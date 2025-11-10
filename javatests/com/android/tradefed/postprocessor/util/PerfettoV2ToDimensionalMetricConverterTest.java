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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
import com.android.tradefed.result.TestDescription;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import perfetto.protos.File.TraceSummary;
import perfetto.protos.V2Metric.TraceMetricV2Bundle;
import perfetto.protos.V2Metric.TraceMetricV2Bundle.Row;
import perfetto.protos.V2Metric.TraceMetricV2Spec;

/** Unit tests for {@link PerfettoV2ToDimensionalMetricConverter}. */
@RunWith(JUnit4.class)
public class PerfettoV2ToDimensionalMetricConverterTest {

    private PerfettoV2ToDimensionalMetricConverter mConverter;

    @Before
    public void setUp() {
        mConverter = new PerfettoV2ToDimensionalMetricConverter();
    }

    @Test
    public void testConvert_complexTraceSummary() {
        TraceSummary summary = createComplexTraceSummary();
        MetricReport report = mConverter.convert(summary, null);

        MetricReport expectedReport = buildExpectedComplexDimensionalReport();
        assertEquals(expectedReport, report);
    }

    private TraceSummary createComplexTraceSummary() {
        // Bundle 1: metric-name-1
        TraceMetricV2Spec metric1Spec =
                TraceMetricV2Spec.newBuilder()
                        .setId("metric-name-1")
                        .addDimensions("dimension-key-1")
                        .setUnit(TraceMetricV2Spec.MetricUnit.TIME_NANOS)
                        .setPolarity(TraceMetricV2Spec.MetricPolarity.LOWER_IS_BETTER)
                        .build();

        Row metric1Row1 =
                Row.newBuilder()
                        .addDimension(
                                Row.Dimension.newBuilder().setStringValue("dimension-value-1-1"))
                        .addValues(Row.Value.newBuilder().setDoubleValue(123.0))
                        .build();
        Row metric1Row2 =
                Row.newBuilder()
                        .addDimension(
                                Row.Dimension.newBuilder().setStringValue("dimension-value-1-1"))
                        .addValues(Row.Value.newBuilder().setDoubleValue(456.0))
                        .build();
        Row metric1Row3 =
                Row.newBuilder()
                        .addDimension(
                                Row.Dimension.newBuilder()
                                        .setNullValue(Row.Dimension.Null.getDefaultInstance()))
                        .addValues(Row.Value.newBuilder().setDoubleValue(10464441.0))
                        .build();

        TraceMetricV2Bundle bundle1 =
                TraceMetricV2Bundle.newBuilder()
                        .addSpecs(metric1Spec)
                        .addRow(metric1Row1)
                        .addRow(metric1Row2)
                        .addRow(metric1Row3)
                        .build();

        // Bundle 2: metric-name-2, metric-name-3
        TraceMetricV2Spec metric2Spec =
                TraceMetricV2Spec.newBuilder()
                        .setId("metric-name-2")
                        .addDimensions("dimension-key-2-1")
                        .addDimensions("dimension-key-2-2")
                        .addDimensions("dimension-key-2-3")
                        .setUnit(TraceMetricV2Spec.MetricUnit.TIME_MILLIS)
                        .setPolarity(TraceMetricV2Spec.MetricPolarity.HIGHER_IS_BETTER)
                        .build();

        TraceMetricV2Spec metric3Spec =
                TraceMetricV2Spec.newBuilder()
                        .setId("metric-name-3")
                        .addDimensions("dimension-key-2-1")
                        .addDimensions("dimension-key-2-2")
                        .addDimensions("dimension-key-2-3")
                        .setUnit(TraceMetricV2Spec.MetricUnit.COUNT)
                        .setPolarity(TraceMetricV2Spec.MetricPolarity.NOT_APPLICABLE)
                        .build();

        Row bundle2Row1 =
                Row.newBuilder()
                        .addDimension(
                                Row.Dimension.newBuilder().setStringValue("dimension-value-2-1"))
                        .addDimension(Row.Dimension.newBuilder().setInt64Value(1234567890))
                        .addDimension(Row.Dimension.newBuilder().setDoubleValue(123.0))
                        .addValues(
                                Row.Value.newBuilder()
                                        .setDoubleValue(260051.0)) // for metric-name-2
                        .addValues(
                                Row.Value.newBuilder()
                                        .setNullValue(
                                                Row.Value.Null
                                                        .getDefaultInstance())) // for metric-name-3
                        .build();

        Row bundle2Row2 =
                Row.newBuilder()
                        .addDimension(
                                Row.Dimension.newBuilder().setStringValue("dimension-value-2-1"))
                        .addDimension(Row.Dimension.newBuilder().setInt64Value(12907))
                        .addDimension(Row.Dimension.newBuilder().setDoubleValue(456.0))
                        .addValues(
                                Row.Value.newBuilder()
                                        .setDoubleValue(158854.0)) // for metric-name-2
                        .addValues(
                                Row.Value.newBuilder().setDoubleValue(345.0)) // for metric-name-3
                        .build();

        Row bundle2Row3 =
                Row.newBuilder()
                        .addDimension(
                                Row.Dimension.newBuilder().setStringValue("dimension-value-2-2"))
                        .addDimension(Row.Dimension.newBuilder().setInt64Value(1234567890))
                        .addDimension(Row.Dimension.newBuilder().setDoubleValue(234.0))
                        .addValues(
                                Row.Value.newBuilder()
                                        .setNullValue(
                                                Row.Value.Null
                                                        .getDefaultInstance())) // for metric-name-2
                        .addValues(
                                Row.Value.newBuilder().setDoubleValue(321.0)) // for metric-name-3
                        .build();

        Row bundle2Row4MissingDim =
                Row.newBuilder()
                        .addDimension(
                                Row.Dimension.newBuilder().setStringValue("dimension-value-2-3"))
                        .addDimension(Row.Dimension.newBuilder().setInt64Value(999))
                        // Missing third dimension
                        .addValues(
                                Row.Value.newBuilder().setDoubleValue(777.0)) // for metric-name-2
                        .addValues(
                                Row.Value.newBuilder().setDoubleValue(888.0)) // for metric-name-3
                        .build();

        TraceMetricV2Bundle bundle2 =
                TraceMetricV2Bundle.newBuilder()
                        .addSpecs(metric2Spec)
                        .addSpecs(metric3Spec)
                        .addRow(bundle2Row1)
                        .addRow(bundle2Row2)
                        .addRow(bundle2Row3)
                        .addRow(bundle2Row4MissingDim)
                        .build();

        // Bundle 3: metric-name-4
        TraceMetricV2Spec noDimsSpec =
                TraceMetricV2Spec.newBuilder()
                        .setId("metric-name-4")
                        .setUnit(TraceMetricV2Spec.MetricUnit.COUNT)
                        .build();

        Row noDimsRow =
                Row.newBuilder()
                        .addValues(Row.Value.newBuilder().setDoubleValue(1.0).build())
                        .build();

        TraceMetricV2Bundle bundle3 =
                TraceMetricV2Bundle.newBuilder().addSpecs(noDimsSpec).addRow(noDimsRow).build();

        return TraceSummary.newBuilder()
                .addMetricBundles(bundle1)
                .addMetricBundles(bundle2)
                .addMetricBundles(bundle3)
                .build();
    }

    private MetricReport buildExpectedComplexDimensionalReport() {
        // --- metric-name-1 ---
        MetricRecord metric1Record =
                MetricRecord.newBuilder()
                        .setMetricId("metric-name-1")
                        .setHumanReadableName("metric-name-1")
                        .setUnit(Unit.TIME_NANOS)
                        .setPolarity(Polarity.LOWER_IS_BETTER)
                        .setSource(Source.PERFETTO_V2)
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-1")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "dimension-value-1-1")))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(123.0))
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(456.0)))
                                        .setLegacyMetricKey("metric-name-1-dimension-value-1-1"))
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-1")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue("null")))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(
                                                                                10464441.0)))
                                        .setLegacyMetricKey("metric-name-1-null"))
                        .build();

        // --- metric-name-2 ---
        MetricRecord metric2Record =
                MetricRecord.newBuilder()
                        .setMetricId("metric-name-2")
                        .setHumanReadableName("metric-name-2")
                        .setUnit(Unit.TIME_MILLIS)
                        .setPolarity(Polarity.HIGHER_IS_BETTER)
                        .setSource(Source.PERFETTO_V2)
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-1")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "dimension-value-2-1")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-2")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(1234567890)))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-3")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(123.0)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(260051.0)))
                                        .setLegacyMetricKey(
                                                "metric-name-2-dimension-value-2-1-1234567890-123.0"))
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-1")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "dimension-value-2-1")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-2")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(12907)))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-3")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(456.0)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(158854.0)))
                                        .setLegacyMetricKey(
                                                "metric-name-2-dimension-value-2-1-12907-456.0"))
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-1")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "dimension-value-2-3")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-2")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(999)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(777.0)))
                                        .setLegacyMetricKey(
                                                "metric-name-2-dimension-value-2-3-999"))
                        .build();

        // --- metric-name-3 ---
        MetricRecord metric3Record =
                MetricRecord.newBuilder()
                        .setMetricId("metric-name-3")
                        .setHumanReadableName("metric-name-3")
                        .setUnit(Unit.COUNT)
                        .setPolarity(Polarity.NOT_APPLICABLE)
                        .setSource(Source.PERFETTO_V2)
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-1")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "dimension-value-2-1")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-2")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(12907)))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-3")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(456.0)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(345.0)))
                                        .setLegacyMetricKey(
                                                "metric-name-3-dimension-value-2-1-12907-456.0"))
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-1")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "dimension-value-2-2")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-2")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(1234567890)))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-3")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(234.0)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(321.0)))
                                        .setLegacyMetricKey(
                                                "metric-name-3-dimension-value-2-2-1234567890-234.0"))
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-1")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setStringValue(
                                                                                "dimension-value-2-3")))
                                        .addDimensions(
                                                Dimension.newBuilder()
                                                        .setKey("dimension-key-2-2")
                                                        .setValue(
                                                                Value.newBuilder()
                                                                        .setIntValue(999)))
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(888.0)))
                                        .setLegacyMetricKey(
                                                "metric-name-3-dimension-value-2-3-999"))
                        .build();

        // --- metric-name-4 ---
        MetricRecord noDimsRecord =
                MetricRecord.newBuilder()
                        .setMetricId("metric-name-4")
                        .setHumanReadableName("metric-name-4")
                        .setUnit(Unit.COUNT)
                        .setSource(Source.PERFETTO_V2)
                        .addDimensionalMetrics(
                                DimensionalMetric.newBuilder()
                                        .setRawValues(
                                                RawValues.newBuilder()
                                                        .addValues(
                                                                Value.newBuilder()
                                                                        .setDoubleValue(1.0)))
                                        .setLegacyMetricKey("metric-name-4"))
                        .build();

        TestMetricResult testResult =
                TestMetricResult.newBuilder()
                        .setIdentifier(TestIdentifier.getDefaultInstance())
                        .addMetricRecords(metric1Record)
                        .addMetricRecords(metric2Record)
                        .addMetricRecords(metric3Record)
                        .addMetricRecords(noDimsRecord)
                        .build();

        return MetricReport.newBuilder().addTestMetricResults(testResult).build();
    }

    @Test
    public void testConvert_allMetricsIncluded() {
        TraceSummary summary = createTraceSummaryWithTwoMetrics();
        MetricReport report = mConverter.convert(summary, null);

        assertNotNull(report);
        assertEquals(1, report.getTestMetricResultsCount());
        TestMetricResult result = report.getTestMetricResults(0);
        assertEquals(2, result.getMetricRecordsCount());
        assertEquals("metric1", result.getMetricRecords(0).getMetricId());
        assertEquals("metric2", result.getMetricRecords(1).getMetricId());
    }

    private TraceSummary createTraceSummaryWithTwoMetrics() {
        TraceMetricV2Spec spec1 =
                TraceMetricV2Spec.newBuilder().setId("metric1").addDimensions("dim1").build();
        Row row1 =
                Row.newBuilder()
                        .addDimension(Row.Dimension.newBuilder().setStringValue("val1").build())
                        .addValues(Row.Value.newBuilder().setDoubleValue(100.0).build())
                        .build();
        TraceMetricV2Bundle bundle1 =
                TraceMetricV2Bundle.newBuilder().addSpecs(spec1).addRow(row1).build();

        TraceMetricV2Spec spec2 =
                TraceMetricV2Spec.newBuilder().setId("metric2").addDimensions("dim1").build();
        Row row2 =
                Row.newBuilder()
                        .addDimension(Row.Dimension.newBuilder().setStringValue("val2").build())
                        .addValues(Row.Value.newBuilder().setDoubleValue(200.0).build())
                        .build();
        TraceMetricV2Bundle bundle2 =
                TraceMetricV2Bundle.newBuilder().addSpecs(spec2).addRow(row2).build();

        return TraceSummary.newBuilder()
                .addMetricBundles(bundle1)
                .addMetricBundles(bundle2)
                .build();
    }

    @Test
    public void testConvert_withBooleanDimension() {
        TraceMetricV2Spec spec =
                TraceMetricV2Spec.newBuilder()
                        .setId("metric-with-bool")
                        .addDimensions("bool_dim")
                        .build();
        Row row =
                Row.newBuilder()
                        .addDimension(Row.Dimension.newBuilder().setBoolValue(true).build())
                        .addValues(Row.Value.newBuilder().setDoubleValue(1.0).build())
                        .build();
        TraceMetricV2Bundle bundle =
                TraceMetricV2Bundle.newBuilder().addSpecs(spec).addRow(row).build();
        TraceSummary summary = TraceSummary.newBuilder().addMetricBundles(bundle).build();

        MetricReport report = mConverter.convert(summary, null);

        DimensionalMetric expectedDimMetric =
                DimensionalMetric.newBuilder()
                        .addDimensions(
                                Dimension.newBuilder()
                                        .setKey("bool_dim")
                                        .setValue(Value.newBuilder().setBoolValue(true)))
                        .setRawValues(
                                RawValues.newBuilder()
                                        .addValues(Value.newBuilder().setDoubleValue(1.0)))
                        .setLegacyMetricKey("metric-with-bool-true")
                        .build();
        MetricRecord expectedRecord =
                MetricRecord.newBuilder()
                        .setMetricId("metric-with-bool")
                        .setHumanReadableName("metric-with-bool")
                        .setSource(Source.PERFETTO_V2)
                        .addDimensionalMetrics(expectedDimMetric)
                        .build();
        TestMetricResult expectedResult =
                TestMetricResult.newBuilder()
                        .setIdentifier(TestIdentifier.getDefaultInstance())
                        .addMetricRecords(expectedRecord)
                        .build();
        MetricReport expectedReport =
                MetricReport.newBuilder().addTestMetricResults(expectedResult).build();

        assertEquals(expectedReport, report);
    }

    @Test
    public void testConvert_withTestDescription() {
        TraceSummary summary = createComplexTraceSummary();
        TestDescription testDescription =
                new TestDescription("com.example.TestClass", "testMethod$1");

        MetricReport report = mConverter.convert(summary, testDescription);

        assertNotNull(report);
        assertEquals(1, report.getTestMetricResultsCount());
        TestMetricResult result = report.getTestMetricResults(0);

        assertEquals(1, result.getIteration());
        TestIdentifier identifier = result.getIdentifier();
        assertEquals("com.example.TestClass", identifier.getTestClass());
        assertEquals("testMethod", identifier.getMethod());
        assertEquals(testDescription.toString(), identifier.getFullTestName());
    }

    @Test
    public void testConvert_withTestDescription_iterationInClass() {
        TraceSummary summary = createComplexTraceSummary();
        TestDescription testDescription =
                new TestDescription("com.example.TestClass$2", "testMethod");

        MetricReport report = mConverter.convert(summary, testDescription);

        assertNotNull(report);
        assertEquals(1, report.getTestMetricResultsCount());
        TestMetricResult result = report.getTestMetricResults(0);

        assertEquals(2, result.getIteration());
        TestIdentifier identifier = result.getIdentifier();
        assertEquals("com.example.TestClass", identifier.getTestClass());
        assertEquals("testMethod", identifier.getMethod());
    }

    @Test
    public void testConvert_withTestDescription_noIteration() {
        TraceSummary summary = createComplexTraceSummary();
        TestDescription testDescription =
                new TestDescription("com.example.TestClass", "testMethod");

        MetricReport report = mConverter.convert(summary, testDescription);

        assertNotNull(report);
        assertEquals(1, report.getTestMetricResultsCount());
        TestMetricResult result = report.getTestMetricResults(0);

        // Default value for iteration is 0 if not present.
        assertEquals(0, result.getIteration());
        TestIdentifier identifier = result.getIdentifier();
        assertEquals("com.example.TestClass", identifier.getTestClass());
        assertEquals("testMethod", identifier.getMethod());
    }

    @Test
    public void testConvert_withTestDescription_iterationInBoth() {
        TraceSummary summary = createComplexTraceSummary();
        TestDescription testDescription =
                new TestDescription("com.example.TestClass$2", "testMethod$1");

        MetricReport report = mConverter.convert(summary, testDescription);

        assertNotNull(report);
        assertEquals(1, report.getTestMetricResultsCount());
        TestMetricResult result = report.getTestMetricResults(0);

        assertEquals(1, result.getIteration());
        TestIdentifier identifier = result.getIdentifier();
        // Class name is not modified if iteration is taken from method name.
        assertEquals("com.example.TestClass$2", identifier.getTestClass());
        assertEquals("testMethod", identifier.getMethod());
    }

    @Test
    public void testConvert_emptyTraceSummary() {
        TraceSummary summary = TraceSummary.getDefaultInstance();
        MetricReport report = mConverter.convert(summary, null);
        assertNull(report);
    }

    @Test
    public void testConvert_traceSummaryWithEmptyBundle() {
        TraceSummary summary =
                TraceSummary.newBuilder()
                        .addMetricBundles(TraceMetricV2Bundle.getDefaultInstance())
                        .build();
        MetricReport report = mConverter.convert(summary, null);
        assertNull(report);
    }

    @Test
    public void testConvert_traceSummaryWithNoValues() {
        TraceMetricV2Spec spec1 =
                TraceMetricV2Spec.newBuilder().setId("metric1").addDimensions("dim1").build();
        Row row1 =
                Row.newBuilder()
                        .addDimension(Row.Dimension.newBuilder().setStringValue("val1").build())
                        .build(); // No values
        TraceMetricV2Bundle bundle1 =
                TraceMetricV2Bundle.newBuilder().addSpecs(spec1).addRow(row1).build();

        TraceSummary summary = TraceSummary.newBuilder().addMetricBundles(bundle1).build();

        MetricReport report = mConverter.convert(summary, null);
        assertNull(report);
    }

    @Test
    public void testConvert_specMissingId() {
        // Spec is missing required 'id' field.
        TraceMetricV2Spec spec = TraceMetricV2Spec.newBuilder().setValue("value_col").build();
        Row row =
                Row.newBuilder()
                        .addValues(Row.Value.newBuilder().setDoubleValue(1.0).build())
                        .build();
        TraceMetricV2Bundle bundle =
                TraceMetricV2Bundle.newBuilder().addSpecs(spec).addRow(row).build();
        TraceSummary summary = TraceSummary.newBuilder().addMetricBundles(bundle).build();

        // The converter should skip the invalid spec and return no metrics.
        MetricReport report = mConverter.convert(summary, null);
        assertNull(report);
    }

    @Test
    public void testConvert_specMissingValue() {
        // Spec is missing required 'value' field.
        TraceMetricV2Spec spec = TraceMetricV2Spec.newBuilder().setId("metric-id").build();
        Row row =
                Row.newBuilder()
                        .addValues(Row.Value.newBuilder().setDoubleValue(1.0).build())
                        .build();
        TraceMetricV2Bundle bundle =
                TraceMetricV2Bundle.newBuilder().addSpecs(spec).addRow(row).build();
        TraceSummary summary = TraceSummary.newBuilder().addMetricBundles(bundle).build();

        // The converter should process the metric but with an empty aggregation string.
        MetricReport report = mConverter.convert(summary, null);

        assertNotNull(report);
        assertEquals(1, report.getTestMetricResults(0).getMetricRecordsCount());
        MetricRecord record = report.getTestMetricResults(0).getMetricRecords(0);
        assertEquals("metric-id", record.getMetricId());
    }

    @Test
    public void testConvert_specWithCustomUnit() {
        // Spec uses a custom string unit.
        TraceMetricV2Spec spec =
                TraceMetricV2Spec.newBuilder()
                        .setId("custom-unit-metric")
                        .setValue("value")
                        .setCustomUnit("custom_widgets")
                        .build();
        Row row =
                Row.newBuilder()
                        .addValues(Row.Value.newBuilder().setDoubleValue(1.0).build())
                        .build();
        TraceMetricV2Bundle bundle =
                TraceMetricV2Bundle.newBuilder().addSpecs(spec).addRow(row).build();
        TraceSummary summary = TraceSummary.newBuilder().addMetricBundles(bundle).build();

        MetricReport report = mConverter.convert(summary, null);

        assertNotNull(report);
        assertEquals(1, report.getTestMetricResults(0).getMetricRecordsCount());
        MetricRecord record = report.getTestMetricResults(0).getMetricRecords(0);
        // Custom units should be ignored and result in UNIT_UNSPECIFIED.
        assertEquals(Unit.UNIT_UNSPECIFIED, record.getUnit());
    }

    @Test
    public void testConvert_dataIntegrityMismatches() {
        TraceMetricV2Spec spec1 =
                TraceMetricV2Spec.newBuilder().setId("metric1").addDimensions("dim1").build();
        TraceMetricV2Spec spec2 =
                TraceMetricV2Spec.newBuilder().setId("metric2").addDimensions("dim1").build();

        // Row with FEWER values than specs
        Row rowFewerValues =
                Row.newBuilder()
                        .addDimension(Row.Dimension.newBuilder().setStringValue("val1"))
                        // Only one value for two specs
                        .addValues(Row.Value.newBuilder().setDoubleValue(100.0))
                        .build();

        // Row with MORE values than specs
        Row rowMoreValues =
                Row.newBuilder()
                        .addDimension(Row.Dimension.newBuilder().setStringValue("val2"))
                        // Three values for two specs, third value should be ignored
                        .addValues(Row.Value.newBuilder().setDoubleValue(200.0))
                        .addValues(Row.Value.newBuilder().setDoubleValue(300.0))
                        .addValues(Row.Value.newBuilder().setDoubleValue(999.0))
                        .build();

        TraceMetricV2Bundle bundle =
                TraceMetricV2Bundle.newBuilder()
                        .addSpecs(spec1)
                        .addSpecs(spec2)
                        .addRow(rowFewerValues)
                        .addRow(rowMoreValues)
                        .build();
        TraceSummary summary = TraceSummary.newBuilder().addMetricBundles(bundle).build();

        MetricReport report = mConverter.convert(summary, null);

        assertNotNull(report);
        assertEquals(1, report.getTestMetricResultsCount());
        TestMetricResult result = report.getTestMetricResults(0);
        // Should have a record for metric1 and metric2
        assertEquals(2, result.getMetricRecordsCount());

        MetricRecord metric1Record = result.getMetricRecords(0);
        assertEquals("metric1", metric1Record.getMetricId());
        assertEquals(2, metric1Record.getDimensionalMetricsCount()); // From both rows

        DimensionalMetric dm1_row1 = metric1Record.getDimensionalMetrics(0);
        assertEquals("val1", dm1_row1.getDimensions(0).getValue().getStringValue());
        assertEquals(1, dm1_row1.getRawValues().getValuesCount());
        assertEquals(100.0, dm1_row1.getRawValues().getValues(0).getDoubleValue(), 0.0);

        DimensionalMetric dm1_row2 = metric1Record.getDimensionalMetrics(1);
        assertEquals("val2", dm1_row2.getDimensions(0).getValue().getStringValue());
        assertEquals(1, dm1_row2.getRawValues().getValuesCount());
        assertEquals(200.0, dm1_row2.getRawValues().getValues(0).getDoubleValue(), 0.0);

        MetricRecord metric2Record = result.getMetricRecords(1);
        assertEquals("metric2", metric2Record.getMetricId());
        // Only one dimensional metric from rowMoreValues, as rowFewerValues had no value for spec2
        assertEquals(1, metric2Record.getDimensionalMetricsCount());

        DimensionalMetric dm2_row2 = metric2Record.getDimensionalMetrics(0);
        assertEquals("val2", dm2_row2.getDimensions(0).getValue().getStringValue());
        assertEquals(1, dm2_row2.getRawValues().getValuesCount());
        assertEquals(300.0, dm2_row2.getRawValues().getValues(0).getDoubleValue(), 0.0);
    }

    @Test
    public void testConvert_withDimensionsSpecs() {
        TraceMetricV2Spec.DimensionSpec dimSpec1 =
                TraceMetricV2Spec.DimensionSpec.newBuilder()
                        .setName("string_dim")
                        .setType(TraceMetricV2Spec.DimensionType.STRING)
                        .build();
        TraceMetricV2Spec.DimensionSpec dimSpec2 =
                TraceMetricV2Spec.DimensionSpec.newBuilder()
                        .setName("int_dim")
                        .setType(TraceMetricV2Spec.DimensionType.INT64)
                        .build();

        TraceMetricV2Spec spec =
                TraceMetricV2Spec.newBuilder()
                        .setId("metric-with-dim-specs")
                        .addDimensionsSpecs(dimSpec1)
                        .addDimensionsSpecs(dimSpec2)
                        .build();

        Row row =
                Row.newBuilder()
                        .addDimension(Row.Dimension.newBuilder().setStringValue("text_value"))
                        .addDimension(Row.Dimension.newBuilder().setInt64Value(98765))
                        .addValues(Row.Value.newBuilder().setDoubleValue(1.0))
                        .build();
        TraceMetricV2Bundle bundle =
                TraceMetricV2Bundle.newBuilder().addSpecs(spec).addRow(row).build();
        TraceSummary summary = TraceSummary.newBuilder().addMetricBundles(bundle).build();

        MetricReport report = mConverter.convert(summary, null);

        assertNotNull(report);
        MetricRecord record = report.getTestMetricResults(0).getMetricRecords(0);
        assertEquals("metric-with-dim-specs", record.getMetricId());
        DimensionalMetric dimMetric = record.getDimensionalMetrics(0);

        assertEquals(2, dimMetric.getDimensionsCount());
        Dimension actualDim1 = dimMetric.getDimensions(0);
        assertEquals("int_dim", actualDim1.getKey());
        assertEquals(98765, actualDim1.getValue().getIntValue());

        Dimension actualDim2 = dimMetric.getDimensions(1);
        assertEquals("string_dim", actualDim2.getKey());
        assertEquals("text_value", actualDim2.getValue().getStringValue());
    }
}
