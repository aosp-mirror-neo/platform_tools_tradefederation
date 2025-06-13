#!/bin/bash

# Default values
DEFAULT_LEVELS="DEWF"
DEFAULT_CONTEXT_LINES=20
INPUT_FILE=""
OUTPUT_FILE=""
LOG_LEVELS=""
ALLOW_TAGS_LIST="TestRunner"
BLOCK_TAGS_LIST=""
CONTEXT_LINES=""
FORMAT_HAS_UID=true # Default to 'threadtime,uid' format

# Temporary files
TMP_FATAL_ANR_CONTEXT=$(mktemp)
TMP_FILTERED_PASS1=$(mktemp)
TMP_FINAL_OUTPUT=$(mktemp)

# Cleanup temporary files on exit
trap 'rm -f "$TMP_FATAL_ANR_CONTEXT" "$TMP_FILTERED_PASS1" "$TMP_FINAL_OUTPUT"' EXIT

usage() {
    echo "Usage: $0 -i <input_logcat_file> [options]"
    echo ""
    echo "Pre-processes an Android logcat file for debugging."
    echo ""
    echo "Options:"
    echo "  -i <file>     Required. Path to the input logcat file."
    echo "  -o <file>     Optional. Path to the output processed file. (Default: <input_file_name>.processed.txt)"
    echo "  -l <levels>   Optional. Log levels to include (e.g., EWF, DW, V). Default: ${DEFAULT_LEVELS}."
    echo "                V: Verbose, D: Debug, I: Info, W: Warning, E: Error, F: Fatal."
    echo "  -a <tags>     Optional. Comma-separated list of tags to always include, regardless of level."
    echo "                (e.g., 'MyTag1,AnotherTag,MyService')"
    echo "  -b <tags>     Optional. Comma-separated list of tags to always exclude."
    echo "                (e.g., 'NoisyLib,SystemSpam')"
    echo "  -c <lines>    Optional. Number of lines before AND after a FATAL or ANR to keep. Default: ${DEFAULT_CONTEXT_LINES}."
    echo "  -N            Optional. Use 'no UID' format (logcat -v threadtime)."
    echo "                By default, the script assumes 'logcat -v threadtime,uid' format."
    echo "  -h            Display this help message."
    exit 1
}

# Parse command-line arguments
# Note: getopts doesn't natively support long options like --format-no-uid easily.
# We'll use -N and document it as such.
while getopts ":i:o:l:a:b:c:Nh" opt; do
    case ${opt} in
        i) INPUT_FILE="${OPTARG}" ;;
        o) OUTPUT_FILE="${OPTARG}" ;;
        l) LOG_LEVELS="${OPTARG}" ;;
        a) ALLOW_TAGS_LIST="${OPTARG}" ;;
        b) BLOCK_TAGS_LIST="${OPTARG}" ;;
        c) CONTEXT_LINES="${OPTARG}" ;;
        N) FORMAT_HAS_UID=false ;;
        h) usage ;;
        \?) echo "Invalid option: -${OPTARG}" >&2; usage ;;
        :) echo "Option -${OPTARG} requires an argument." >&2; usage ;;
    esac
done

# --- Validate input ---
if [[ -z "$INPUT_FILE" ]]; then
    echo "Error: Input file (-i) is required." >&2
    usage
fi

if [[ ! -f "$INPUT_FILE" ]]; then
    echo "Error: Input file '$INPUT_FILE' not found." >&2
    exit 1
fi

# --- Set defaults if not provided ---
if [[ -z "$LOG_LEVELS" ]]; then
    LOG_LEVELS="$DEFAULT_LEVELS"
fi

if [[ -z "$CONTEXT_LINES" ]]; then
    CONTEXT_LINES="$DEFAULT_CONTEXT_LINES"
fi
# Validate CONTEXT_LINES is a number
if ! [[ "$CONTEXT_LINES" =~ ^[0-9]+$ ]]; then
    echo "Error: Context lines (-c) must be a non-negative integer." >&2
    exit 1
fi


if [[ -z "$OUTPUT_FILE" ]]; then
    OUTPUT_FILE="${INPUT_FILE%.*}.processed.txt"
    if [[ "$OUTPUT_FILE" == "$INPUT_FILE" ]]; then # Handle files with no extension
        OUTPUT_FILE="${INPUT_FILE}.processed.txt"
    fi
fi

LOGCAT_FORMAT_DESCRIPTION="logcat -v threadtime"
if [ "$FORMAT_HAS_UID" = true ]; then
    LOGCAT_FORMAT_DESCRIPTION="logcat -v threadtime,uid"
fi

echo "--- Configuration ---"
echo "Input File:      $INPUT_FILE"
echo "Output File:     $OUTPUT_FILE"
echo "Log Levels:      $LOG_LEVELS"
echo "Allowlist Tags:  ${ALLOW_TAGS_LIST:-None}"
echo "Blocklist Tags:  ${BLOCK_TAGS_LIST:-None}"
echo "Context Lines:   $CONTEXT_LINES"
echo "Logcat Format:   $LOGCAT_FORMAT_DESCRIPTION"
echo "---------------------"

# --- Helper function to build grep patterns ---
# Logcat -v threadtime,uid format: DATE TIME UID/PKG PID TID LEVEL TAG: MESSAGE
# Logcat -v threadtime format:    DATE TIME       PID TID LEVEL TAG: MESSAGE

# Regex for UID/Package field (only if FORMAT_HAS_UID is true):
UID_PKG_REGEX_PART_CONDITIONAL=""
if [ "$FORMAT_HAS_UID" = true ]; then
    UID_PKG_REGEX_PART_CONDITIONAL="[0-9a-zA-Z_.]+ *" # UID/PKG followed by optional spaces
fi

build_tag_grep_pattern() {
    local tag_list="$1"
    local pattern=""
    if [[ -n "$tag_list" ]]; then
        IFS=',' read -r -a tags <<< "$tag_list"
        for i in "${!tags[@]}"; do
            local escaped_tag=$(echo "${tags[$i]}" | sed 's/[].[^$*]/\\&/g')
            # Base pattern: DATE TIME ... PID TID LEVEL TAG:
            # The UID_PKG_REGEX_PART_CONDITIONAL will be empty if -N is used.
            pattern+="^[0-9-]* [0-9:.]* *${UID_PKG_REGEX_PART_CONDITIONAL}[0-9]+ *[0-9]+ [A-Z] +${escaped_tag}(:| )"
            if [[ $i -lt $((${#tags[@]} - 1)) ]]; then
                pattern+="|"
            fi
        done
    fi
    echo "$pattern"
}

build_level_grep_pattern() {
    local levels="$1"
    local pattern=""
    if [[ -n "$levels" ]]; then
        local chars=$(echo "$levels" | fold -w1 | paste -sd "|" -)
        # Base pattern: DATE TIME ... PID TID LEVEL
        pattern="^[0-9-]* [0-9:.]* *${UID_PKG_REGEX_PART_CONDITIONAL}[0-9]+ *[0-9]+ [${chars}] "
    fi
    echo "$pattern"
}


# --- 1. Extract FATAL EXCEPTION and ANR context ---
echo "Step 1: Extracting context for FATAL exceptions and ANRs..."
grep -E -i "FATAL EXCEPTION|ANR in" -A "${CONTEXT_LINES}" -B "${CONTEXT_LINES}" "$INPUT_FILE" > "$TMP_FATAL_ANR_CONTEXT"
echo "Found $(wc -l < "$TMP_FATAL_ANR_CONTEXT") lines of FATAL/ANR context."


# --- 2. Apply other filters (Blocklist, Allowlist, Level) ---
echo "Step 2: Applying blocklist, allowlist, and level filters..."
CURRENT_INPUT="$INPUT_FILE"

# Apply Blocklist Tags
BLOCK_TAG_PATTERN=$(build_tag_grep_pattern "$BLOCK_TAGS_LIST")
if [[ -n "$BLOCK_TAG_PATTERN" ]]; then
    echo "Applying blocklist tags: $BLOCK_TAGS_LIST"
    grep -E -v "$BLOCK_TAG_PATTERN" "$CURRENT_INPUT" > "$TMP_FILTERED_PASS1.block.tmp"
    CURRENT_INPUT="$TMP_FILTERED_PASS1.block.tmp"
else
    if [ "$CURRENT_INPUT" == "$INPUT_FILE" ]; then # Only copy if no operations yet to avoid unnecessary copy
        cp "$INPUT_FILE" "$TMP_FILTERED_PASS1.noblock.tmp" # Create a distinct file for awk input
        CURRENT_INPUT="$TMP_FILTERED_PASS1.noblock.tmp"
    fi
fi

# Apply Allowlist Tags OR Level Filters using awk
ALLOW_TAG_PATTERN=$(build_tag_grep_pattern "$ALLOW_TAGS_LIST")
LEVEL_PATTERN=$(build_level_grep_pattern "$LOG_LEVELS")

# Pass shell variables to awk using -v
# For LOG_LEVELS and DEFAULT_LEVELS, ensure awk gets their string values correctly
awk -v allow_pat="$ALLOW_TAG_PATTERN" \
    -v level_pat="$LEVEL_PATTERN" \
    -v shell_allow_tags_list="$ALLOW_TAGS_LIST" \
    -v shell_log_levels="$LOG_LEVELS" \
    -v shell_default_levels="$DEFAULT_LEVELS" '
{
    line_passes = 0

    # Check allowlist tag
    if (allow_pat != "" && $0 ~ allow_pat) {
        line_passes = 1
    }

    # Check level (only if not already passed by allowlist)
    if (line_passes == 0 && level_pat != "" && $0 ~ level_pat) {
        line_passes = 1
    }
    
    # This logic handles cases where no specific positive filters are active.
    # If user explicitly provided an empty -l "" and no -a, line_passes remains 0 for non-allowlisted lines.
    # If -l was not provided (so it uses default EWF), level_pat will be non-empty.
    # If -l was provided but with invalid chars for level_pat generation, level_pat might be empty.
    # The goal: if neither allowlist tags nor effective level filters were specified by the user (beyond defaults already handled),
    # then lines that survived blocklisting should pass.
    # This is simplified: if both patterns end up empty, it means no specific positive filtering is requested.
    if (allow_pat == "" && level_pat == "") {
        # This is true if:
        # 1. User provided no -a AND (no -l OR -l="" OR -l contained no valid level chars)
        # In this scenario, all lines that reached awk (i.e., survived blocklist) should pass.
        line_passes = 1
    }


    if (line_passes) {
        print $0
    }
}
' "$CURRENT_INPUT" > "$TMP_FILTERED_PASS1"

# Clean up intermediate block/noblock filter file if it was created
if [ -f "$TMP_FILTERED_PASS1.block.tmp" ]; then
    rm -f "$TMP_FILTERED_PASS1.block.tmp"
fi
if [ -f "$TMP_FILTERED_PASS1.noblock.tmp" ]; then
    rm -f "$TMP_FILTERED_PASS1.noblock.tmp"
fi


echo "Found $(wc -l < "$TMP_FILTERED_PASS1") lines after main filtering."

# --- 3. Combine FATAL/ANR context with filtered logs and de-duplicate ---
echo "Step 3: Combining results and de-duplicating..."
cat "$TMP_FATAL_ANR_CONTEXT" "$TMP_FILTERED_PASS1" | awk '!seen[$0]++' > "$TMP_FINAL_OUTPUT"

# --- 4. Save to output file ---
cp "$TMP_FINAL_OUTPUT" "$OUTPUT_FILE"
echo "---------------------"
echo "Processing complete. Output saved to: $OUTPUT_FILE"
echo "Total lines in output: $(wc -l < "$OUTPUT_FILE")"

exit 0
