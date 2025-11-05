/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.postprocessor.util;

import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Dimension;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.DimensionalMetric;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.MetricRecord;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.MetricReport;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Polarity;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.RawValues;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Source;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.TestIdentifier;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.TestMetricResult;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Unit;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Value;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Value.ValueCase;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.TestDescription;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import perfetto.protos.File.TraceSummary;
import perfetto.protos.V2Metric.TraceMetricV2Bundle;
import perfetto.protos.V2Metric.TraceMetricV2Bundle.Row;
import perfetto.protos.V2Metric.TraceMetricV2Spec;

/**
 * Converts Perfetto V2 {@link TraceSummary} proto into a {@link MetricReport} for dimensional
 * metric reporting.
 */
public class PerfettoV2ToDimensionalMetricConverter {

    /**
     * Helper class to extract dimensions from a {@link Row} based on a {@link TraceMetricV2Spec}.
     */
    private static class DimensionExtractor {
        private final TraceMetricV2Spec mSpec;

        DimensionExtractor(TraceMetricV2Spec spec) {
            this.mSpec = spec;
        }

        /**
         * Extracts a list of {@link Dimension} protos from {@link Row} based on the spec.
         *
         * @param row The row to extract dimensions from.
         * @return A list of dimensions.
         */
        public List<Dimension> extractDimensions(Row row) {
            List<Dimension> dimensions = new ArrayList<>();
            if (!mSpec.getDimensionsSpecsList().isEmpty()) {
                for (int i = 0; i < mSpec.getDimensionsSpecsCount(); i++) {
                    if (i >= row.getDimensionCount()) {
                        CLog.w(
                                "Mismatch in dimension counts. Spec has %d, but row only has %d.",
                                mSpec.getDimensionsSpecsCount(), row.getDimensionCount());
                        break;
                    }
                    String dimName = mSpec.getDimensionsSpecs(i).getName();
                    dimensions.add(createDimensionProto(dimName, row.getDimension(i)));
                }
            } else if (!mSpec.getDimensionsList().isEmpty()) {
                for (int i = 0; i < mSpec.getDimensionsCount(); i++) {
                    if (i >= row.getDimensionCount()) {
                        CLog.w(
                                "Mismatch in dimension counts. Spec has %d, but row only has %d.",
                                mSpec.getDimensionsCount(), row.getDimensionCount());
                        break;
                    }
                    String dimName = mSpec.getDimensions(i);
                    dimensions.add(createDimensionProto(dimName, row.getDimension(i)));
                }
            }
            return dimensions;
        }

        private Dimension createDimensionProto(String name, Row.Dimension dimension) {
            Value value = PerfettoV2MetricConverterUtil.createValueFromPerfettoDimension(dimension);
            if (value.getValueCase() == ValueCase.VALUE_NOT_SET) {
                CLog.w(
                        "Unknown dimension value type '%s' for dimension '%s'.",
                        dimension.getValueOneofCase(), name);
            }
            return Dimension.newBuilder().setKey(name).setValue(value).build();
        }
    }

    /**
     * Converts Perfetto V2 {@link TraceSummary} proto into a {@link MetricReport} for dimensional
     * metric reporting.
     */
    @Nullable
    public MetricReport convert(
            TraceSummary traceSummary, @Nullable TestDescription testDescription) {
        CLog.d("Starting Perfetto V2 to dimensional metric conversion.");
        TestMetricResult.Builder testMetricResultBuilder =
                initializeTestMetricResultBuilder(testDescription);
        boolean hasRecords = false;

        List<TraceMetricV2Bundle> bundles = traceSummary.getMetricBundlesList();
        CLog.d("Processing %d metric bundles.", bundles.size());

        for (TraceMetricV2Bundle bundle : bundles) {
            if (bundle.getSpecsCount() == 0) {
                CLog.d("Skipping bundle with no metric specs.");
                continue;
            }
            Map<List<Dimension>, List<List<Value>>> groupedByDimensions =
                    groupMetricsByDimension(bundle);
            for (int i = 0; i < bundle.getSpecsCount(); i++) {
                MetricRecord record = buildMetricRecord(bundle.getSpecs(i), i, groupedByDimensions);
                if (record != null) {
                    testMetricResultBuilder.addMetricRecords(record);
                    hasRecords = true;
                }
            }
        }

        if (!hasRecords) {
            CLog.d("No metric records generated. Returning null report.");
            return null;
        }

        CLog.d("Finished conversion, returning populated metric report.");
        return MetricReport.newBuilder().addTestMetricResults(testMetricResultBuilder).build();
    }

    /** Initializes the {@link TestMetricResult.Builder} with test identifier info. */
    private TestMetricResult.Builder initializeTestMetricResultBuilder(
            @Nullable TestDescription testDescription) {
        TestMetricResult.Builder builder = TestMetricResult.newBuilder();
        IteratedTest iteratedTest = new IteratedTest(testDescription);
        builder.setIdentifier(iteratedTest.toTestIdentifier());
        if (iteratedTest.iteration != null) {
            builder.setIteration(iteratedTest.iteration);
        }
        return builder;
    }

    /** Builds a {@link MetricRecord} for a given spec and grouped dimensions. */
    @Nullable
    private MetricRecord buildMetricRecord(
            TraceMetricV2Spec spec,
            int specIndex,
            Map<List<Dimension>, List<List<Value>>> groupedByDimensions) {
        if (!spec.hasId()) {
            CLog.w("Skipping metric spec at index %d due to missing 'id' field.", specIndex);
            return null;
        }
        CLog.d("Building record for metric: %s", spec.getId());
        MetricRecord.Builder recordBuilder =
                MetricRecord.newBuilder()
                        .setMetricId(spec.getId())
                        .setHumanReadableName(spec.getId())
                        .setSource(Source.PERFETTO_V2);
        recordBuilder.setUnit(convertPerfettoUnitToCbUnit(spec));
        recordBuilder.setPolarity(convertPerfettoPolarityToCbPolarity(spec.getPolarity()));

        for (Map.Entry<List<Dimension>, List<List<Value>>> entry : groupedByDimensions.entrySet()) {
            List<Dimension> dimensions = entry.getKey();
            List<Value> values = entry.getValue().get(specIndex);
            if (values.isEmpty()) {
                continue;
            }

            String legacyKey =
                    PerfettoV2MetricConverterUtil.createLegacyMetricKeyFromTf(
                            spec.getId(), dimensions);

            // Sort dimensions alphabetically by key for consistent reporting.
            List<Dimension> sortedDimensions =
                    dimensions.stream()
                            .sorted(Comparator.comparing(Dimension::getKey))
                            .collect(Collectors.toList());

            recordBuilder.addDimensionalMetrics(
                    DimensionalMetric.newBuilder()
                            .addAllDimensions(sortedDimensions)
                            .setRawValues(RawValues.newBuilder().addAllValues(values))
                            .setLegacyMetricKey(legacyKey));
        }

        if (recordBuilder.getDimensionalMetricsCount() == 0) {
            CLog.d("No dimensional metrics found for metric '%s'. Skipping record.", spec.getId());
            return null;
        }

        return recordBuilder.build();
    }

    private Map<List<Dimension>, List<List<Value>>> groupMetricsByDimension(
            TraceMetricV2Bundle bundle) {
        List<String> specIds =
                bundle.getSpecsList().stream()
                        .map(TraceMetricV2Spec::getId)
                        .collect(Collectors.toList());
        CLog.d("Grouping metrics for bundle with specs: %s", specIds);

        // This method effectively creates buckets based on dimension sets. For each bucket (i.e.,
        // for
        // each unique set of dimensions), it maintains a list of lists (List<List<Value>>), where
        // each
        // inner list holds the values for one specific metric, and the order of these inner lists
        // matches the order of metric specs in the Perfetto bundle (bundle.getSpecsList()).
        Map<List<Dimension>, List<List<Value>>> groupedByDimensions = new LinkedHashMap<>();
        // A map from a canonical (order-independent) representation of a dimension set to the
        // original-order list of dimensions. This allows grouping metrics with the same dimensions
        // regardless of order, while preserving the original order from the first-seen row
        // for legacy_metric_key consistency.
        Map<Set<Dimension>, List<Dimension>> canonicalToOriginal = new LinkedHashMap<>();
        DimensionExtractor dimensionExtractor = new DimensionExtractor(bundle.getSpecs(0));

        for (Row row : bundle.getRowList()) {
            List<Dimension> dimensions = dimensionExtractor.extractDimensions(row);

            // Use a Set for order-independent comparison, but store with original order via mapKey.
            Set<Dimension> canonicalKey = new HashSet<>(dimensions);
            List<Dimension> mapKey =
                    canonicalToOriginal.computeIfAbsent(canonicalKey, k -> dimensions);

            // Get or create the list of value lists for this dimension set (mapKey).
            // If the dimension set is new, computeIfAbsent creates an outer list
            // with N inner ArrayLists, where N is the number of metric specs in the bundle.
            List<List<Value>> metricValues =
                    groupedByDimensions.computeIfAbsent(
                            mapKey,
                            k -> {
                                List<List<Value>> newList = new ArrayList<>();
                                for (int i = 0; i < bundle.getSpecsCount(); i++) {
                                    newList.add(new ArrayList<>());
                                }
                                return newList;
                            });

            // Append row values to the correct metric list based on index.
            // row.getValues(i) corresponds to bundle.getSpecs(i), so we add it to
            // metricValues.get(i).
            for (int i = 0; i < row.getValuesCount(); i++) {
                if (i >= bundle.getSpecsCount()) {
                    CLog.w(
                            "Row has more values (%d) than there are metric specs (%d). Ignoring"
                                    + " extra values.",
                            row.getValuesCount(), bundle.getSpecsCount());
                    break;
                }
                Row.Value rowValue = row.getValues(i);
                if (rowValue.hasDoubleValue()) {
                    metricValues
                            .get(i)
                            .add(
                                    Value.newBuilder()
                                            .setDoubleValue(rowValue.getDoubleValue())
                                            .build());
                }
            }
        }
        CLog.d(
                "Finished grouping %d rows into %d unique dimension sets.",
                bundle.getRowList().size(), groupedByDimensions.size());
        return groupedByDimensions;
    }

    /** Helper class to parse test identifiers and extract iterations. */
    private static class IteratedTest {
        private static final Pattern ITERATION_PATTERN = Pattern.compile("\\$(\\d+)$");
        final String fullTestName;
        final String className;
        final String methodName;
        final Long iteration;

        IteratedTest(@Nullable TestDescription testDescription) {
            if (testDescription == null) {
                this.fullTestName = null;
                this.className = null;
                this.methodName = null;
                this.iteration = null;
                return;
            }

            this.fullTestName = testDescription.toString();
            String currentClassName = testDescription.getClassName();
            String currentMethodName = testDescription.getTestName();
            Long resultIteration = null;

            // Example: "testMethod$123" -> iteration = 123, modifiedName = "testMethod"
            IterationExtractionResult methodResult = extractIteration(currentMethodName, "method");
            if (methodResult.iteration != null) {
                resultIteration = methodResult.iteration;
                currentMethodName = methodResult.name;
            } else {
                IterationExtractionResult classResult = extractIteration(currentClassName, "class");
                if (classResult.iteration != null) {
                    resultIteration = classResult.iteration;
                    currentClassName = classResult.name;
                }
            }

            this.className = currentClassName;
            this.methodName = currentMethodName;
            this.iteration = resultIteration;
        }

        TestIdentifier toTestIdentifier() {
            TestIdentifier.Builder identifierBuilder = TestIdentifier.newBuilder();
            if (fullTestName != null) {
                identifierBuilder.setFullTestName(fullTestName);
            }
            if (className != null) {
                identifierBuilder.setTestClass(className);
            }
            if (methodName != null) {
                identifierBuilder.setMethod(methodName);
            }
            return identifierBuilder.build();
        }

        private static class IterationExtractionResult {
            public final String name;
            public final Long iteration;

            public IterationExtractionResult(String name, Long iteration) {
                this.name = name;
                this.iteration = iteration;
            }
        }

        private IterationExtractionResult extractIteration(String name, String nameType) {
            if (name == null) {
                return new IterationExtractionResult(name, null);
            }
            Matcher matcher = ITERATION_PATTERN.matcher(name);
            if (matcher.find()) {
                try {
                    return new IterationExtractionResult(
                            name.substring(0, matcher.start()), Long.parseLong(matcher.group(1)));
                } catch (NumberFormatException e) {
                    CLog.w(
                            "Found a non-numeric iteration suffix in %s name '%s'. Ignoring.",
                            nameType, name);
                }
            }
            return new IterationExtractionResult(name, null);
        }
    }

    private Unit convertPerfettoUnitToCbUnit(TraceMetricV2Spec spec) {
        if (spec.hasCustomUnit()) {
            CLog.w(
                    "Custom units are not supported. Ignoring custom_unit: %s",
                    spec.getCustomUnit());
            return Unit.UNIT_UNSPECIFIED;
        }
        try {
            return Unit.valueOf(spec.getUnit().name());
        } catch (IllegalArgumentException e) {
            return Unit.UNIT_UNSPECIFIED;
        }
    }

    private Polarity convertPerfettoPolarityToCbPolarity(
            TraceMetricV2Spec.MetricPolarity polarity) {
        try {
            return Polarity.valueOf(polarity.name());
        } catch (IllegalArgumentException e) {
            return Polarity.POLARITY_UNSPECIFIED;
        }
    }
}
