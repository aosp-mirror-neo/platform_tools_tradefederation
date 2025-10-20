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

package com.google.android.tradefed.result;

import static com.google.android.tradefed.result.AndroidBuildResultReporter.BUILD_ATTEMPT_ID;
import static com.google.android.tradefed.result.AndroidBuildResultReporter.DEFAULT_BUILD_ATTEMPT_ID;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.util.MultiMap;

import com.google.android.tradefed.build.RemoteBuildInfo;
import com.google.common.annotations.VisibleForTesting;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.ClangConfiguration;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.Component;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.Configuration;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.CoverageMeasurement;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.CoverageMetadata;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.GcovConfiguration;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.GcovKernelConfiguration;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.JackConfiguration;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.JacocoConfiguration;
import com.google.wireless.android.testtools.coverage.aggregator.CoverageMetadataProto.TestArtifact;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link ILogSaverListener} which collects logged coverage measurements and generates a
 * CoverageMetadataProto for processing by the Android Code Coverage service.
 */
public final class CoverageMetadataCollector implements ILogSaverListener, IConfigurationReceiver {

    @Option(
            name = "coverage",
            description =
                    "Collect code coverage for this test run. Note that the build under test must"
                            + "be a coverage build or else this will fail.")
    private boolean mCoverage = false;

    @Option(
            name = "coverage-stay-enabled",
            description = "Whether or not to keep coverage enabled if it fails to collect once.")
    private boolean mStayEnabled = false;

    @Option(
            name = "coverage-toolchain",
            description = "The coverage toolchains that were used to compile the coverage build.")
    private List<Toolchain> mToolchains = new ArrayList<>();

    @Option(
            name = "collect-on-log-association",
            description = "Enable collecting coverage on logAssociation event.")
    private boolean mEnableLogAssociationCollection = false;

    @VisibleForTesting
    enum Toolchain {
        CLANG,
        GCOV,
        GCOV_KERNEL,
        JACK,
        JACOCO;
    }

    @Option(
            name = "coverage-filter",
            description = "Sets the jack filters for tests that use a shared xml configuration.")
    private MultiMap<String, String> mJackFilters = new MultiMap<>();

    @Option(
            name = "jacoco-includes",
            description = "Sets the jacoco includes for tests that use a shared xml configuration.")
    private MultiMap<String, String> mJacocoIncludes = new MultiMap<>();

    @Option(
            name = "jacoco-excludes",
            description = "Sets the jacoco excludes for tests that use a shared xml configuration.")
    private MultiMap<String, String> mJacocoExcludes = new MultiMap<>();

    @Option(
            name = "gcov-includes",
            description = "Sets the gcov includes for tests that use a shared xml configuration.")
    private MultiMap<String, String> mGcovIncludes = new MultiMap<>();

    @Option(
            name = "gcov-excludes",
            description = "Sets the gcov excludes for tests that use a shared xml configuration.")
    private MultiMap<String, String> mGcovExcludes = new MultiMap<>();

    @Option(
            name = "clang-binary-includes",
            description =
                    "Sets the binaries that should contribute to coverage for tests that use a"
                            + " shared xml configuration.")
    private MultiMap<String, String> mClangBinaryIncludes = new MultiMap<>();

    @Option(
            name = "clang-binary-excludes",
            description =
                    "Sets the binaries that should be excluded from coverage for tests that use a"
                            + " shared xml configuration.")
    private MultiMap<String, String> mClangBinaryExcludes = new MultiMap<>();

    @Option(
            name = "clang-includes",
            description =
                    "Sets the source file paths that should be included in the coverage report.")
    private MultiMap<String, String> mClangIncludes = new MultiMap<>();

    @Option(
            name = "clang-excludes",
            description =
                    "Sets the source file paths that should be excluded from the coverage report.")
    private MultiMap<String, String> mClangExcludes = new MultiMap<>();

    @Option(
            name = "metadata-zip",
            description =
                    "Sets the metadata zip file containing the Java class coverage data. Only set "
                            + "from the command line.")
    private String mMetadataZip = "jacoco-report-classes-all.jar";

    @Option(
            name = "native-metadata-zip",
            description =
                    "Sets the metadata zip file containing native coverage data. Only set from the "
                            + "command line.")
    private String mNativeMetadataZip = "gcov-report-files-all.zip";

    @Option(
            name = "clang-metadata-zip",
            description =
                    "Sets the metadata zip containing the unstripped Clang binaries or coverage"
                            + " mapping files. Only set from the command line.")
    private String mClangMetadataZip = "";

    @Option(
            name = "gcov-kernel-metadata-zip",
            description =
                    "Sets the metadata zip containing the *.gcno and gcno mapping files."
                            + " Only set from the command line.")
    private String mGcovKernelMetadataZip = "";

    @Option(
            name = "gcov-kernel-component-name",
            description = "Sets the component name to use for the gcov kernel configuration.")
    private List<String> mGcovKernelComponentName = new ArrayList<>();

    // Configuration Metadata Tags
    static final String METADATA_COMPONENT = "component";
    static final String METADATA_COVERAGE_FILTER = "coverage-filter:%s";

    static final String JACOCO_INCLUDE_FILTER = "jacoco-include-filter:%s";
    static final String JACOCO_EXCLUDE_FILTER = "jacoco-exclude-filter:%s";

    static final String GCOV_INCLUDE_FILTER = "gcov-include-filter:%s";
    static final String GCOV_EXCLUDE_FILTER = "gcov-exclude-filter:%s";

    static final String CLANG_BINARY_INCLUDE_FILTER = "clang-binary-include-filter:%s";
    static final String CLANG_BINARY_EXCLUDE_FILTER = "clang-binary-exclude-filter:%s";
    static final String CLANG_INCLUDE_FILTER = "clang-include-filter:%s";
    static final String CLANG_EXCLUDE_FILTER = "clang-exclude-filter:%s";

    private AndroidBuildApiLogSaver mLogSaver = null;

    // Protos to hold accumulated measurements
    private TestArtifact mTestArtifactTemplate;
    private CoverageMetadata.Builder mClangCoverageMetadata;
    private CoverageMetadata.Builder mGcovCoverageMetadata;
    private CoverageMetadata.Builder mGcovKernelCoverageMetadata;
    private CoverageMetadata.Builder mJackCoverageMetadata;
    private CoverageMetadata.Builder mJacocoCoverageMetadata;

    private IInvocationContext mMainContext;

    // Module Collection flag
    private IInvocationContext mModuleContext = null;

    private IConfiguration mConfiguration;

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    /** Sets the --coverage option for testing. */
    @VisibleForTesting
    void setCoverage(boolean coverage) {
        mCoverage = coverage;
    }

    /** Sets the --coverage-toolchain option for testing. */
    @VisibleForTesting
    void setToolchain(Toolchain toolchain) {
        mToolchains.clear();
        mToolchains.add(toolchain);
    }

    /** Sets multiple values for the --coverage-toolchain option for testing. */
    @VisibleForTesting
    void setToolchains(Collection<Toolchain> toolchains) {
        mToolchains.clear();
        mToolchains.addAll(toolchains);
    }

    /** Sets the --jack-filters option for testing. */
    @VisibleForTesting
    void setJackFilters(MultiMap<String, String> jackFilters) {
        mJackFilters = jackFilters;
    }

    /** Sets the --jacoco-includes option for testing. */
    @VisibleForTesting
    void setJacocoIncludes(MultiMap<String, String> jacocoIncludes) {
        mJacocoIncludes = jacocoIncludes;
    }

    /** Sets the --jacoco-excludes option for testing. */
    @VisibleForTesting
    void setJacocoExcludes(MultiMap<String, String> jacocoExcludes) {
        mJacocoExcludes = jacocoExcludes;
    }

    /** Sets the --gcov-includes option for testing. */
    @VisibleForTesting
    void setGcovIncludes(MultiMap<String, String> gcovIncludes) {
        mGcovIncludes = gcovIncludes;
    }

    /** Sets the --gcov-excludes option for testing. */
    @VisibleForTesting
    void setGcovExcludes(MultiMap<String, String> gcovExcludes) {
        mGcovExcludes = gcovExcludes;
    }

    /** Sets the --clang-binary-includes option for testing. */
    @VisibleForTesting
    void setClangBinaryIncludes(MultiMap<String, String> clangBinaryIncludes) {
        mClangBinaryIncludes = clangBinaryIncludes;
    }

    /** Sets the --clang-binary-excludes option for testing. */
    @VisibleForTesting
    void setClangBinaryExcludes(MultiMap<String, String> clangBinaryExcludes) {
        mClangBinaryExcludes = clangBinaryExcludes;
    }

    /** Sets the --clang-includes option for testing. */
    @VisibleForTesting
    void setClangIncludes(MultiMap<String, String> clangIncludes) {
        mClangIncludes = clangIncludes;
    }

    /** Sets the --clang-excludes option for testing. */
    @VisibleForTesting
    void setClangExcludes(MultiMap<String, String> clangExcludes) {
        mClangExcludes = clangExcludes;
    }

    /** Sets the --metadata-zip option for testing. */
    @VisibleForTesting
    void setMetadataZip(String metadataZip) {
        mMetadataZip = metadataZip;
    }

    /** Sets the --gcov-kernel-component-name option for testing. */
    @VisibleForTesting
    void setGcovKernelComponentName(List<String> components) {
        mGcovKernelComponentName = components;
    }

    @VisibleForTesting
    void setEnableLogAssociation(boolean logAssociation) {
        mEnableLogAssociationCollection = logAssociation;
    }

    private MultiMap<String, String> getMetadata(IInvocationContext context) {
        return context.getConfigurationDescriptor().getAllMetaData();
    }

    private MultiMap<String, String> getMetadataFromOptions() {
        MultiMap<String, String> metadata = new MultiMap<>();
        Set<String> components = new HashSet<>();

        // Add the Jack filters from the command line options.
        components.addAll(mJackFilters.keySet());
        for (String component : mJackFilters.keySet()) {
            String key = String.format(METADATA_COVERAGE_FILTER, component);
            mJackFilters.get(component).stream().forEach(filter -> metadata.put(key, filter));
        }

        // Add the Jacoco includes from the command line options.
        components.addAll(mJacocoIncludes.keySet());
        CLog.i("jacoco-includes are %s", mJacocoIncludes.keySet());
        for (String component : mJacocoIncludes.keySet()) {
            String key = String.format(JACOCO_INCLUDE_FILTER, component);
            mJacocoIncludes.get(component).stream().forEach(filter -> metadata.put(key, filter));
        }

        // Add the Jacoco excludes from the command line options.
        components.addAll(mJacocoExcludes.keySet());
        CLog.i("jacoco-excludes are %s", mJacocoExcludes.keySet());
        for (String component : mJacocoExcludes.keySet()) {
            String key = String.format(JACOCO_EXCLUDE_FILTER, component);
            mJacocoExcludes.get(component).stream().forEach(filter -> metadata.put(key, filter));
        }

        // Add the gcov includes from the command line options.
        components.addAll(mGcovIncludes.keySet());
        for (String component : mGcovIncludes.keySet()) {
            String key = String.format(GCOV_INCLUDE_FILTER, component);
            mGcovIncludes.get(component).stream().forEach(filter -> metadata.put(key, filter));
        }

        // Add the gcov excludes from the command line options.
        components.addAll(mGcovExcludes.keySet());
        for (String component : mGcovExcludes.keySet()) {
            String key = String.format(GCOV_EXCLUDE_FILTER, component);
            mGcovExcludes.get(component).stream().forEach(filter -> metadata.put(key, filter));
        }

        // Add the Clang binary includes from the command line options.
        components.addAll(mClangBinaryIncludes.keySet());
        for (String component : mClangBinaryIncludes.keySet()) {
            String key = String.format(CLANG_BINARY_INCLUDE_FILTER, component);
            mClangBinaryIncludes.get(component).stream()
                    .forEach(filter -> metadata.put(key, filter));
        }

        // Add the Clang binary excludes from the command line options.
        components.addAll(mClangBinaryExcludes.keySet());
        for (String component : mClangBinaryExcludes.keySet()) {
            String key = String.format(CLANG_BINARY_EXCLUDE_FILTER, component);
            mClangBinaryExcludes.get(component).stream()
                    .forEach(filter -> metadata.put(key, filter));
        }

        // Add the Clang includes from the command line options.
        components.addAll(mClangIncludes.keySet());
        for (String component : mClangIncludes.keySet()) {
            String key = String.format(CLANG_INCLUDE_FILTER, component);
            mClangIncludes.get(component).stream().forEach(filter -> metadata.put(key, filter));
        }

        // Add the Clang excludes from the command line options.
        components.addAll(mClangExcludes.keySet());
        for (String component : mClangExcludes.keySet()) {
            String key = String.format(CLANG_EXCLUDE_FILTER, component);
            mClangExcludes.get(component).stream().forEach(filter -> metadata.put(key, filter));
        }

        components.addAll(mGcovKernelComponentName);

        // Add the components.
        components.stream().forEach(component -> metadata.put(METADATA_COMPONENT, component));

        return metadata;
    }

    /** {@inheritDoc} */
    @Override
    public void setLogSaver(ILogSaver logSaver) {
        // No-op if coverage is disabled.
        if (!mCoverage) {
            return;
        }

        if (logSaver instanceof AndroidBuildApiLogSaver) {
            mLogSaver = (AndroidBuildApiLogSaver) logSaver;
        }
        if (mLogSaver == null) {
            mCoverage = mStayEnabled;
            throw new IllegalStateException("Only AndroidBuildApiLogSaver is supported");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void invocationStarted(IInvocationContext context) {
        // No-op if coverage is disabled.
        if (!mCoverage) {
            return;
        }

        mMainContext = context;
    }

    /** {@inheritDoc} */
    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        // No-op if coverage is disabled.
        if (!mCoverage) {
            return;
        }
        // Prevent testRunStart to resetup
        mModuleContext = moduleContext;
        setUpCollection(mModuleContext);
    }

    /** {@inheritDoc} */
    @Override
    public void testRunStarted(String runName, int testCount) {
        // No-op if coverage is disabled.
        if (!mCoverage) {
            return;
        }

        if (mModuleContext == null) {
            // Do the necessary setup for collection
            setUpCollection(mMainContext);
        }
    }

    @Override
    public void logAssociation(String unusedDataName, LogFile logFile) {
        // No-op if coverage is disabled.
        if (!mCoverage) {
            return;
        }
        if (!mEnableLogAssociationCollection) {
            return;
        }

        handleLog(unusedDataName, logFile.getType(), logFile, true);
    }

    /** {@inheritDoc} */
    @Override
    public void testLogSaved(
            String unusedDataName,
            LogDataType dataType,
            InputStreamSource unusedDataStream,
            LogFile logFile) {

        // No-op if coverage is disabled.
        if (!mCoverage) {
            return;
        }

        handleLog(unusedDataName, dataType, logFile, false);
    }

    private void handleLog(
            String unusedDataName, LogDataType dataType, LogFile logFile, boolean logAssociation) {
        // Accumulate coverage measurements.
        if (LogDataType.COVERAGE.equals(dataType)
                || LogDataType.NATIVE_COVERAGE.equals(dataType)
                || LogDataType.CLANG_COVERAGE.equals(dataType)
                || LogDataType.GCOV_KERNEL_COVERAGE.equals(dataType)) {
            String message = "Received a coverage log";
            if (logAssociation) {
                message += " association";
            }
            CLog.d("%s for %s", message, unusedDataName);
            String resourceId = null;
            if (logAssociation) {
                String rootDirName = mLogSaver.getLogRootPath().getName();
                int index = logFile.getPath().indexOf(rootDirName);
                resourceId = logFile.getPath().substring(index + rootDirName.length() + 1);
            } else {
                resourceId =
                        mLogSaver
                                .getLogRootPath()
                                .toPath()
                                .relativize(Paths.get(logFile.getPath()))
                                .toString();
            }
            TestArtifact.Builder artifact =
                    mTestArtifactTemplate.toBuilder().setResourceId(resourceId);

            if (LogDataType.GCOV_KERNEL_COVERAGE.equals(dataType)) {
                mGcovKernelCoverageMetadata.addMeasurements(
                        CoverageMeasurement.newBuilder().setTestArtifact(artifact));
            }

            if (LogDataType.CLANG_COVERAGE.equals(dataType)) {
                mClangCoverageMetadata.addMeasurements(
                        CoverageMeasurement.newBuilder().setTestArtifact(artifact));
            }

            if (LogDataType.NATIVE_COVERAGE.equals(dataType)) {
                mGcovCoverageMetadata.addMeasurements(
                        CoverageMeasurement.newBuilder().setTestArtifact(artifact));
            }
            if (LogDataType.COVERAGE.equals(dataType)) {
                mJackCoverageMetadata.addMeasurements(
                        CoverageMeasurement.newBuilder().setTestArtifact(artifact));
                mJacocoCoverageMetadata.addMeasurements(
                        CoverageMeasurement.newBuilder().setTestArtifact(artifact));
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void testRunEnded(long elapsedTime, HashMap<String, Metric> runMetrics) {
        // No-op if coverage is disabled.
        if (!mCoverage) {
            return;
        }
        if (mModuleContext == null) {
            finalizeCollection();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void testModuleEnded() {
        // No-op if coverage is disabled.
        if (!mCoverage) {
            return;
        }
        finalizeCollection();
        mModuleContext = null;
    }

    private void setUpCollection(IInvocationContext context) {
        // Use the module context first if we have one:
        MultiMap<String, String> metadata = getMetadata(context);
        MultiMap<String, String> commandLineMetadata = getMetadataFromOptions();
        if (metadata.isEmpty() && commandLineMetadata.isEmpty()) {
            mCoverage = mStayEnabled;
            throw new IllegalArgumentException(
                    "Metadata from the configuration and the command line cannot be both empty.");
        }

        metadata.putAll(commandLineMetadata);

        CLog.i("cmd line metadata is %s", commandLineMetadata.entries());
        CLog.i("metadata is %s", metadata.entries());
        if (mToolchains.isEmpty()) {
            mCoverage = mStayEnabled;
            throw new IllegalArgumentException(
                    "At least one coverage toolchain must be specified.");
        }

        // Initialize a TestArtifact template since most of the fields will be the same.
        IBuildInfo buildInfo = context.getBuildInfos().get(0);
        if (mToolchains.contains(Toolchain.GCOV_KERNEL)) {
            // TODO(joefradley): Confirm using this approach for platform targets
            mTestArtifactTemplate =
                    TestArtifact.newBuilder()
                            .setBuildType(getBuildType(mLogSaver.getTestResultBuildId()))
                            .setBuildId(
                                    checkNotNull(
                                            mLogSaver.getTestResultBuildId(),
                                            "Missing test result build id"))
                            .setTarget(
                                    checkNotNull(
                                            mLogSaver.getTestResultBuildTarget(),
                                            "Missing test result build target"))
                            .setAttemptId(getBuildAttempt(buildInfo))
                            .setTestResultId(mLogSaver.getTestResultId())
                            .build();
        } else {
            mTestArtifactTemplate =
                    TestArtifact.newBuilder()
                            .setBuildType(getBuildType(buildInfo.getBuildId()))
                            .setBuildId(checkNotNull(buildInfo.getBuildId(), "Missing build id"))
                            .setTarget(
                                    checkNotNull(
                                            buildInfo.getBuildFlavor(), "Missing build flavor"))
                            .setAttemptId(getBuildAttempt(buildInfo))
                            .setTestResultId(mLogSaver.getTestResultId())
                            .build();
        }

        // Clear any existing CoverageMetadata objects and initialize new ones.
        mClangCoverageMetadata = CoverageMetadata.newBuilder().setMetadataZip(mClangMetadataZip);
        mGcovKernelCoverageMetadata =
                CoverageMetadata.newBuilder().setMetadataZip(mGcovKernelMetadataZip);
        mGcovCoverageMetadata = CoverageMetadata.newBuilder().setMetadataZip(mNativeMetadataZip);
        mJackCoverageMetadata = CoverageMetadata.newBuilder().setMetadataZip(mMetadataZip);
        mJacocoCoverageMetadata = CoverageMetadata.newBuilder().setMetadataZip(mMetadataZip);

        // Add any components from the configuration.
        List<String> components =
                checkNotNull(
                        metadata.get(METADATA_COMPONENT), "At least one component must be set.");
        CLog.i("components are %s", components);
        for (String componentName : components) {
            if (mToolchains.contains(Toolchain.JACK)) {
                String filterKey = String.format(METADATA_COVERAGE_FILTER, componentName);

                if (metadata.containsKey(filterKey)) {
                    Component.Builder component =
                            mJackCoverageMetadata.addComponentsBuilder().setName(componentName);
                    Configuration.Builder reportConfiguration = component.getConfigurationBuilder();

                    JackConfiguration.Builder jack = reportConfiguration.getJackBuilder();
                    Optional.ofNullable(metadata.get(filterKey))
                            .ifPresent(jack::addAllMetadataFilters);
                }
            }
            if (mToolchains.contains(Toolchain.JACOCO)) {
                String includeKey = String.format(JACOCO_INCLUDE_FILTER, componentName);
                String excludeKey = String.format(JACOCO_EXCLUDE_FILTER, componentName);
                CLog.i("includeKey are %s", includeKey);
                CLog.i("excludeKey are %s", excludeKey);

                if (metadata.containsKey(includeKey)) {
                    Component.Builder component =
                            mJacocoCoverageMetadata.addComponentsBuilder().setName(componentName);
                    Configuration.Builder reportConfiguration = component.getConfigurationBuilder();

                    JacocoConfiguration.Builder jacoco = reportConfiguration.getJacocoBuilder();
                    Optional.ofNullable(metadata.get(includeKey))
                            .ifPresent(jacoco::addAllIncludeFilters);
                    Optional.ofNullable(metadata.get(excludeKey))
                            .ifPresent(jacoco::addAllExcludeFilters);
                }
            }
            if (mToolchains.contains(Toolchain.CLANG)) {
                String binaryIncludeKey = String.format(CLANG_BINARY_INCLUDE_FILTER, componentName);
                String binaryExcludeKey = String.format(CLANG_BINARY_EXCLUDE_FILTER, componentName);
                String includeKey = String.format(CLANG_INCLUDE_FILTER, componentName);
                String excludeKey = String.format(CLANG_EXCLUDE_FILTER, componentName);

                if (metadata.containsKey(includeKey)) {
                    Component.Builder component =
                            mClangCoverageMetadata.addComponentsBuilder().setName(componentName);
                    Configuration.Builder reportConfiguration = component.getConfigurationBuilder();

                    ClangConfiguration.Builder clang = reportConfiguration.getClangBuilder();
                    Optional.ofNullable(metadata.get(binaryIncludeKey))
                            .ifPresent(clang::addAllBinaryIncludeFilters);
                    Optional.ofNullable(metadata.get(binaryExcludeKey))
                            .ifPresent(clang::addAllBinaryExcludeFilters);
                    Optional.ofNullable(metadata.get(includeKey))
                            .ifPresent(clang::addAllIncludeFilters);
                    Optional.ofNullable(metadata.get(excludeKey))
                            .ifPresent(clang::addAllExcludeFilters);
                }
            }
            if (mToolchains.contains(Toolchain.GCOV_KERNEL)) {
                if (mGcovKernelComponentName.contains(componentName)) {
                    Configuration.Builder reportConfiguration =
                            mGcovKernelCoverageMetadata
                                    .addComponentsBuilder()
                                    .setName(componentName)
                                    .getConfigurationBuilder();

                    // Even though we don't use `gcovKernel` directly, we call
                    // `getGcovKernelBuilder` to force the typing of the configuration object.
                    GcovKernelConfiguration.Builder gcovKernel =
                            reportConfiguration.getGcovKernelBuilder();
                }
            }
            if (mToolchains.contains(Toolchain.GCOV)) {
                String includeKey = String.format(GCOV_INCLUDE_FILTER, componentName);
                String excludeKey = String.format(GCOV_EXCLUDE_FILTER, componentName);

                if (metadata.containsKey(includeKey)) {
                    Component.Builder component =
                            mGcovCoverageMetadata.addComponentsBuilder().setName(componentName);
                    Configuration.Builder reportConfiguration = component.getConfigurationBuilder();

                    GcovConfiguration.Builder gcov = reportConfiguration.getGcovBuilder();
                    Optional.ofNullable(metadata.get(includeKey))
                            .ifPresent(gcov::addAllIncludeFilters);
                    Optional.ofNullable(metadata.get(excludeKey))
                            .ifPresent(gcov::addAllExcludeFilters);
                }
            }
        }
    }

    private void finalizeCollection() {
        if (mToolchains.contains(Toolchain.CLANG)) {
            logCoverageMetadata(mClangCoverageMetadata, Toolchain.CLANG);
        }
        if (mToolchains.contains(Toolchain.GCOV_KERNEL)) {
            logCoverageMetadata(mGcovKernelCoverageMetadata, Toolchain.GCOV_KERNEL);
        }
        if (mToolchains.contains(Toolchain.GCOV)) {
            logCoverageMetadata(mGcovCoverageMetadata, Toolchain.GCOV);
        }
        if (mToolchains.contains(Toolchain.JACK)) {
            logCoverageMetadata(mJackCoverageMetadata, Toolchain.JACK);
        }
        if (mToolchains.contains(Toolchain.JACOCO)) {
            logCoverageMetadata(mJacocoCoverageMetadata, Toolchain.JACOCO);
        }
    }

    private void logCoverageMetadata(CoverageMetadata.Builder metadata, Toolchain toolchain) {
        // Make sure we got at least one measurement.
        if (metadata.getMeasurementsList().isEmpty()) {
            CLog.w("Did not receive any coverage measurements for %s.", toolchain.name());
            return;
        }

        // Save the completed CoverageMetadata proto.
        try (InputStreamSource source =
                new ByteArrayInputStreamSource(metadata.build().toByteArray())) {
            String name = "coverage_metadata_" + toolchain.name();
            LogFile loggedFile =
                    mLogSaver.saveLogDataRaw(name, LogDataType.PB, source.createInputStream());
            associateMetadata(name, loggedFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** This helps notifying all the other result reporters of the logged file. */
    private void associateMetadata(String name, LogFile loggedFile) {
        if (mConfiguration == null) {
            return;
        }
        for (ITestInvocationListener resultReporter : mConfiguration.getTestInvocationListeners()) {
            if (resultReporter == this) {
                continue;
            }
            if (resultReporter instanceof ILogSaverListener) {
                ((ILogSaverListener) resultReporter).logAssociation(name, loggedFile);
            }
        }
    }

    private <T> T checkNotNull(T reference, String errorMessage) {
        if (reference == null) {
            mCoverage = mStayEnabled;
            throw new NullPointerException(errorMessage);
        }
        return reference;
    }

    /** Returns the build type for the given build id. */
    @VisibleForTesting
    static String getBuildType(String buildId) {
        return RemoteBuildInfo.parseBuildType(buildId).toString();
    }

    /** Returns the test result id for the given {@link IInvocationContext}. */
    private static String getBuildAttempt(IBuildInfo buildInfo) {
        return buildInfo
                .getBuildAttributes()
                .getOrDefault(BUILD_ATTEMPT_ID, DEFAULT_BUILD_ATTEMPT_ID);
    }
}
