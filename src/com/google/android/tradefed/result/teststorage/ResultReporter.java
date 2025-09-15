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

import static com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper.BUILD_ENV_INVOCATION_CONTEXT_KEY;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.cluster.ClusterOptions;
import com.android.tradefed.cluster.IClusterOptions;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.IGlobalConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.config.proxy.TradefedDelegator;
import com.android.tradefed.invoker.DelegatedInvocationExecution;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.ITestSummaryListener;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestSummary;
import com.android.tradefed.result.resultdb.ResultDBReporter;
import com.android.tradefed.result.retry.ISupportGranularResults;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.testtype.suite.SuiteTestFilter;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.SystemUtil;
import com.android.tradefed.util.UniqueMultiMap;
import com.android.tradefed.util.VersionParser;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;

import com.google.android.tradefed.build.AbLocalBuildProvider;
import com.google.android.tradefed.result.AndroidBuildApiLogSaver;
import com.google.api.services.androidbuildinternal.v3.model.AconfigFlag;
import com.google.api.services.androidbuildinternal.v3.model.AconfigFlags;
import com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata;
import com.google.api.services.androidbuildinternal.v3.model.BuildDescriptor;
import com.google.api.services.androidbuildinternal.v3.model.DebugInfo;
import com.google.api.services.androidbuildinternal.v3.model.Invocation;
import com.google.api.services.androidbuildinternal.v3.model.Metrics;
import com.google.api.services.androidbuildinternal.v3.model.ModuleInfo;
import com.google.api.services.androidbuildinternal.v3.model.Property;
import com.google.api.services.androidbuildinternal.v3.model.SkippedReason;
import com.google.api.services.androidbuildinternal.v3.model.TestDefinition;
import com.google.api.services.androidbuildinternal.v3.model.TestIdentifier;
import com.google.api.services.androidbuildinternal.v3.model.TestResult;
import com.google.api.services.androidbuildinternal.v3.model.Timing;
import com.google.api.services.androidbuildinternal.v3.model.WorkUnit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@OptionClass(alias = "ants-result-reporter")
public class ResultReporter
        implements ITestSummaryListener,
                ILogSaverListener,
                ISupportGranularResults,
                IConfigurationReceiver {

    // Additional property names set for the work unit.
    private static final String ATTEMPT_PROPERTY = "attempt";
    private static final String TF_VERSION_PROPERTY = "tradefed_version";
    private static final String USER_PROPERTY = "user";
    private static final String HOSTNAME_PROPERTY = "hostname";
    private static final String BUILD_FLAVOR_PROPERTY = "build_flavor";
    private static final String CLUSTER_OPTIONS_HOST_LABEL_PROPERTY = "host_label";
    private static final String CLUSTER_OPTIONS_LAB_NAME_PROPERTY = "lab_name";
    private static final String CLUSTER_OPTIONS_CLUSTER_ID_PROPERTY = "cluster";

    /** Key of the command line args attributes */
    private static final String COMMAND_ARGS_KEY = "command_line_args";

    // Property name for the test mapping source
    private static final String TEST_MAPPING_PROPERTY = "test_mapping_source";

    // Property name for Sponge invocation id.
    private static final String SPONGE_INVOCATION_ID_PROPERTY = "sponge_invocation_id";

    // Property name for local instructions
    private static final String LOCAL_INSTRUCTION_PROPERTY = "local_repro_instruction";

    private static final String LOCAL_RUNNER = "local-test-runner";
    private static final String LOCAL_RUNNER_PROPERTY = "local_test_runner";

    // Property name for crystalball.
    private static final String CRYSTALBALL_INGEST_PROPERTY = "crystalball_ingest";
    private static final String CRYSTALBALL_HAS_DATA_PROPERTY = "crystalball_has_data";

    // Property name for mainline.
    private static final String MAINLINE_MODULE_PROPERTY = "module_installed";

    private static final String MAINLINE_VERSION_SUFFIX = "_version_used";
    private static final String MAINLINE_MODULE_PREFIX = "com.google.android";

    // Label to use for TF invocation work Unit.
    private static final String TF_INVOCATION_LABEL = "tf_invocation";

    // Short-link to redirect to the result page.
    // Full-link: https://android-build.googleplex.com/builds/tests/view?invocationId=
    private static final String RESULT_LINK = "http://ab/%s";

    // Settings to use when TF is creating a Test Storage invocation.
    public static final String DEFAULT_BUILDPROVIDER = "androidbuild";
    public static final String EXPERIMENTAL = "experimental";
    public static final String TRADEFED = "tradefed";
    public static final String SYSTEM_CHECKER = "system_checker";

    // Key that determines if we are using legacy V2 api or only v3 api for logs
    private static final String ARTIFACT_UPLOAD_KEY = "use_artifact_v3_api";

    private static final String TEST_RESULT_ID = "test_result_id";

    // Set containing the properties that should only be set once on the workunit
    private static final Set<String> UNIQUE_PROPERTIES =
            ImmutableSet.of(
                    COMMAND_ARGS_KEY,
                    TF_VERSION_PROPERTY,
                    USER_PROPERTY,
                    HOSTNAME_PROPERTY,
                    CLUSTER_OPTIONS_LAB_NAME_PROPERTY);

    // Module property if set will add modules in one batch.
    private static final String SPARSE_MODULE_PROPERTY = "sparse-module";
    // Set containing the allowed module property keys
    private static final Set<String> ALLOWED_MODULE_PROPERTIES =
            ImmutableSet.of(
                    ModuleDefinition.MODULE_ABI,
                    ModuleDefinition.MODULE_PARAMETERIZATION,
                    ModuleDefinition.MODULE_ISOLATED,
                    ModuleDefinition.MODULE_EXTERNAL_DEPENDENCIES,
                    ModuleDefinition.MODULE_CACHED,
                    ModuleDefinition.SPARSE_MODULE,
                    ModuleDefinition.MODULE_INVOCATION_ATTRIBUTE_FLAG_OVERRIDES_KEY);
    private static final Pattern ACONFIG_FLAG_PATTERN =
            Pattern.compile("(\\w+)/(.+\\..+)=(false|true)");
    private static final char FLAG_SEPARATOR = ' ';

    // Set containing the allowed test identifier module parameter keys
    private static final Set<String> ALLOWED_MODULE_PARAMETERS =
            ImmutableSet.of(ModuleDefinition.MODULE_ABI, ModuleDefinition.MODULE_PARAMETERIZATION);

    private static final ImmutableSet<String> METRIC_STRING_TO_PROPERTIES =
            ImmutableSet.of("run-isolated");
    private static final ImmutableSet<String> METRIC_INT_TO_PROPERTIES =
            ImmutableSet.of(
                    ModuleDefinition.PREPARATION_TIME,
                    ModuleDefinition.TEAR_DOWN_TIME,
                    ModuleDefinition.ISOLATION_COST,
                    ModuleDefinition.MODULE_TEST_COUNT);

    private static final ImmutableSet<String> TEST_PROPERTIES_MIGRATION =
            ImmutableSet.of("board", "model", "sku", "account_id");

    @Option(name = "disable", description = "Set to true if reporter is disabled")
    private boolean mDisable = false;

    @Option(name = "root-url", description = "Root url of the Test Storage service")
    private String mRootUrl = "https://www.googleapis.com/";

    @Option(name = "service-path", description = "Service path of the Test Storage service")
    private String mServicePath = "android/internal/build/v3";

    @Option(
            name = "create-local-invocation",
            description = "Create an invocation, if one is not provided")
    private boolean mCreateInvocation = SystemUtil.isLocalMode();

    @Option(
            name = "disable-associations",
            description = "Set to true to disable uploading test artifact associations")
    private boolean mDisableAssociations = false;

    @Option(name = "support-granular-results", description = "Enable support for granular results.")
    private boolean mSupportGranularResults = true;

    @Option(
            name = "enable-metrics",
            description = "Flag to control whether metrics are uploaded or not.")
    private boolean mEnableMetrics = false;

    @Option(
            name = "enable-sponge",
            description = "Flag to control whether upload results to Sponge.")
    private boolean mEnableSponge = true;

    // TODO: remove the flag once deployment is complete and verified.
    @Option(
            name = "enable-error-types",
            description = "Flag to enable setting error types for results and work units.")
    private boolean mEnableErrorTypes = true;

    @Option(
            name = "save-metric-file",
            description = "Flag to control whether to save the metric file or not.")
    private boolean mEnableMetricFile = false;

    @Option(
            name = "parallel-update-actions",
            description = "Feature flag to test parallelizing some update actions for speed.")
    private boolean mParallelOperations = true;

    @Option(
            name = "parallel-log-association-upload-pool",
            description =
                    "Feature flag to test parallelizing the log association upload for v3 api"
                            + " only. 0 meaning no parallel upload.")
    private int mParallelLogUploadPool = 10;

    @Option(
            name = "log-association-with-work-unit",
            description =
                    "Feature flag to experiment reporting associated "
                            + "logAssociation when the work unit is done.")
    private boolean mUploadLogAssociationWithWorkUnit = true;

    @Option(
            name = "metric-filter-regex",
            description =
                    "Regular expression that will be used for filtering the metrics written"
                            + " in to the test results file.")
    private Set<String> mMetricFilterRegEx = new HashSet<>();

    @Option(
            name = "enable-filter-property",
            description = "Feature flag to enable reading and using 'ignored' properties.")
    private boolean mEnableFilterProperty = true;

    @Option(
            name = "use-batch-insert",
            description = "Use BatchInsert api rather than http batch when uploading results")
    private boolean mUseBatchInsert = false;

    @Option(
            name = "max-batch-size",
            description = "Max number of test results sent in a single BatchInsert api call.")
    private int mMaxBatchSize = 200;

    // Default is 0, to let the server pick the best number.
    @Option(
            name = "insert-batch-size",
            description =
                    "How many results will be written to the database at once when using BatchInser"
                            + " api")
    private int mInsertBatchSize = 0;

    @Option(
            name = "enable-sparse-modules-batching",
            description = "Enable uploading of sparse modules in batches, rather than individually")
    private boolean mEnableModuleBatching = true;

    @Option(
            name = "enable-skip-method-reporting",
            description = "Enable the reporting of methods that are skipped.")
    private boolean mEnableSkipMethodReporting = true;

    @Option(
            name = "enable-skip-workunit-reporting",
            description = "Enable the reporting of workunits that are skipped.")
    private boolean mEnableSkipWorkUnitReporting = true;

    @Option(name = "enable-test-properties-migration")
    private boolean mEnablePropertyMigration = true;

    @Option(
            name = "enable-empty-module-reporting",
            description = "Enable the creation of a module workunit test run without a module.")
    private boolean mEnableEmptyModuleReporting = true;

    private TestResult mCurrentTestResult;
    private String mSummaryUrl;
    private DebugInfo mFailureInfo; // Contains invocation failure message.
    private SkipReason mInvocationSkipReason;

    private boolean mDelegatedMode = false;

    // Set to true if the result reporter created its own invocation
    // and will be updating it.
    // Set to false if invocation was created somewhere else and will not be updated.
    private boolean mManagingInvocation;
    private Invocation mInvocation;
    private String mWorkUnitId;

    // We keep work units in a stack, so that we can build them as a tree
    // when modules and test runs start. When a module or a test run starts, we
    // create a WorkUnit and push it on this stack. When test run or module ends
    // we pop units off the stack.
    // When uploading results and we want to associate result with the work unit,
    // the correct one is obtained by calling mWorkUnits.peek.
    private Deque<WorkUnit> mWorkUnits;

    // The module work unit which is used for test runs which are not associated with a module. This
    // ensures that every TF_TEST_RUN module has a TF_MODULE parent. This is only created when the
    // test run is created without a module and every test run after will use it. The empty module
    // will then be finalized when the invocation ends.
    private WorkUnit mEmptyModuleWorkUnit = null;

    // List that stores cached work units. Cached work units are for tests where result is known
    // ahead of time
    // from a previous run. These work units are expected to be module level, and should not contain
    // any child work units, artifacts or test results. Since no tests runs for these work units
    // it is much more efficient to collect them and upload them all at once in a batch.
    private List<WorkUnit> mCachedWorkUnits = new ArrayList<WorkUnit>();

    private String mCurrentModule = null;
    // All properties which should be reported to the work unit.
    private List<Property> mModuleProperties = Collections.emptyList();
    // Filtered module properties to use in the test identifier (module-abi & module-param)
    private List<Property> mModuleParameters = Collections.emptyList();

    private List<Property> mMigrationTestProperties = new ArrayList<Property>();

    // A map to keep track which test generated which log.
    private Multimap<String, LogAssociation> mLogAssociations =
            Multimaps.synchronizedMultimap(HashMultimap.create());

    // Queue to store log associations received before invocationStarted.
    private ConcurrentLinkedQueue<LogFile> mEarlyAssociations = new ConcurrentLinkedQueue<>();

    // Set to true if the invocationEnded is called.
    private boolean mInvocationEnded = false;

    // Legacy test result it for interacting with v2 log upload api. We need this id
    // to ensure compatibility with logs uploaded using the legacy v2 api.
    private long mLegacyResultId;

    // BuildInfo attributes are added to work unit properties so need to keep a reference to it.
    private IBuildInfo mBuildInfo;

    // Same for invocation context
    private IInvocationContext mContext;
    private IConfiguration mConfiguration;

    // Need module context for atest instructions
    private IInvocationContext mModuleContext;

    private ITestStorageClient mClient;

    private String mSpongeInvocationId;

    private MetricFileUtil mMetricFileUtil = new MetricFileUtil();

    private List<Thread> mUpdateActions = new ArrayList<>();

    protected ITestStorageClient createTestStorageClient(IInvocationContext context)
            throws GeneralSecurityException, IOException {
        String buildEnvironment = context.getAttribute(BUILD_ENV_INVOCATION_CONTEXT_KEY);
        if (buildEnvironment.isEmpty()) {
            buildEnvironment = "prod";
        }
        String artifactLegacyMigration = context.getAttribute(ARTIFACT_UPLOAD_KEY);
        return Client.create(
                buildEnvironment,
                !"false".equals(artifactLegacyMigration),
                true,
                mUseBatchInsert,
                mMaxBatchSize,
                mInsertBatchSize);
    }

    @Override
    public void setLogSaver(ILogSaver logSaver) {

        if (mEnableMetricFile) {
            mMetricFileUtil.setLogSaver(logSaver);
        }

        if (logSaver instanceof AndroidBuildApiLogSaver) {
            return;
        }

        CLog.w("Not using AndroidBuildApiLogSaver, log associations will be disabled.");
        mDisableAssociations = true;
    }

    @Override
    public void invocationStarted(IInvocationContext context) {
        if (mDisable) {
            return;
        }
        long invocationStart = System.currentTimeMillis();
        try (CloseableTraceScope start = new CloseableTraceScope("ants:invocationStart")) {
            mDelegatedMode =
                    mConfiguration.getConfigurationObject(TradefedDelegator.DELEGATE_OBJECT)
                            != null;
            mContext = context;
            mWorkUnits = new ArrayDeque<>();
            String invocationId = context.getAttribute("invocation_id");
            String workUnitId = context.getAttribute("work_unit_id");
            String testResultId = context.getAttribute(TEST_RESULT_ID);
            if (mEnablePropertyMigration) {
                for (String key : TEST_PROPERTIES_MIGRATION) {
                    String value = context.getAttribute(key);
                    if (!Strings.isNullOrEmpty(value)) {
                        mMigrationTestProperties.add(newProperty(key, value));
                    }
                }
            }
            try {
                mLegacyResultId = Long.parseLong(testResultId);
            } catch (NumberFormatException e) {
                CLog.w("Could not parse legacy test result id. Log associations will be disabled.");
                mDisableAssociations = true;
            }
            if (!context.getBuildInfos().isEmpty()) {
                mBuildInfo = context.getBuildInfos().get(0);
            }
            try {
                if (!invocationId.isEmpty()) {
                    // We received invocation and work unit from scheduler.
                    mClient = createTestStorageClient(context);
                    mInvocation = getInvocation(invocationId);
                    WorkUnit workUnit = null;
                    if (workUnitId.isEmpty()) {
                        workUnit =
                                newWorkUnit(
                                        mInvocation.getInvocationId(), WorkUnitType.TF_INVOCATION);
                        workUnit = mClient.createWorkUnit(workUnit);
                        mWorkUnitId = workUnit.getId();
                        CLog.d("Created WU %s", mWorkUnitId);
                    } else {
                        mWorkUnitId = workUnitId;
                        workUnit = newWorkUnit(mInvocation.getInvocationId()).setId(workUnitId);
                        if (mEnableSponge) {
                            try {
                                List<Property> properties =
                                        mClient.getWorkUnit(workUnitId).getProperties();
                                if (properties != null) {
                                    for (Property p : properties) {
                                        if (p.getName().equals(SPONGE_INVOCATION_ID_PROPERTY)) {
                                            mSpongeInvocationId = p.getValue();
                                            break;
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                CLog.d("Can't find WorkUnit: %s", workUnitId);
                            }
                        }
                    }
                    if (mEnableSponge && mSpongeInvocationId == null) {
                        // TFC will set sponge id property when test is
                        // scheduled by ATP.
                        mSpongeInvocationId = SpongeUtils.createInvocationId();
                        WorkUnit updated =
                                ObjectUpdater.create(mClient)
                                        .runUpdate(
                                                mWorkUnitId,
                                                new ObjectUpdater.WorkUnitUpdate() {
                                                    @Override
                                                    public WorkUnit doUpdate(WorkUnit current) {
                                                        addWorkUnitProperty(
                                                                current,
                                                                newProperty(
                                                                        SPONGE_INVOCATION_ID_PROPERTY,
                                                                        mSpongeInvocationId));
                                                        return current;
                                                    }
                                                });
                    }
                    if (mConfiguration.getCommandOptions().getShardCount() != null) {
                        workUnit.setShardCount(mConfiguration.getCommandOptions().getShardCount());
                        workUnit.setChildShardNumber(
                                mConfiguration.getCommandOptions().getShardIndex());
                    }
                    mWorkUnits.push(workUnit);
                } else if (invocationId.isEmpty() && workUnitId.isEmpty()) {
                    // we did not receive invocation, so we need to create one.
                    // check if we want to upload locally.
                    if (!mCreateInvocation) {
                        CLog.d(
                                "No invocation_id and work_unit_id was provided, skipping"
                                        + " reporting.");
                        mDisable = true;
                        return;
                    }
                    mClient = createTestStorageClient(context);
                    mInvocation = newInvocation(context);
                    mInvocation = mClient.createInvocation(mInvocation);
                    WorkUnit workUnit =
                            newWorkUnit(mInvocation.getInvocationId(), WorkUnitType.TF_INVOCATION);
                    if (mEnableSponge) {
                        mSpongeInvocationId = SpongeUtils.createInvocationId();
                        addWorkUnitProperty(
                                workUnit,
                                newProperty(SPONGE_INVOCATION_ID_PROPERTY, mSpongeInvocationId));
                    }
                    mWorkUnits.push(mClient.createWorkUnit(workUnit));
                    mManagingInvocation = true;
                    invocationId = mInvocation.getInvocationId();
                    mWorkUnitId = mWorkUnits.peek().getId();
                    context.addInvocationAttribute("invocation_id", invocationId);
                    context.addInvocationAttribute("work_unit_id", mWorkUnitId);
                    CLog.i("Created invocation %s and WU %s", invocationId, mWorkUnitId);
                } else {
                    mDisable = true;
                    throw new RuntimeException(
                            "You must either pass both invocation id and work unit id, or pass"
                                    + " none");
                }

                // Add early associations to the mLogAssociations for later upload.
                LogFile log;
                while ((log = mEarlyAssociations.poll()) != null) {
                    mLogAssociations.put(
                            mWorkUnitId,
                            new LogAssociation(
                                    mInvocation,
                                    mWorkUnitId,
                                    mCurrentTestResult,
                                    mLegacyResultId,
                                    log));
                }
            } catch (GeneralSecurityException | IOException e) {
                mDisable = true;
                if (mClient != null) {
                    // Make sure we cancel the client, otherwise it will leak a thread since
                    // invocationEnded will be skipped.
                    mClient.finalizeTestResults();
                }
                throw new RuntimeException(e);
            }
            if (mInvocation != null) {
                CLog.logAndDisplay(
                        LogLevel.INFO,
                        "Result link: %s",
                        String.format(RESULT_LINK, mInvocation.getInvocationId()));
                if (mEnableFilterProperty && mInvocation.getProperties() != null) {
                    List<Property> ignoredProperty =
                            mInvocation.getProperties().stream()
                                    .filter(p -> p.getName().equals("ignored"))
                                    .collect(Collectors.toList());
                    if (!ignoredProperty.isEmpty()) {
                        CLog.d("Read properties: %s", ignoredProperty);
                        for (Property p : ignoredProperty) {
                            if (Strings.isNullOrEmpty(p.getValue())) {
                                continue;
                            }
                            mConfiguration
                                    .getRetryDecision()
                                    .addToSkipRetryList(p.getValue().trim());
                        }
                    }
                    List<Property> runCountProperty =
                            mInvocation.getProperties().stream()
                                    .filter(p -> p.getName().equals("run_count"))
                                    .collect(Collectors.toList());
                    if (!runCountProperty.isEmpty()) {
                        context.addInvocationAttribute(
                                "run_count", runCountProperty.get(0).getValue());
                    }
                }
            }
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.ANTS_INVOCATION_START_TIME,
                    System.currentTimeMillis() - invocationStart);
        }
    }

    @Override
    public TestSummary getSummary() {
        if (mInvocation == null || mWorkUnitId == null) {
            return null;
        }
        return new TestSummary(
                String.format(
                        "https://android-build.googleplex.com/"
                                + "builds/tests/view?invocationId=%s&tab=artifacts&workunit_id=%s",
                        mInvocation.getInvocationId(), mWorkUnitId));
    }

    private void setInvocationError(String message, String trace) {
        // In general we can't expect TF to correctly set
        // error message in the invocation and WorkUnit because we are
        // not going to make it here in some crashes. If TF is started
        // by an external service, then that service should set the error message.

        // In cases where TF is run locally, we will set the error message
        // on the 'best effort' basis.
        mInvocation.setSchedulerState(SchedulerState.ERROR.toString());
        mInvocation.setSummary(message);
        mFailureInfo = new DebugInfo().setErrorMessage(message);
        if (!trace.isEmpty()) {
            mFailureInfo.setTrace(trace);
        }
    }

    private static String getStackTrace(Throwable cause) {
        StringWriter w = new StringWriter();
        cause.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    @Override
    public void invocationSkipped(SkipReason reason) {
        if (mDisable) {
            return;
        }
        if (!mEnableSkipWorkUnitReporting) {
            CLog.d("Not reporting %s. skip status reporting not enabled.", reason);
            return;
        }
        CLog.d("Received #invocationSkipped: %s", reason);
        mInvocation.setSchedulerState(SchedulerState.SKIPPED.toString());
        mInvocationSkipReason = reason;
    }

    @Override
    public void invocationFailed(Throwable cause) {
        if (mDisable) {
            return;
        }
        setInvocationError(cause.getMessage(), getStackTrace(cause));
    }

    @Override
    public void invocationFailed(FailureDescription failure) {
        if (mDisable) {
            return;
        }

        String errorMessage = "";
        String stackTrace = "";
        if (failure.getCause() != null) {
            errorMessage = failure.getCause().getMessage();
            stackTrace = getStackTrace(failure.getCause());
        }
        if (Strings.isNullOrEmpty(errorMessage)) {
            errorMessage = failure.getFormattedErrorMessage();
        }
        if (Strings.isNullOrEmpty(errorMessage)) {
            // Default to a standard message if error really doesn't have a message
            errorMessage = "invocation failure with no detailed message.";
        }
        setInvocationError(errorMessage, stackTrace);
        if (mEnableErrorTypes) {
            setErrorType(mFailureInfo, failure);
            String errorDebug = "";
            if (failure.getErrorIdentifier() != null) {
                mFailureInfo.setErrorCode(failure.getErrorIdentifier().code());
                errorDebug += String.format("code: %s", failure.getErrorIdentifier().code());
                mFailureInfo.setErrorName(failure.getErrorIdentifier().name());
                errorDebug += String.format(",name: %s", failure.getErrorIdentifier().name());
            }
            if (failure.getOrigin() != null) {
                mFailureInfo.setErrorOrigin(failure.getOrigin());
            }
            CLog.d("invocationFailed with '%s' and '%s'", errorMessage, errorDebug);
        }
    }

    @Override
    public void invocationEnded(long elapsedTime) {
        if (mDisable) {
            return;
        }
        mInvocationEnded = true;
        long invocationEnd = System.currentTimeMillis();
        try (CloseableTraceScope end = new CloseableTraceScope("ants:invocationEnded")) {
            if (mEnableSponge && mSpongeInvocationId != null) {
                CLog.i(
                        String.format(
                                "Stored invocation results in sponge Url: %s",
                                SpongeUtils.getInvocationUrl(mSpongeInvocationId)));
            }
            if (mEnableMetricFile) {
                try {
                    mMetricFileUtil.invocationEnded(mMetricFilterRegEx);
                } catch (RuntimeException e) {
                    // Avoid any impact from bug in MetricFileUtil
                    CLog.e(e);
                }
            }

            if (mEnableEmptyModuleReporting) {
                finalizeEmptyModuleWorkUnit();
            }
            mClient.finalizeTestResults();
            try (CloseableTraceScope update =
                    new CloseableTraceScope(
                            InvocationMetricKey.wait_for_results_update.toString())) {
                for (Thread t : mUpdateActions) {
                    if (t.isAlive()) {
                        t.join(20 * 60000L); // Wait 20 minutes max for final result upload
                    }
                }
            } catch (InterruptedException e) {
                CLog.e(e);
            }
            try {
                CLog.i("Uploading %d cached modules", mCachedWorkUnits.size());
                mClient.createWorkUnits(mCachedWorkUnits);
                uploadLogAssociations(new ArrayList<>(mLogAssociations.values()));
                finalizeTfWorkUnit();
                updateInvocation();
                CLog.logAndDisplay(
                        LogLevel.INFO,
                        "Result link: %s",
                        String.format(RESULT_LINK, mInvocation.getInvocationId()));
                if (mManagingInvocation) {
                    mInvocation = finalizeInvocation(mInvocation);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            CLog.d("Done with AnTs ResultReporter");
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.ANTS_INVOCATION_END_TIME,
                    System.currentTimeMillis() - invocationEnd);
        }
    }

    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        long startModule = System.currentTimeMillis();
        try (CloseableTraceScope ignored = new CloseableTraceScope("ants:testModuleStarted")) {
            mModuleContext = moduleContext;
            mCurrentModule = moduleContext.getConfigurationDescriptor().getModuleName();
            mModuleProperties = getModuleProperties(moduleContext);
            mModuleParameters = getModuleParameters(mModuleProperties);
            long currentTimeStamp = currentTimestamp();
            if (!Strings.isNullOrEmpty(moduleContext.getAttribute(ITestSuite.MODULE_START_TIME))) {
                try {
                    currentTimeStamp =
                            Long.parseLong(
                                    moduleContext.getAttribute(ITestSuite.MODULE_START_TIME));
                } catch (NumberFormatException nfe) {
                    CLog.e(nfe);
                }
            }

            ModuleInfo moduleInfo = new ModuleInfo().setName(mCurrentModule);
            if (!mModuleParameters.isEmpty()) {
                moduleInfo.setModuleParameters(mModuleParameters);
            }
            WorkUnit workUnit =
                    new WorkUnit()
                            .setName(mCurrentModule)
                            .setParentId(mWorkUnits.peek().getId())
                            .setInvocationId(mInvocation.getInvocationId())
                            .setTiming(new Timing().setCreationTimestamp(currentTimeStamp))
                            .setType(WorkUnitType.TF_MODULE.name())
                            .setState(SchedulerState.RUNNING.toString())
                            .setModuleInfo(moduleInfo);
            workUnit.setProperties(mModuleProperties);
            AconfigFlags flagOverrides =
                    addOrUpdateFlagOverrides(
                            null,
                            moduleContext.getAttribute(
                                    ModuleDefinition
                                            .MODULE_INVOCATION_ATTRIBUTE_FLAG_OVERRIDES_KEY));

            workUnit.setAconfigFlagOverrides(flagOverrides);
            if (mEnableModuleBatching
                    && containsProperty(mModuleProperties, SPARSE_MODULE_PROPERTY, "true")) {
                // batch sparsely reported modules together, upload at the end of the invocation.
                mCachedWorkUnits.add(workUnit);
                mWorkUnits.push(workUnit);
                return;
            }
            try {
                mWorkUnits.push(mClient.createWorkUnit(workUnit));
            } catch (IOException e) {
                CLog.w("%s\n", workUnit);
                throw new RuntimeException(e);
            }
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.ANTS_MODULE_START_TIME,
                    System.currentTimeMillis() - startModule);
        }
    }

    @Override
    public void testModuleEnded() {
        if (mDisable | mDelegatedMode) {
            return;
        }
        long moduleEnd = System.currentTimeMillis();
        try (CloseableTraceScope ignored = new CloseableTraceScope("ants:testModuleEnded")) {
            List<Property> moduleCheckerProperties = getModuleCheckerProperties(mModuleContext);
            long value = currentTimestamp();
            if (!Strings.isNullOrEmpty(mModuleContext.getAttribute(ITestSuite.MODULE_END_TIME))) {
                try {
                    value = Long.parseLong(mModuleContext.getAttribute(ITestSuite.MODULE_END_TIME));
                } catch (NumberFormatException nfe) {
                    CLog.e(nfe);
                }
            }
            SkipReason moduleSkip = null;
            if (mModuleContext != null
                    && !Strings.isNullOrEmpty(
                            mModuleContext.getAttribute(ModuleDefinition.MODULE_SKIPPED))) {
                moduleSkip =
                        new SkipReason(
                                mModuleContext.getAttribute(ModuleDefinition.MODULE_SKIPPED), "");
                CLog.d("Received a module skipped: %s", moduleSkip);
            }
            final long currentTimeStamp = value;
            mModuleContext = null;
            mCurrentModule = null;
            mModuleProperties = Collections.emptyList();
            mModuleParameters = Collections.emptyList();
            WorkUnit workUnit = mWorkUnits.peek();
            if (!workUnit.getType().equals(WorkUnitType.TF_MODULE.name())) {
                CLog.w("Unexpected work unit in testModuleEnded %s, expected TF_MODULE.", workUnit);
                CLog.w("Skipping AnTS work unit update.");
                return;
            }
            final WorkUnit update = mWorkUnits.pop();
            if (mEnableModuleBatching
                    && containsProperty(update.getProperties(), SPARSE_MODULE_PROPERTY, "true")) {
                // batch sparsely reported modules together, upload at the end of the invocation.
                updateModuleWorkUnit(update, currentTimeStamp, moduleCheckerProperties, moduleSkip);
                return;
            }
            final SkipReason finalModuleSkip = moduleSkip;
            Runnable updater =
                    new Runnable() {

                        @Override
                        public void run() {
                            String workUnitId = update.getId();
                            try {
                                ObjectUpdater.create(mClient)
                                        .runUpdate(
                                                workUnitId,
                                                new ObjectUpdater.WorkUnitUpdate() {
                                                    @Override
                                                    public WorkUnit doUpdate(WorkUnit current) {
                                                        return updateModuleWorkUnit(
                                                                current,
                                                                currentTimeStamp,
                                                                moduleCheckerProperties,
                                                                finalModuleSkip);
                                                    }
                                                });
                            } catch (IOException e) {
                                CLog.w("%s\n", update);
                                throw new RuntimeException(e);
                            }
                            if (mUploadLogAssociationWithWorkUnit) {
                                Collection<LogAssociation> logs =
                                        mLogAssociations.removeAll(workUnitId);
                                try {
                                    uploadLogAssociations(new ArrayList<>(logs));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    };
            if (mParallelOperations) {
                // Run updater non-blocking
                Thread updaterThread = new Thread(updater);
                updaterThread.setName("ants-updater-thread");
                updaterThread.setDaemon(false);
                updaterThread.start();
                mUpdateActions.add(updaterThread);
                // Removing already done updates.
                mUpdateActions.removeIf(t -> !t.isAlive());
            } else {
                // Run blocking
                updater.run();
            }
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.ANTS_MODULE_END_TIME,
                    System.currentTimeMillis() - moduleEnd);
        }
    }

    private WorkUnit updateModuleWorkUnit(
            WorkUnit workUnit,
            long currentTimeStamp,
            List<Property> properties,
            SkipReason reason) {
        if (reason != null) {
            workUnit.setSkippedReason(convertTfToAnTS(reason, SkipReasonType.REASON_UNSPECIFIED))
                    .setState(SchedulerState.SKIPPED.toString());
        } else {
            workUnit.setState(SchedulerState.COMPLETED.toString());
        }
        workUnit.getTiming().setCompleteTimestamp(currentTimeStamp);
        workUnit.setProperties(
                new ListBuilder<>(workUnit.getProperties()).addAll(properties).build());
        return workUnit;
    }

    @Override
    public void testRunStarted(String runName, int testCount) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        testRunStarted(runName, testCount, 0);
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        testRunStarted(runName, testCount, attemptNumber, currentTimestamp());
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber, long startTime) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        long runStart = System.currentTimeMillis();
        try {
            if (mEnableMetricFile) {
                mMetricFileUtil.testRunStarted(runName);
            }

            WorkUnit parent = mWorkUnits.peek();
            if (mEnableEmptyModuleReporting
                    && !parent.getType().equals(WorkUnitType.TF_MODULE.name())) {
                parent = getEmptyModuleWorkUnit();
            }

            WorkUnit workUnit = new WorkUnit();
            workUnit.setName(runName)
                    .setInvocationId(mInvocation.getInvocationId())
                    .setParentId(parent.getId())
                    .setTiming(new Timing().setCreationTimestamp(startTime))
                    // Keep the property & the child attempt number for backward compatibility
                    .setProperties(
                            Arrays.asList(
                                    new Property()
                                            .setName(ATTEMPT_PROPERTY)
                                            .setValue(Integer.toString(attemptNumber))))
                    .setChildAttemptNumber(attemptNumber)
                    .setType(WorkUnitType.TF_TEST_RUN.name())
                    .setState(SchedulerState.RUNNING.toString());
            try {
                mWorkUnits.push(mClient.createWorkUnit(workUnit));
            } catch (IOException e) {
                CLog.w("%s\n", workUnit);
                throw new RuntimeException(e);
            }
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.ANTS_RUN_START_TIME, System.currentTimeMillis() - runStart);
        }
    }

    @Override
    public void testRunFailed(String errorMessage) {
        testRunFailed(FailureDescription.create(errorMessage));
    }

    @Override
    public void testRunFailed(FailureDescription failure) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        if (!mEnableErrorTypes) {
            testRunFailed(failure.toString());
            return;
        }
        WorkUnit workUnit = mWorkUnits.peek();
        if (!workUnit.getType().equals(WorkUnitType.TF_TEST_RUN.name())) {
            CLog.w("Unexpected work unit in testRunFailed %s, expected TF_TEST_RUN", workUnit);
            CLog.w("Skipping AnTS update");
            return;
        }
        DebugInfo debugInfo = new DebugInfo().setErrorMessage(failure.getFormattedErrorMessage());
        setErrorType(debugInfo, failure);
        if (failure.getErrorIdentifier() != null) {
            debugInfo.setErrorCode(failure.getErrorIdentifier().code());
            debugInfo.setErrorName(failure.getErrorIdentifier().name());
        }
        if (failure.getOrigin() != null) {
            debugInfo.setErrorOrigin(failure.getOrigin());
        }
        workUnit.setDebugInfo(debugInfo).setState(SchedulerState.ERROR.toString());
        CLog.i("Set work unit %s state to error due to test run failure", workUnit.getId());
    }

    @Override
    public void testRunEnded(
            long elapsedTimeMillis, HashMap<String, MetricMeasurement.Metric> runMetrics) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        long runEnded = System.currentTimeMillis();
        try {
            if (mEnableSkipMethodReporting) {
                try {
                    reportSkippedMethod();
                } catch (RuntimeException e) {
                    CLog.e("error during skip reporting:");
                    CLog.e(e);
                }
            }
            if (mEnableMetricFile) {
                mMetricFileUtil.testRunEnded(runMetrics);
            }

            WorkUnit workUnit = mWorkUnits.peek();
            if (!workUnit.getType().equals(WorkUnitType.TF_TEST_RUN.name())) {
                CLog.w("Unexpected work unit in testRunFailed %s, expected TF_TEST_RUN", workUnit);
                CLog.w("Skipping AnTS update");
                return;
            }
            final WorkUnit update = mWorkUnits.pop();
            if (SchedulerState.RUNNING.toString().equals(update.getState())) {
                update.setState(SchedulerState.COMPLETED.toString());
            }

            CLog.i(
                    "TestRunEnded setting work unit %s state to %s",
                    update.getId(), update.getState());
            Runnable updater =
                    new Runnable() {
                        @Override
                        public void run() {
                            String workUnitId = update.getId();
                            try {
                                ObjectUpdater.create(mClient)
                                        .runUpdate(
                                                workUnitId,
                                                new ObjectUpdater.WorkUnitUpdate() {
                                                    @Override
                                                    public WorkUnit doUpdate(WorkUnit current) {
                                                        current.setState(update.getState());
                                                        long startTime =
                                                                current.getTiming()
                                                                        .getCreationTimestamp();
                                                        current.getTiming()
                                                                .setCompleteTimestamp(
                                                                        startTime
                                                                                + elapsedTimeMillis);
                                                        if (update.getDebugInfo() != null) {
                                                            current.setDebugInfo(
                                                                    update.getDebugInfo());
                                                        }
                                                        if (mEnableMetrics) {
                                                            current.setMetrics(
                                                                    MetricUtils.toMetrics(
                                                                            runMetrics));
                                                        }
                                                        if (!runMetrics.isEmpty()) {
                                                            ListBuilder<Property> propertyBuilder =
                                                                    getRunMetrics(
                                                                            current.getProperties(),
                                                                            runMetrics);
                                                            current.setProperties(
                                                                    propertyBuilder.build());
                                                        }
                                                        return current;
                                                    }
                                                });
                            } catch (IOException e) {
                                CLog.w("%s\n", workUnit);
                                throw new RuntimeException(e);
                            }
                            if (mUploadLogAssociationWithWorkUnit) {
                                Collection<LogAssociation> logs =
                                        mLogAssociations.removeAll(workUnitId);
                                try {
                                    uploadLogAssociations(new ArrayList<>(logs));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    };
            if (mParallelOperations) {
                // Run updater non-blocking
                Thread updaterThread = new Thread(updater);
                updaterThread.setName("ants-updater-thread");
                updaterThread.setDaemon(false);
                updaterThread.start();
                mUpdateActions.add(updaterThread);
                // Removing already done updates.
                mUpdateActions.removeIf(t -> !t.isAlive());
            } else {
                // Run blocking
                updater.run();
            }
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.ANTS_RUN_END_TIME, System.currentTimeMillis() - runEnded);
        }
    }

    private ListBuilder<Property> getRunMetrics(
            List<Property> current, HashMap<String, MetricMeasurement.Metric> runMetrics) {
        ListBuilder<Property> propertyBuilder = new ListBuilder<Property>(current);
        for (String key : METRIC_STRING_TO_PROPERTIES) {
            if (runMetrics.containsKey(key)) {
                String value = runMetrics.get(key).getMeasurements().getSingleString();
                propertyBuilder.add(newProperty(key, value));
            }
        }
        for (String key : METRIC_INT_TO_PROPERTIES) {
            if (runMetrics.containsKey(key)) {
                long value = runMetrics.get(key).getMeasurements().getSingleInt();
                propertyBuilder.add(newProperty(key, Long.toString(value)));
            }
        }
        return propertyBuilder;
    }

    @Override
    public void testStarted(TestDescription test) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        testStarted(test, currentTimestamp());
    }

    @Override
    public void testStarted(TestDescription test, long startTime) {
        if (mDisable | mDelegatedMode) {
            return;
        }

        WorkUnit peek = mWorkUnits.peek();
        if (peek == null) {
            CLog.e("testStarted(%s) didn't find a parent work unit.", test);
            return;
        }

        mCurrentTestResult = new TestResult();
        mCurrentTestResult
                .setWorkUnitId(peek.getId())
                // Set initial state to pass, because we only upload
                // test results at the end and this way we don't need
                // to check the test status in testEnded.
                .setTestStatus(TestStatus.PASS.toString())
                .setInvocationId(mInvocation.getInvocationId())
                .setTiming(new Timing().setCreationTimestamp(startTime))
                .setTestIdentifier(
                        new TestIdentifier()
                                .setModule(mCurrentModule)
                                .setTestClass(test.getClassName())
                                .setModuleParameters(mModuleParameters)
                                .setMethod(test.getTestName()));
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        handleTestFailed(test, trace);

        // Log stacktrace.
        CLog.e(
                "Received #testFailed(%s, %s) - should NEVER happen.\n%s",
                test, trace, StreamUtil.getStackTrace(new Exception()));
    }

    private void handleTestFailed(TestDescription test, String trace) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testFailed(%s) without a valid testStart before.", test);
            return;
        }
        final DebugInfo debugInfo = new DebugInfo().setTrace(trace);
        final String message = extractErrorMessage(trace);
        if (message != null && !message.isEmpty()) {
            debugInfo.setErrorMessage(message);
        }
        mCurrentTestResult.setTestStatus(TestStatus.FAIL.toString()).setDebugInfo(debugInfo);
        String localInstructions = getLocalInstructions(test);
        ListBuilder<Property> propertyBuilder =
                new ListBuilder<>(mCurrentTestResult.getProperties());
        if (!localInstructions.isEmpty()) {
            propertyBuilder.add(newProperty(LOCAL_INSTRUCTION_PROPERTY, localInstructions));
        }
        mCurrentTestResult.setProperties(propertyBuilder.build());
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        if (!mEnableErrorTypes) {
            CLog.d("Option \"enable-error-types\" is false, bypassing testStatus converting.");
            handleTestFailed(test, failure.toString());
            return;
        }

        handleTestFailed(test, failure.toString());

        mCurrentTestResult.setTestStatus(
                TestStatus.fromDescription(failure.getFailureStatus()).toString());
        // Add additional error type from the FailureDescription
        DebugInfo info = mCurrentTestResult.getDebugInfo();
        setErrorType(info, failure);
        mCurrentTestResult.setDebugInfo(info);
    }

    private String getLocalInstructions(TestDescription test) {
        LocalInstructions instructions = new LocalInstructions(mContext, mModuleContext);
        return instructions.forTest(test);
    }

    @Override
    public void testAssumptionFailure(TestDescription test, String trace) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        testAssumptionFailure(test, FailureDescription.create(trace));
    }

    @Override
    public void testAssumptionFailure(TestDescription test, FailureDescription failure) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testAssumptionFailure(%s) without a valid testStart before.", test);
            return;
        }
        final DebugInfo debugInfo = new DebugInfo().setTrace(failure.getErrorMessage());
        final String message = extractErrorMessage(failure.getErrorMessage());
        if (message != null && !message.isEmpty()) {
            debugInfo.setErrorMessage(message);
        }
        mCurrentTestResult
                .setTestStatus(TestStatus.ASSUMPTION_FAILURE.toString())
                .setDebugInfo(debugInfo);
    }

    @Override
    public void testSkipped(TestDescription test, SkipReason reason) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testIgnored(%s) without a valid testStart before.", test);
            return;
        }
        mCurrentTestResult
                .setTestStatus(TestStatus.TEST_SKIPPED.toString())
                .setSkippedReason(convertTfToAnTS(reason, SkipReasonType.REASON_UNSPECIFIED));
    }

    @Override
    public void testIgnored(TestDescription test) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testIgnored(%s) without a valid testStart before.", test);
            return;
        }
        mCurrentTestResult.setTestStatus(TestStatus.IGNORED.toString());
    }

    @Override
    public void testEnded(
            TestDescription test,
            long endTime,
            HashMap<String, MetricMeasurement.Metric> testMetrics) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testEnded(%s) without a valid testStart before.", test);
            return;
        }

        if (mEnableMetricFile) {
            mMetricFileUtil.testEnded(test, testMetrics);
        }

        mCurrentTestResult.getTiming().setCompleteTimestamp(endTime);
        if (mEnableMetrics) {
            Metrics metrics = MetricUtils.toMetrics(testMetrics);
            mCurrentTestResult.setMetrics(metrics);
        }
        ListBuilder<Property> propertyBuilder = new ListBuilder<>(Collections.emptyList());
        if (testMetrics.get(TEST_MAPPING_PROPERTY) != null) {
            // Get Test Mapping sources from string formatting with list such as "[path1, path2]".
            // Note: Some test mapping sources may not be recorded. This is because a test module
            // can be defined across multiple TEST_MAPPING files, and TF doesn't run it again if
            // it's passed in the previous run.
            String testMappingMeasurement =
                    testMetrics
                            .get(TEST_MAPPING_PROPERTY)
                            .getMeasurements()
                            .getSingleString()
                            .replaceAll("^\\[| |\\]$", "");
            List<String> testMappingSources = Arrays.asList(testMappingMeasurement.split(","));
            for (String testMappingSource : testMappingSources) {
                propertyBuilder.add(newProperty(TEST_MAPPING_PROPERTY, testMappingSource));
            }
        }
        propertyBuilder.addAll(mMigrationTestProperties);
        if (!propertyBuilder.isEmpty()) {
            mCurrentTestResult.setProperties(propertyBuilder.build());
        }
        mClient.uploadResult(mCurrentTestResult);
        mCurrentTestResult = null;
    }

    @Override
    public void testEnded(TestDescription test, HashMap<String, MetricMeasurement.Metric> metrics) {
        if (mDisable | mDelegatedMode) {
            return;
        }
        testEnded(test, currentTimestamp(), metrics);
    }

    private void reportSkippedMethod() {
        if (mDisable | mDelegatedMode) {
            return;
        }

        WorkUnit peek = mWorkUnits.peek();
        if (peek == null) {
            CLog.e("Didn't find a parent work unit for skipped method.");
            return;
        }
        String moduleAbi = null;
        String moduleName = null;
        if (mModuleContext != null) {
            moduleAbi = mModuleContext.getAttribute(ModuleDefinition.MODULE_ABI);
            moduleName = mModuleContext.getAttribute(ModuleDefinition.MODULE_NAME);
        }
        for (Entry<String, SkipReason> entry :
                getConfiguration().getSkipManager().getDemotedTests().entrySet()) {
            String key = entry.getKey();
            SuiteTestFilter filter = SuiteTestFilter.createFrom(key);
            // If module filter has an ABI, we expect the filter to match it to be applicable
            // not having it is a bit ambiguous so we default to report for all abi similar
            // to how filters are applied.
            if (filter.getAbi() != null && !filter.getAbi().equals(moduleAbi)) {
                continue;
            }
            if (!filter.getName().equals(moduleName)) {
                continue;
            }
            if (filter.getTest() == null) {
                continue;
            }
            TestDescription test = TestDescription.fromString(filter.getTest());
            TestResult currentTest = new TestResult();
            currentTest
                    .setWorkUnitId(peek.getId())
                    .setTestStatus(TestStatus.TEST_SKIPPED.toString())
                    .setInvocationId(mInvocation.getInvocationId())
                    .setTiming(new Timing().setCreationTimestamp(System.currentTimeMillis()))
                    .setTestIdentifier(
                            new TestIdentifier()
                                    .setModule(mCurrentModule)
                                    .setTestClass(test.getClassName())
                                    .setModuleParameters(mModuleParameters)
                                    .setMethod(test.getTestName()));
            SkippedReason reason =
                    convertTfToAnTS(entry.getValue(), SkipReasonType.REASON_DEMOTION);
            currentTest.setSkippedReason(reason);
            mClient.uploadResult(currentTest);
            CLog.d("Backfilled %s SKIPPED", currentTest);
        }
    }

    @Override
    public void logAssociation(String dataName, LogFile log) {
        if (mDisable) {
            return;
        }
        if (mDisableAssociations) {
            return;
        }
        if (mWorkUnits == null) {
            // InvocationStarted is not called and the work unit ID is not known.
            // We save these early association to memory and add them to the mLogAssociations map
            // when invocation is started.
            CLog.v("Received early association %s %s %s", dataName, log.getPath(), log.getUrl());
            mEarlyAssociations.add(log);
            return;
        }
        if (mInvocationEnded) {
            // InvocationEnded is called. There will no more calls to upload log associations in
            // mLogAssociations.
            // So we upload the log association directly here.
            CLog.v(
                    "Received association after invocation ended %s %s %s",
                    dataName, log.getPath(), log.getUrl());
            LogAssociation logAssociation =
                    new LogAssociation(
                            mInvocation, mWorkUnitId, mCurrentTestResult, mLegacyResultId, log);
            try {
                uploadLogAssociations(new ArrayList<>(Arrays.asList(logAssociation)));
            } catch (IOException e) {
                CLog.e(e);
            }
            return;
        }
        CLog.v("Received association %s %s %s", dataName, log.getPath(), log.getUrl());
        String workUnitId = mWorkUnits.peek().getId();
        mLogAssociations.put(
                workUnitId,
                new LogAssociation(
                        mInvocation, workUnitId, mCurrentTestResult, mLegacyResultId, log));
    }

    private WorkUnit newWorkUnit(String invocationId) {
        return new WorkUnit()
                .setName(TF_INVOCATION_LABEL)
                .setType("")
                .setTiming(new Timing().setCreationTimestamp(currentTimestamp()))
                .setState(SchedulerState.RUNNING.toString())
                .setInvocationId(invocationId);
    }

    private WorkUnit newWorkUnit(String invocationId, WorkUnitType type) {
        return newWorkUnit(invocationId).setType(type.name());
    }

    private void updateInvocation() {
        String localRunner = mContext.getAttribute(LOCAL_RUNNER);
        List<Property> invocationProperties = InvocationProperties.parse(mContext);
        ExtraBuildsBuilder extraBuildsBuilder = new ExtraBuildsBuilder(mContext);
        List<String> tags = mConfiguration.getCommandOptions().reportingTags();
        if (invocationProperties.isEmpty()
                && localRunner.isEmpty()
                && extraBuildsBuilder.isEmpty()
                && tags.isEmpty()
                && mBuildInfo == null) {
            return;
        }

        CLog.d("Updating invocation. invoc properties=%s, tags=%s", invocationProperties, tags);
        // When updating labels and properties, we will not append them if
        // an identical label or property already exists.
        try {
            ObjectUpdater.create(mClient)
                    .runUpdate(
                            mInvocation.getInvocationId(),
                            new ObjectUpdater.InvocationUpdate() {
                                @Override
                                public Invocation doUpdate(Invocation current) {
                                    boolean updated = false;

                                    ListBuilder<Property> propertyBuilder =
                                            new ListBuilder<>(current.getProperties());
                                    for (Property p : invocationProperties) {
                                        if (!propertyBuilder.contains(p)) {
                                            propertyBuilder.add(p);
                                            updated = true;
                                        }

                                        // Set crystalball_has_data to yes if crystalball ingest
                                        // property is set and metrics are available.
                                        if (p.getName()
                                                        .equalsIgnoreCase(
                                                                CRYSTALBALL_INGEST_PROPERTY)
                                                && p.getValue().equalsIgnoreCase("yes")
                                                && mMetricFileUtil.isMetricAvailable()) {
                                            Property metricAvailableProperty =
                                                    newProperty(
                                                            CRYSTALBALL_HAS_DATA_PROPERTY, "yes");
                                            if (!propertyBuilder.contains(
                                                    metricAvailableProperty)) {
                                                propertyBuilder.add(metricAvailableProperty);
                                                updated = true;
                                            }
                                        }
                                    }
                                    // Add flag overrides if they exist in build info.
                                    if (mBuildInfo != null) {
                                        String flagOverrides =
                                                mBuildInfo
                                                        .getBuildAttributes()
                                                        .getOrDefault(
                                                                ITestSuite
                                                                        .BUILD_ATTRIBUTE_FLAG_OVERRIDES_KEY,
                                                                "");
                                        AconfigFlags currentFlags =
                                                current.getAconfigFlagOverrides();
                                        if (!flagOverrides.isEmpty()) {
                                            AconfigFlags newFlags =
                                                    addOrUpdateFlagOverrides(
                                                            currentFlags, flagOverrides);
                                            if (newFlags != null
                                                    && !newFlags.equals(currentFlags)) {
                                                current.setAconfigFlagOverrides(newFlags);
                                                updated = true;
                                            }
                                        }
                                    }

                                    // If it's mainline invocation, update mainline module
                                    // properties(i.e. module_installed) to
                                    // prepend the version of the specific module to its build info.
                                    // e.g., module installed:
                                    // <version_module_1><module_1_build_info>
                                    //       module installed:
                                    // <version_module_2><module_2_build_info>
                                    if (mContext.getBuildInfos().size() > 0) {
                                        // Keep updated to true if already set
                                        updated |= updateMainlineProperties(current);
                                    }

                                    if (!tags.isEmpty()) {
                                        Set<String> currentTags = new HashSet<>();
                                        if (current.getTags() != null) {
                                            currentTags.addAll(current.getTags());
                                        }
                                        currentTags.addAll(tags);
                                        current.setTags(new ArrayList<>(currentTags));
                                        updated = true;
                                    }

                                    if (!localRunner.isEmpty()) {
                                        Property localRunnerProp =
                                                newProperty(LOCAL_RUNNER_PROPERTY, localRunner);
                                        if (!propertyBuilder.contains(localRunnerProp)) {
                                            propertyBuilder.add(localRunnerProp);
                                            updated = true;
                                        }
                                    }
                                    current.setProperties(propertyBuilder.build());

                                    List<BuildDescriptor> extraBuilds =
                                            new ArrayList<>(
                                                    extraBuildsBuilder.build(
                                                            current.getExtraBuilds()));
                                    if (!extraBuilds.isEmpty()) {
                                        updated = true;
                                        current.setExtraBuilds(extraBuilds);
                                    }
                                    if (updated) {
                                        return current;
                                    }
                                    return null;
                                }
                            });
        } catch (IOException e) {
            CLog.w("Failed to update invocation %s", mInvocation);
            CLog.w(e);
        }
    }

    private boolean updateMainlineProperties(Invocation current) {
        boolean updated = false;
        Map<String, String> attributes = mContext.getBuildInfos().get(0).getBuildAttributes();
        for (String attributeName : attributes.keySet()) {
            if (attributeName.contains(MAINLINE_VERSION_SUFFIX)) {
                for (Property p : current.getProperties()) {
                    // Update invocation properties to prepend version number, skip if
                    // version already prepended
                    if (isMainlineProperty(p)) {
                        String modulePkgName =
                                attributeName.substring(
                                        0,
                                        attributeName.length() - MAINLINE_VERSION_SUFFIX.length());
                        if (p.getValue().contains(modulePkgName)
                                && p.getValue().startsWith(MAINLINE_MODULE_PREFIX)) {
                            String version =
                                    mContext.getBuildInfos()
                                            .get(0)
                                            .getBuildAttributes()
                                            .get(attributeName);
                            p.setValue(String.format("[%s] %s", version, p.getValue()));
                            if (!updated) {
                                updated = true;
                            }
                            break;
                        }
                    }
                }
            }
        }
        return updated;
    }

    private boolean isMainlineProperty(Property p) {
        return p.getName().equals(MAINLINE_MODULE_PROPERTY);
    }

    private WorkUnit getEmptyModuleWorkUnit() {
        if (mEmptyModuleWorkUnit == null) {
            WorkUnit workUnit =
                    new WorkUnit()
                            .setParentId(mWorkUnits.peek().getId())
                            .setInvocationId(mInvocation.getInvocationId())
                            .setTiming(new Timing().setCreationTimestamp(currentTimestamp()))
                            .setType(WorkUnitType.TF_MODULE.name())
                            .setState(SchedulerState.RUNNING.toString())
                            .setModuleInfo(new ModuleInfo());
            try {
                mEmptyModuleWorkUnit = mClient.createWorkUnit(workUnit);
            } catch (IOException e) {
                CLog.w("%s\n", workUnit);
                throw new RuntimeException(e);
            }
        }
        return mEmptyModuleWorkUnit;
    }

    private void finalizeEmptyModuleWorkUnit() {
        if (mEmptyModuleWorkUnit == null) {
            return;
        }
        WorkUnit workUnit = mEmptyModuleWorkUnit;
        try {
            ObjectUpdater.create(mClient)
                    .runUpdate(
                            workUnit.getId(),
                            new ObjectUpdater.WorkUnitUpdate() {
                                @Override
                                public WorkUnit doUpdate(WorkUnit current) {
                                    current.getTiming().setCompleteTimestamp(currentTimestamp());
                                    current.setState(SchedulerState.COMPLETED.toString());
                                    return current;
                                }
                            });
        } catch (IOException e) {
            CLog.w("Failed to update work unit %s", workUnit);
            CLog.w(e);
        }
        mEmptyModuleWorkUnit = null;
    }

    private void finalizeTfWorkUnit() {
        WorkUnit workUnit = mWorkUnits.pop();

        MultiMap<String, String> configurationProperties = createConfigurationMap(mContext);

        try {
            ObjectUpdater.create(mClient)
                    .runUpdate(
                            workUnit.getId(),
                            new ObjectUpdater.WorkUnitUpdate() {
                                @Override
                                public WorkUnit doUpdate(WorkUnit current) {

                                    if (mManagingInvocation) {
                                        current.getTiming()
                                                .setCompleteTimestamp(currentTimestamp());
                                        if (!mInvocation
                                                        .getSchedulerState()
                                                        .equals(SchedulerState.ERROR.toString())
                                                && !mInvocation
                                                        .getSchedulerState()
                                                        .equals(
                                                                SchedulerState.SKIPPED
                                                                        .toString())) {
                                            current.setState(SchedulerState.COMPLETED.toString());
                                        }
                                    }
                                    if (mInvocationSkipReason != null) {
                                        current.setSkippedReason(
                                                convertTfToAnTS(
                                                        mInvocationSkipReason,
                                                        SkipReasonType.REASON_UNSPECIFIED));
                                        current.setState(SchedulerState.SKIPPED.toString());
                                        current.getTiming()
                                                .setCompleteTimestamp(currentTimestamp());
                                    }
                                    // Avoid duplicate property reporting, report properties in the
                                    // parent
                                    if (System.getenv(
                                                    DelegatedInvocationExecution.DELEGATED_MODE_VAR)
                                            != null) {
                                        CLog.d("Delegated mode. Skipping properties.");
                                    } else {
                                        addWorkUnitProperties(current, configurationProperties);
                                    }
                                    if (mFailureInfo != null) {
                                        current.setDebugInfo(mFailureInfo);
                                    }
                                    return current;
                                }
                            });
        } catch (IOException e) {
            CLog.w("Failed to update work unit %s", workUnit);
            CLog.w(e);
        }

        mWorkUnits.push(workUnit);
    }

    /**
     * Returns a map with configuration settings which might be interesting to clients. For example
     * device serial number, TF version, transponder link, jvm settings and so on.
     */
    private MultiMap<String, String> createConfigurationMap(IInvocationContext context) {
        // Use UniqueMultiMap to dedup attributes values
        UniqueMultiMap<String, String> result = new UniqueMultiMap<>();
        // These excluded properties are already present as the work unit id and
        // invocation id fields
        Set<String> excluded_properties =
                new HashSet<>(
                        Arrays.asList("work_unit_id", "invocation_id", "invocation-property"));

        String serial = null;
        if (mBuildInfo != null) {
            serial = mBuildInfo.getDeviceSerial();
        }
        if (serial != null) {
            result.put("serial", serial);
        }

        String testTag = context.getTestTag();
        if (testTag != null) {
            result.put("test_tag", testTag);
        }

        // Add configuration attributes.
        for (String attributeKey : context.getAttributes().keySet()) {
            if (excluded_properties.contains(attributeKey)) {
                continue;
            }

            List<String> values = context.getAttributes().get(attributeKey);
            for (String value : values) {
                result.put(attributeKey, value);
            }
        }

        // Add build attributes
        if (mBuildInfo != null) {
            for (Map.Entry<String, String> buildAttribute :
                    mBuildInfo.getBuildAttributes().entrySet()) {
                result.put(buildAttribute.getKey(), buildAttribute.getValue());
            }
            final String buildFlavor = mBuildInfo.getBuildFlavor();
            if (buildFlavor != null && !buildFlavor.isEmpty()) {
                result.put(BUILD_FLAVOR_PROPERTY, buildFlavor);
            }
        }

        final String tfVersion = getVersion();
        if (!tfVersion.isEmpty()) {
            result.put(TF_VERSION_PROPERTY, tfVersion);
        }

        final String user = getUser();
        if (user != null && !user.isEmpty()) {
            result.put(USER_PROPERTY, user);
        }

        final String hostname = getHostName();
        if (hostname != null && !hostname.isEmpty()) {
            result.put(HOSTNAME_PROPERTY, hostname);
        }

        // Carry tags to property for plx tracking
        for (String tag : mConfiguration.getCommandOptions().reportingTags()) {
            result.put(tag, "true");
        }

        // add cluster options to sponge configure values.
        IClusterOptions clusterOptions =
                (IClusterOptions)
                        getGlobalConfig().getConfigurationObject(ClusterOptions.TYPE_NAME);
        if (clusterOptions != null) {
            // add host labels to sponge configure values.
            for (String label : clusterOptions.getLabels()) {
                if (label != null && !label.isEmpty()) {
                    result.put(CLUSTER_OPTIONS_HOST_LABEL_PROPERTY, label);
                }
            }

            final String labName = clusterOptions.getLabName();
            if (labName != null && !labName.isEmpty()) {
                result.put(CLUSTER_OPTIONS_LAB_NAME_PROPERTY, labName);
            }

            final String clusterId = clusterOptions.getClusterId();
            if (clusterId != null && !clusterId.isEmpty()) {
                result.put(CLUSTER_OPTIONS_CLUSTER_ID_PROPERTY, clusterId);
            }
        }

        return result;
    }

    private Invocation newInvocation(IInvocationContext context, String invocationId) {
        // To post results we need to keep track of invocation id, and build id and build target.
        // Even if invocation was created by the scheduler we must still set build id and target
        // here so we can post results to it.
        Invocation invocation = new Invocation().setInvocationId(invocationId);
        List<IBuildInfo> buildInfos;
        if (context.getBuildInfo(AbLocalBuildProvider.LOCAL_BUILD) != null) {
            buildInfos = Arrays.asList(context.getBuildInfo(AbLocalBuildProvider.LOCAL_BUILD));
        } else {
            buildInfos = context.getBuildInfos();
        }
        for (int i = 0; i < buildInfos.size(); i++) {
            IBuildInfo buildInfo = buildInfos.get(i);
            BuildDescriptor build = new BuildDescriptor().setBuildProvider(DEFAULT_BUILDPROVIDER);
            if (buildInfo.getBuildBranch() != null) {
                build.setBranch(buildInfo.getBuildBranch());
            }
            if (buildInfo.getBuildFlavor() != null) {
                build.setBuildTarget(buildInfo.getBuildFlavor());
            }
            if (buildInfo.getBuildId() != null) {
                build.setBuildId(buildInfo.getBuildId());
            }
            if (i == 0) {
                invocation.setPrimaryBuild(build);
            } else {
                invocation.setExtraBuilds(
                        new ListBuilder<>(invocation.getExtraBuilds()).add(build).build());
            }
        }
        return invocation;
    }

    private Invocation newInvocation(IInvocationContext context) {
        Invocation invocation =
                newInvocation(context, null)
                        .setSchedulerState(SchedulerState.RUNNING.toString())
                        .setTest(new TestDefinition().setName(context.getTestTag()))
                        .setTags(Arrays.asList(TF_INVOCATION_LABEL))
                        .setTiming(new Timing().setCreationTimestamp(currentTimestamp()))
                        .setRunner(TRADEFED);
        return invocation;
    }

    private Invocation getInvocation(String invocationId) {
        try {
            return mClient.getInvocation(invocationId);
        } catch (IOException e) {
            mDisable = true;
            throw new RuntimeException(e);
        }
    }

    private Invocation finalizeInvocation(Invocation invocation) throws IOException {
        return ObjectUpdater.create(mClient)
                .runUpdate(
                        invocation.getInvocationId(),
                        new ObjectUpdater.InvocationUpdate() {
                            @Override
                            public Invocation doUpdate(Invocation current) {
                                if (current.getSchedulerState()
                                        .equals(SchedulerState.RUNNING.toString())) {
                                    current.setSchedulerState(SchedulerState.COMPLETED.toString());
                                    return current;
                                }
                                return null;
                            }
                        });
    }

    private Property newProperty(String key, String value) {
        return new Property().setName(key).setValue(value);
    }

    private List<Property> toProperties(MultiMap<String, String> propertyMap) {
        ArrayList<Property> properties = new ArrayList<>();
        for (Map.Entry<String, String> entry : propertyMap.entries()) {
            properties.add(newProperty(entry.getKey(), entry.getValue()));
        }
        return properties;
    }

    private boolean containsProperty(List<Property> properties, String name, String value) {
        return properties.stream()
                .anyMatch(p -> p.getName().equals(name) && p.getValue().equals(value));
    }

    private List<Property> getModuleProperties(IInvocationContext context) {
        List<Property> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : context.getAttributes().entries()) {
            if (ALLOWED_MODULE_PROPERTIES.contains(entry.getKey())) {
                result.add(newProperty(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    private List<Property> getModuleCheckerProperties(IInvocationContext context) {
        List<Property> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : context.getAttributes().entries()) {
            String key = entry.getKey();
            if (key.startsWith(SYSTEM_CHECKER)) {
                result.add(
                        newProperty(key.substring(SYSTEM_CHECKER.length() + 1), entry.getValue()));
            }
        }
        return result;
    }

    /*
     * Only module-abi and module-param are used in the module identifier, so filter other
     * values.
     */
    private List<Property> getModuleParameters(List<Property> properties) {
        List<Property> result = new ArrayList<>();
        for (Property property : properties) {
            if (ALLOWED_MODULE_PARAMETERS.contains(property.getName())) {
                result.add(property);
            }
        }
        for (Property migrationProperty : mMigrationTestProperties) {
            result.add(migrationProperty);
        }
        return result;
    }

    /*
     * Add a property to WorkUnit.
     */
    private void addWorkUnitProperty(WorkUnit workUnit, Property property) {
        workUnit.setProperties(new ListBuilder<>(workUnit.getProperties()).add(property).build());
    }

    /*
     * Add a list of properties to WorkUnit.
     */
    private void addWorkUnitProperties(WorkUnit workUnit, MultiMap<String, String> properties) {
        workUnit.setProperties(
                uniqueProperties(new ListBuilder<>(workUnit.getProperties()), properties).build());
    }

    /*
     * Some properties like 'tradefed_version' shouldn't be re-added
     */
    private ListBuilder<Property> uniqueProperties(
            ListBuilder<Property> currentProperties, MultiMap<String, String> properties) {
        for (Property prop : currentProperties.entries) {
            if (UNIQUE_PROPERTIES.contains(prop.getName())) {
                properties.remove(prop.getName());
            }
        }
        return currentProperties.addAll(toProperties(properties));
    }

    private void setErrorType(DebugInfo info, FailureDescription failure) {
        ErrorType errorType = ErrorType.create(failure.getFailureStatus());
        if (errorType != ErrorType.ERROR_TYPE_UNSPECIFIED) {
            info.setErrorType(errorType.toString());
        }
    }

    @Override
    public void putEarlySummary(List<TestSummary> summaries) {
        for (TestSummary summary : summaries) {
            if (TestSummary.Type.URI.equals(summary.getSummary().getType())) {
                mSummaryUrl = summary.getSummary().getString();
            }
        }

        if (mSummaryUrl == null || mSummaryUrl.isEmpty()) {
            CLog.w("putSummary was called but summary was not provided");
        }
    }

    private void uploadLogAssociations(List<LogAssociation> logAssociations) throws IOException {
        if (logAssociations.isEmpty()) {
            return;
        }
        // Obtain the ResultDB reporter from the configuration.
        ResultDBReporter rdbReporter = null;
        for (ITestInvocationListener listener : mConfiguration.getTestInvocationListeners()) {
            if (listener instanceof ResultDBReporter) {
                rdbReporter = (ResultDBReporter) listener;
                break;
            }
        }

        try (CloseableTraceScope ignored = new CloseableTraceScope("uploadLogAssociations")) {
            // UploadAssociations to AnTS. This call is blocking.
            mClient.uploadAssociations(logAssociations, mParallelLogUploadPool);
        }

        try (CloseableTraceScope ignored = new CloseableTraceScope("reportStoragePath")) {
            if (rdbReporter == null) {
                CLog.v("ResultDB reporter is null, skipping reporting storage path.");
                return;
            }
            if (rdbReporter.isDisabled()) {
                CLog.v("ResultDB reporter is disabled, skipping reporting storage path.");
                return;
            }
            // Temporary solution to sent AnTS test artifact storage path to ResultDB, as part of
            // the migration from AnTS to ResultDB (go/convergence-tr).
            // TODO(beining): Remove this method once we can properly migrate AnTS artifacts to
            // ResultDB.
            reportLogToResultDB(logAssociations, rdbReporter);
        }
    }

    public void reportLogToResultDB(
            List<LogAssociation> logAssociations, ResultDBReporter rdbReporter) throws IOException {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (LogAssociation association : logAssociations) {
            if (!association.isSupported()) {
                continue;
            }
            ResultDBLogReportTask oneTask =
                    new ResultDBLogReportTask(
                            association, mInvocation.getInvocationId(), mClient, rdbReporter);
            tasks.add(oneTask);
        }

        if (tasks.isEmpty()) {
            return;
        }
        if (mParallelLogUploadPool > 0) {
            int effectivePoolSize = Math.min(mParallelLogUploadPool, tasks.size());
            ParallelDeviceExecutor<Void> executor = new ParallelDeviceExecutor<>(effectivePoolSize);
            // The invokeAll method will block until all tasks are completed.
            executor.invokeAll(tasks, 0, TimeUnit.MILLISECONDS);
        } else {
            // Call sequentially with blocking call.
            for (Callable<Void> callable : tasks) {
                ((ResultDBLogReportTask) callable).call();
            }
        }
    }

    /**
     * A Callable class that encapsulates the logic for reporting a single log association to
     * ResultDB.
     */
    private class ResultDBLogReportTask implements Callable<Void> {
        private final LogAssociation mAssociation;
        private final ITestStorageClient mClient;
        private final ResultDBReporter rdbReporter;
        private final String mInvocationId;

        private ResultDBLogReportTask(
                LogAssociation association,
                String invocationId,
                ITestStorageClient mClient,
                ResultDBReporter rdbReporter) {
            this.mAssociation = association;
            this.mClient = mClient;
            this.rdbReporter = rdbReporter;
            this.mInvocationId = invocationId;
        }

        @Override
        public Void call() throws IOException {
            CLog.v(
                    "Reporting log association to ResultDB %s name: %s:",
                    mAssociation, mAssociation.getResourceId());

            BuildArtifactMetadata artifact =
                    mClient.getTestArtifact(mInvocationId, mAssociation.getResourceId());
            String storagePath = artifact.getStoragePath();
            rdbReporter.uploadStoragePath(
                    mAssociation.getResourceId(), storagePath, mAssociation.getLog());
            return null;
        }
    }

    @VisibleForTesting
    long currentTimestamp() {
        return System.currentTimeMillis();
    }

    @VisibleForTesting
    String getSpongeV2InvocationId() {
        return mSpongeInvocationId;
    }

    @VisibleForTesting
    void setMetricFileUtil(MetricFileUtil fileUtil) {
        mMetricFileUtil = fileUtil;
    }

    protected String getVersion() {
        String version = VersionParser.fetchVersion();
        if (version == null) {
            return "";
        }
        return version;
    }

    private AconfigFlags addOrUpdateFlagOverrides(AconfigFlags currentFlags, String flagOverrides) {
        if (flagOverrides == null || flagOverrides.isEmpty()) {
            return currentFlags;
        }

        List<String> flagStrings =
                Splitter.on(FLAG_SEPARATOR)
                        .trimResults()
                        .omitEmptyStrings()
                        .splitToList(flagOverrides);
        if (flagStrings.isEmpty()) {
            return currentFlags;
        }

        Map<String, AconfigFlag> allFlags = new LinkedHashMap<>();
        if (currentFlags == null) {
            currentFlags = new AconfigFlags();
        }
        List<AconfigFlag> flagList = currentFlags.getAconfigFlags();
        if (flagList == null) {
            flagList = Collections.emptyList();
        }
        for (AconfigFlag f : flagList) {
            allFlags.put(getFlagKey(f), f);
        }

        for (String flagString : flagStrings) {
            Optional<AconfigFlag> override = parseFlag(flagString);
            if (override.isEmpty()) {
                continue;
            }
            if (!override.get().equals(allFlags.get(getFlagKey(override.get())))) {
                allFlags.put(getFlagKey(override.get()), override.get());
            }
        }
        if (allFlags.isEmpty()) {
            return null;
        }
        AconfigFlags newFlags = new AconfigFlags();
        newFlags.setAconfigFlags(new ArrayList<>(allFlags.values()));
        return newFlags;
    }

    private static Optional<AconfigFlag> parseFlag(String flagOverride) {
        Matcher m = ACONFIG_FLAG_PATTERN.matcher(flagOverride);
        if (!m.find()) {
            return Optional.empty();
        }
        String namespace = m.group(1);
        String fullName = m.group(2);
        String valueString = m.group(3);
        Optional<String> parsedValue = parseFlagState(valueString);
        if (parsedValue.isEmpty()) {
            return Optional.empty();
        }

        List<String> parts = Splitter.on('.').splitToList(fullName);
        if (parts.size() < 2) {
            return Optional.empty();
        }
        String flagName = parts.get(parts.size() - 1);
        String flagPackage = String.join(".", parts.subList(0, parts.size() - 1));
        AconfigFlag flag =
                new AconfigFlag()
                        .setFlagNamespace(namespace)
                        .setFlagPackage(flagPackage)
                        .setFlagName(flagName)
                        .setState(parsedValue.get());
        return Optional.of(flag);
    }

    private static Optional<String> parseFlagState(String valueString) {
        return Optional.ofNullable(
                ImmutableMap.of("true", "enabled", "false", "disabled").get(valueString));
    }

    private static String getFlagKey(AconfigFlag flag) {
        return String.format(
                "namespace:%s package:%s name:%s",
                flag.getFlagNamespace(), flag.getFlagPackage(), flag.getFlagName());
    }

    @VisibleForTesting
    String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            CLog.w("Unable to get hostname");
        }
        return null;
    }

    @VisibleForTesting
    String getUser() {
        return System.getProperty("user.name");
    }

    @VisibleForTesting
    IGlobalConfiguration getGlobalConfig() {
        return GlobalConfiguration.getInstance();
    }

    /**
     * Extract the first line of the stack trace as the error message.
     *
     * <p>In most cases, this ends up being the exception + error message.
     */
    @VisibleForTesting
    String extractErrorMessage(String trace) {
        String firstLine = trace.split("[\\r\\n]+")[0];
        if (!firstLine.trim().isEmpty()) {
            return firstLine;
        }
        return null;
    }

    @Override
    public boolean supportGranularResults() {
        return mSupportGranularResults;
    }

    protected static class ListBuilder<T> {
        private List<T> entries;

        public ListBuilder(Collection<T> entries) {
            this.entries = new ArrayList<>();
            if (entries != null) {
                this.entries.addAll(entries);
            }
        }

        public ListBuilder<T> add(T p) {
            this.entries.add(p);
            return this;
        }

        public ListBuilder<T> addAll(Collection<? extends T> collection) {
            this.entries.addAll(collection);
            return this;
        }

        public List<T> build() {
            return this.entries;
        }

        public boolean contains(T e) {
            return entries.contains(e);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
        mMetricFileUtil.setConfiguration(configuration);
    }

    public IConfiguration getConfiguration() {
        return mConfiguration;
    }

    public void enableFilterProperty(boolean enableFilterProperty) {
        mEnableFilterProperty = enableFilterProperty;
    }

    private SkippedReason convertTfToAnTS(SkipReason reason, SkipReasonType type) {
        SkippedReason antsReason =
                new SkippedReason()
                        .setReasonMessage(reason.getReason())
                        .setBugId(reason.getBugId())
                        .setTrigger(reason.getTrigger())
                        .setReasonType(type.toString());
        return antsReason;
    }
}
