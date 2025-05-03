/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.tradefed.result.teststorage;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.metrics.proto.MetricMeasurement.Measurements;
import com.android.tradefed.metrics.proto.MetricMeasurement.Measurements.MeasurementCase;

import com.google.api.services.androidbuildinternal.v3.model.Metric;
import com.google.api.services.androidbuildinternal.v3.model.MetricGroup;
import com.google.api.services.androidbuildinternal.v3.model.Metrics;
import com.google.api.services.androidbuildinternal.v3.model.Statistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Class to hold helper methods for dealing with Metric format conversion. */
public final class MetricUtils {
    /**
     * Converts a HashMap of metric key to Tradefed Metric mappings to an Android Build API Metrics
     * model.
     *
     * @param tfMetrics a HashMap of metric key to Tradefed Metric
     * @return the Metrics model
     */
    public static Metrics toMetrics(HashMap<String, MetricMeasurement.Metric> tfMetrics) {
        HashMap<String, Double> doubleMetrics = convertValueToDouble(tfMetrics);
        List<MetricGroup> metricGroups = new ArrayList<MetricGroup>();
        for (String metricKey : doubleMetrics.keySet()) {
            double metricValue = doubleMetrics.get(metricKey);
            Metric metric =
                    new Metric()
                            .setMetadataId(0L)
                            .setStatistics(
                                    new Statistics()
                                            .setCount(1L)
                                            .setMean(metricValue)
                                            .setStandardDeviation(0D)
                                            .setMinimum(metricValue)
                                            .setMedian(metricValue)
                                            .setMaximum(metricValue));
            MetricGroup metricGroup =
                    new MetricGroup().setName(metricKey).setMetrics(Arrays.asList(metric));
            metricGroups.add(metricGroup);
        }

        Metrics metrics = new Metrics().setMetricGroups(metricGroups);
        return metrics;
    }

    /**
     * Conversion of Map<String, MetricMeasurement.Metric> to Map<String, Double>. Single values of
     * int and double are kept, lists of values are not converted and will be lost.
     *
     * @param tfMetrics a HashMap of metric key to Tradefed Metric
     * @return a HashMap of metric key to double value
     */
    public static HashMap<String, Double> convertValueToDouble(
            HashMap<String, MetricMeasurement.Metric> tfMetrics) {
        HashMap<String, Double> metrics = new HashMap<>();
        List<String> metricsNotConverted = new ArrayList<>();
        List<String> metricsWithoutMeasurement = new ArrayList<>();
        for (String key : tfMetrics.keySet()) {
            Measurements measures = tfMetrics.get(key).getMeasurements();
            MeasurementCase set = measures.getMeasurementCase();
            double value;
            switch (set) {
                case SINGLE_DOUBLE:
                    value = measures.getSingleDouble();
                    break;
                case SINGLE_INT:
                    value = measures.getSingleInt();
                    break;
                case SINGLE_STRING:
                    try {
                        value = Double.parseDouble(measures.getSingleString());
                        break;
                    } catch (NumberFormatException e) {
                        metricsNotConverted.add(
                                String.format("metric key: %s, type: %s", key, set));
                        continue;
                    }
                case MEASUREMENT_NOT_SET:
                    metricsWithoutMeasurement.add(key);
                    continue;
                default:
                    metricsNotConverted.add(String.format("metric key: %s, type: %s", key, set));
                    continue;
            }
            metrics.put(key, value);
        }
        if (!metricsNotConverted.isEmpty()) {
            CLog.d(
                    String.format(
                            "Could not convert complex types to double. %s.",
                            String.join("; ", metricsNotConverted)));
        }
        if (!metricsWithoutMeasurement.isEmpty()) {
            CLog.d(
                    String.format(
                            "No measurements was set for metric keys: %s.",
                            String.join(", ", metricsWithoutMeasurement)));
        }
        return metrics;
    }
}
