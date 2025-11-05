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

import static org.junit.Assert.assertEquals;

import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Dimension;
import com.android.tradefed.dimensional_metrics.proto.DimensionalMetrics.Value;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;
import perfetto.protos.V2Metric.TraceMetricV2Bundle.Row;
import perfetto.protos.V2Metric.TraceMetricV2Bundle.Row.Dimension.Null;

/** Unit tests for {@link PerfettoV2MetricConverterUtil}. */
@RunWith(JUnit4.class)
public class PerfettoV2MetricConverterUtilTest {
    @Test
    public void testCreateLegacyMetricKey() {
        String metricId = "metric id";
        Row.Dimension stringDim = Row.Dimension.newBuilder().setStringValue("string value").build();
        Row.Dimension intDim = Row.Dimension.newBuilder().setInt64Value(123).build();
        Row.Dimension doubleDim = Row.Dimension.newBuilder().setDoubleValue(45.6).build();
        Row.Dimension boolDim = Row.Dimension.newBuilder().setBoolValue(true).build();
        Row.Dimension nullDim =
                Row.Dimension.newBuilder().setNullValue(Null.getDefaultInstance()).build();

        String key =
                PerfettoV2MetricConverterUtil.createLegacyMetricKey(
                        metricId, Arrays.asList(stringDim, intDim, doubleDim, boolDim, nullDim));

        assertEquals("metric_id-string_value-123-45.6-true-null", key);
    }

    @Test
    public void testCreateLegacyMetricKey_noDimensions() {
        String key =
                PerfettoV2MetricConverterUtil.createLegacyMetricKey(
                        "metric", Collections.emptyList());
        assertEquals("metric", key);
    }

    @Test
    public void testCreateLegacyMetricKeyFromTf() {
        String metricId = "metric id";
        Dimension stringDim =
                Dimension.newBuilder()
                        .setValue(Value.newBuilder().setStringValue("string value"))
                        .build();
        Dimension intDim =
                Dimension.newBuilder().setValue(Value.newBuilder().setIntValue(123)).build();
        Dimension doubleDim =
                Dimension.newBuilder().setValue(Value.newBuilder().setDoubleValue(45.6)).build();
        Dimension boolDim =
                Dimension.newBuilder().setValue(Value.newBuilder().setBoolValue(false)).build();
        Dimension unknownDim = Dimension.newBuilder().setValue(Value.getDefaultInstance()).build();

        String key =
                PerfettoV2MetricConverterUtil.createLegacyMetricKeyFromTf(
                        metricId, Arrays.asList(stringDim, intDim, doubleDim, boolDim, unknownDim));

        assertEquals("metric_id-string_value-123-45.6-false-unknown", key);
    }

    @Test
    public void testCreateLegacyMetricKeyFromTf_noDimensions() {
        String key =
                PerfettoV2MetricConverterUtil.createLegacyMetricKeyFromTf(
                        "metric", Collections.emptyList());
        assertEquals("metric", key);
    }

    @Test
    public void testCreateValueFromPerfettoDimension() {
        Row.Dimension stringDim = Row.Dimension.newBuilder().setStringValue("value").build();
        Value stringValue =
                PerfettoV2MetricConverterUtil.createValueFromPerfettoDimension(stringDim);
        assertEquals(Value.newBuilder().setStringValue("value").build(), stringValue);

        Row.Dimension intDim = Row.Dimension.newBuilder().setInt64Value(123).build();
        Value intValue = PerfettoV2MetricConverterUtil.createValueFromPerfettoDimension(intDim);
        assertEquals(Value.newBuilder().setIntValue(123).build(), intValue);

        Row.Dimension doubleDim = Row.Dimension.newBuilder().setDoubleValue(45.6).build();
        Value doubleValue =
                PerfettoV2MetricConverterUtil.createValueFromPerfettoDimension(doubleDim);
        assertEquals(Value.newBuilder().setDoubleValue(45.6).build(), doubleValue);

        Row.Dimension boolDim = Row.Dimension.newBuilder().setBoolValue(true).build();
        Value boolValue = PerfettoV2MetricConverterUtil.createValueFromPerfettoDimension(boolDim);
        assertEquals(Value.newBuilder().setBoolValue(true).build(), boolValue);

        Row.Dimension nullDim =
                Row.Dimension.newBuilder().setNullValue(Null.getDefaultInstance()).build();
        Value nullValue = PerfettoV2MetricConverterUtil.createValueFromPerfettoDimension(nullDim);
        assertEquals(Value.newBuilder().setStringValue("null").build(), nullValue);

        Row.Dimension unknownDim = Row.Dimension.getDefaultInstance();
        Value unknownValue =
                PerfettoV2MetricConverterUtil.createValueFromPerfettoDimension(unknownDim);
        assertEquals(Value.getDefaultInstance(), unknownValue);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateLegacyMetricKey_nullMetricId_throwsException() {
        PerfettoV2MetricConverterUtil.createLegacyMetricKey(null, Collections.emptyList());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateLegacyMetricKey_nullDimensions_throwsException() {
        PerfettoV2MetricConverterUtil.createLegacyMetricKey("metric", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateLegacyMetricKeyFromTf_nullMetricId_throwsException() {
        PerfettoV2MetricConverterUtil.createLegacyMetricKeyFromTf(null, Collections.emptyList());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateLegacyMetricKeyFromTf_nullDimensions_throwsException() {
        PerfettoV2MetricConverterUtil.createLegacyMetricKeyFromTf("metric", null);
    }
}
