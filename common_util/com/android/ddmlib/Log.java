/*
 * Copyright (C) 2007 The Android Open Source Project
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
package com.android.ddmlib;

public class Log {

    /** Log Level enum. */
    public enum LogLevel {
        VERBOSE(2, "verbose", 'V'), // $NON-NLS-1$
        DEBUG(3, "debug", 'D'), // $NON-NLS-1$
        INFO(4, "info", 'I'), // $NON-NLS-1$
        WARN(5, "warn", 'W'), // $NON-NLS-1$
        ERROR(6, "error", 'E'), // $NON-NLS-1$
        ASSERT(7, "assert", 'A'); // $NON-NLS-1$

        private int mPriorityLevel;
        private String mStringValue;
        private char mPriorityLetter;

        LogLevel(int intPriority, String stringValue, char priorityChar) {
            mPriorityLevel = intPriority;
            mStringValue = stringValue;
            mPriorityLetter = priorityChar;
        }

        public static LogLevel getByString(String value) {
            for (LogLevel mode : values()) {
                if (mode.mStringValue.equals(value)) {
                    return mode;
                }
            }

            return null;
        }

        /**
         * Returns the {@link LogLevel} enum matching the specified letter.
         *
         * @param letter the letter matching a <code>LogLevel</code> enum
         * @return a <code>LogLevel</code> object or <code>null</code> if no match were found.
         */
        public static LogLevel getByLetter(char letter) {
            for (LogLevel mode : values()) {
                if (mode.mPriorityLetter == letter) {
                    return mode;
                }
            }

            return null;
        }

        /**
         * Returns the {@link LogLevel} enum matching the specified letter.
         *
         * <p>The letter is passed as a {@link String} argument, but only the first character is
         * used.
         *
         * @param letter the letter matching a <code>LogLevel</code> enum
         * @return a <code>LogLevel</code> object or <code>null</code> if no match were found.
         */
        public static LogLevel getByLetterString(String letter) {
            if (!letter.isEmpty()) {
                return getByLetter(letter.charAt(0));
            }

            return null;
        }

        /** Returns the letter identifying the priority of the {@link LogLevel}. */
        public char getPriorityLetter() {
            return mPriorityLetter;
        }

        /** Returns the numerical value of the priority. */
        public int getPriority() {
            return mPriorityLevel;
        }

        /** Returns a non translated string representing the LogLevel. */
        public String getStringValue() {
            return mStringValue;
        }
    }

    /**
     * Outputs a {@link LogLevel#VERBOSE} level message.
     *
     * @param tag The tag associated with the message.
     * @param message The message to output.
     */
    public static void v(String tag, String message) {
        com.android.tradefed.log.Log.v(tag, message);
    }

    /**
     * Outputs a {@link LogLevel#DEBUG} level message.
     *
     * @param tag The tag associated with the message.
     * @param message The message to output.
     */
    public static void d(String tag, String message) {
        com.android.tradefed.log.Log.d(tag, message);
    }

    /**
     * Outputs a {@link LogLevel#INFO} level message.
     *
     * @param tag The tag associated with the message.
     * @param message The message to output.
     */
    public static void i(String tag, String message) {
        com.android.tradefed.log.Log.i(tag, message);
    }

    /**
     * Outputs a {@link LogLevel#WARN} level message.
     *
     * @param tag The tag associated with the message.
     * @param message The message to output.
     */
    public static void w(String tag, String message) {
        com.android.tradefed.log.Log.w(tag, message);
    }

    /**
     * Outputs a {@link LogLevel#ERROR} level message.
     *
     * @param tag The tag associated with the message.
     * @param message The message to output.
     */
    public static void e(String tag, String message) {
        com.android.tradefed.log.Log.e(tag, message);
    }

    /**
     * Outputs a {@link LogLevel#ERROR} level {@link Throwable} information.
     *
     * @param tag The tag associated with the message.
     * @param throwable The {@link Throwable} to output.
     */
    public static void e(String tag, Throwable throwable) {
        com.android.tradefed.log.Log.e(tag, throwable);
    }

    public static void logAndDisplay(LogLevel logLevel, String tag, String message) {
        com.android.tradefed.log.Log.logAndDisplay(
                com.android.tradefed.log.Log.LogLevel.convertFromDdmlib(logLevel), tag, message);
    }
}
