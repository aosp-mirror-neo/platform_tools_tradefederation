/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may not use this file at
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
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Value;
import java.util.List;
import perfetto.protos.V2Metric.TraceMetricV2Bundle.Row;

/** Utility class for converting Perfetto V2 metrics. */
public final class PerfettoV2MetricConverterUtil {
    private PerfettoV2MetricConverterUtil() {}

    private static final String METRIC_SEP = "-";

    /**
     * Creates a full legacy metric key from a metric ID and Perfetto dimensions.
     *
     * @return A string like "metric_id-dim1-dim2"
     */
    public static String createLegacyMetricKey(String metricId, List<Row.Dimension> dimensions) {
        if (metricId == null) {
            throw new IllegalArgumentException("Metric ID cannot be null.");
        }
        if (dimensions == null) {
            throw new IllegalArgumentException("Dimensions list cannot be null.");
        }
        return metricId.replace(' ', '_') + createLegacyDimensionsKey(dimensions);
    }

    /**
     * Creates a full legacy metric key from a metric ID and Tradefed dimensions.
     *
     * @return A string like "metric_id-dim1-dim2"
     */
    public static String createLegacyMetricKeyFromTf(String metricId, List<Dimension> dimensions) {
        if (metricId == null) {
            throw new IllegalArgumentException("Metric ID cannot be null.");
        }
        if (dimensions == null) {
            throw new IllegalArgumentException("Dimensions list cannot be null.");
        }
        return metricId.replace(' ', '_') + createLegacyDimensionsKeyFromTf(dimensions);
    }

    /**
     * Creates the dimension part of a legacy metric key from Perfetto dimensions.
     *
     * @return A string like "-dim1-dim2"
     */
    private static String createLegacyDimensionsKey(List<Row.Dimension> dimensions) {
        StringBuilder builder = new StringBuilder();
        for (Row.Dimension dim : dimensions) {
            builder.append(METRIC_SEP)
                    .append(getLegacyDimensionValueString(createValueFromPerfettoDimension(dim)));
        }
        return builder.toString();
    }

    /**
     * Creates the dimension part of a legacy metric key from Tradefed dimensions.
     *
     * @return A string like "-dim1-dim2"
     */
    private static String createLegacyDimensionsKeyFromTf(List<Dimension> dimensions) {
        StringBuilder builder = new StringBuilder();
        for (Dimension dim : dimensions) {
            builder.append(METRIC_SEP).append(getLegacyDimensionValueString(dim.getValue()));
        }
        return builder.toString();
    }

    /** Gets the string representation of {@link Value} for legacy metric key generation. */
    private static String getLegacyDimensionValueString(Value val) {
        switch (val.getValueCase()) {
            case STRING_VALUE:
                return val.getStringValue().replace(' ', '_');
            case INT_VALUE:
                return String.valueOf(val.getIntValue());
            case DOUBLE_VALUE:
                return String.valueOf(val.getDoubleValue());
            case BOOL_VALUE:
                return String.valueOf(val.getBoolValue());
            default:
                return "unknown";
        }
    }

    /** Creates a {@link Value} proto from Perfetto {@link Row.Dimension} proto. */
    static Value createValueFromPerfettoDimension(Row.Dimension dimension) {
        Value.Builder valueBuilder = Value.newBuilder();
        switch (dimension.getValueOneofCase()) {
            case STRING_VALUE:
                valueBuilder.setStringValue(dimension.getStringValue());
                break;
            case INT64_VALUE:
                valueBuilder.setIntValue(dimension.getInt64Value());
                break;
            case DOUBLE_VALUE:
                valueBuilder.setDoubleValue(dimension.getDoubleValue());
                break;
            case BOOL_VALUE:
                valueBuilder.setBoolValue(dimension.getBoolValue());
                break;
            case NULL_VALUE:
                valueBuilder.setStringValue("null");
                break;
            default:
                break;
        }
        return valueBuilder.build();
    }
}
