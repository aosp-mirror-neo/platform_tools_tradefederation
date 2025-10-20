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
package com.google.android.tradefed.build;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildAttributeKey;
import com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.util.Joiner;
import com.google.api.services.androidbuildinternal.v3.Androidbuildinternal;
import com.google.api.services.androidbuildinternal.v3.Androidbuildinternal.Build.Get;
import com.google.api.services.androidbuildinternal.v3.Androidbuildinternal.Buildartifact;
import com.google.api.services.androidbuildinternal.v3.model.Build;
import com.google.api.services.androidbuildinternal.v3.model.BuildArtifactListResponse;
import com.google.api.services.androidbuildinternal.v3.model.BuildArtifactMetadata;
import com.google.api.services.androidbuildinternal.v3.model.BuildListResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Helper class used for querying Android Build API for build artifacts. */
public class AndroidBuildArtifactHelper {

    // the build attempt to query for a build, hard coded to "latest" per b/24497675
    public static final String BUILD_ATTEMPT_LATEST = "latest";

    // constants used for latest build query on a branch
    private static final String BUILD_ATTEMPT_STATUS_COMPLETE = "complete";

    private static final String SORTING_TYPE_BUILDID = "buildId";

    private static final int HTTP_POLL_TIME_MS = 5 * 1000;

    protected static final String PLATFORM_LINUX = "linux";
    protected static final String PLATFORM_MAC = "mac";
    protected static final String PLATFORM_MAC_ARM64 = "mac_arm64";

    // A set of platforms that do not need the OS appended to their build flavor.
    private static final ImmutableSet<String> STANDARD_PLATFORMS =
        ImmutableSet.of(PLATFORM_LINUX, PLATFORM_MAC, PLATFORM_MAC_ARM64);

    private static final long PAGE_SIZE = 100;
    private Androidbuildinternal mClient;
    private int maxRetryAttempts = 3;
    private boolean mStrictMode = false;
    private boolean mThrowOnQueryFailure = false;

    // Keep a map with eviction of a few builds query to avoid requerying constantly
    private static final long MAX_SIZE = 100;
    private static final Map<String, RemoteBuildInfo> EVICTION_CACHE_MAP =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, RemoteBuildInfo>() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, RemoteBuildInfo> eldest) {
                            return size() > MAX_SIZE;
                        }
                    });

    /**
     * Constructor of AndroidBuildArtifactHelper.
     *
     * @param buildEnv the Build API environment to use.
     * @throws BuildRetrievalError
     */
    public AndroidBuildArtifactHelper(String buildEnv) throws BuildRetrievalError {
        this(buildEnv, false);
    }

    @VisibleForTesting
    public AndroidBuildArtifactHelper(String buildEnv, boolean strictMode)
            throws BuildRetrievalError {
        this(buildEnv, strictMode, false);
    }

    public AndroidBuildArtifactHelper(
            String buildEnv, boolean strictMode, boolean throwOnQueryFailure)
            throws BuildRetrievalError {
        try {
            mClient = AndroidBuildAPIHelper.getClient(buildEnv);
        } catch (GeneralSecurityException | IOException e) {
            throw new BuildRetrievalError(
                    "Unable to initialize build API client",
                    e,
                    InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
        }
        mStrictMode = strictMode;
        mThrowOnQueryFailure = throwOnQueryFailure;
    }

    /**
     * Query for build artifact info by branch and flavor: latest successful build will be used if
     * one exists, otherwise <code>null</code> will be returned
     *
     * @param branch
     * @param buildFlavor
     * @param os
     * @return
     */
    public RemoteBuildInfo queryForLatestBuild(String branch, String buildFlavor, String os)
            throws BuildRetrievalError {
        return queryForLatestBuild(branch, buildFlavor, os, null);
    }

    /**
     * Query for build artifact info by branch and flavor: latest successful build will be used if
     * one exists, otherwise <code>null</code> will be returned
     *
     * @param branch
     * @param buildFlavor
     * @param os
     * @param pattern
     * @return
     */
    public RemoteBuildInfo queryForLatestBuild(
            String branch, String buildFlavor, String os, String pattern)
            throws BuildRetrievalError {
        Build build = getLatestBuild(branch, buildFlavor, os);
        if (build != null) {
            return queryForBuild(branch, build.getBuildId(), buildFlavor, os, pattern, null);
        } else {
            return null;
        }
    }

    /**
     * Query for build artifact info by branch, build id and flavor.
     *
     * <p>Note that branch parameter here is optional: it's for the purpose of backwards
     * compatibility against old launch control query format. The actual querying is done by build
     * id and flavor, and if a branch param is provided but conflicts with the actual branch of
     * build, a <code>null</code> response will be returned instead even if a build can be found.
     *
     * @param branch
     * @param buildId
     * @param buildFlavor
     * @param os
     * @param pattern
     * @param exclusionPatterns Only apply if there is not positive pattern
     * @return
     */
    public RemoteBuildInfo queryForBuildById(
            String branch,
            String buildId,
            String buildFlavor,
            String os,
            String pattern,
            Set<String> exclusionPatterns)
            throws BuildRetrievalError {
        if (branch != null) {
            if (pattern == null) {
                RemoteBuildInfo cacheBuild =
                        EVICTION_CACHE_MAP.get(
                                String.format("%s:%s:%s:%s", branch, buildId, buildFlavor, os));
                if (cacheBuild != null) {
                    CLog.d("Using RemoteBuildInfo from cache: %s", cacheBuild);
                    return cacheBuild.clone();
                }
            }
            // do a branch check
            Build build = getBuild(branch, buildId, buildFlavor, os);
            if (build == null) {
                return null;
            }
            if (!"complete".equalsIgnoreCase(build.getBuildAttemptStatus())) {
                String error =
                        String.format(
                                "Build '%s' isn't completed it's in state '%s'",
                                buildId, build.getBuildAttemptStatus());
                CLog.e(error);
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.ABORT_CONTENT_ANALYSIS_REASON,
                        String.format("Build is in state: %s", build.getBuildAttemptStatus()));
                return throwOrReturn(error);
            }
            if (!branch.equals(build.getBranch())) {
                CLog.w(
                        "Build id %s found, but branch mismatch (expected: %s, actual: %s); "
                                + "reported as no build to test.",
                        buildId, branch, build.getBranch());
                return null;
            } else {
                RemoteBuildInfo returnValue =
                        queryForBuild(
                                build,
                                branch,
                                buildId,
                                buildFlavor,
                                os,
                                pattern,
                                exclusionPatterns);
                if (pattern == null) {
                    EVICTION_CACHE_MAP.put(
                            String.format("%s:%s:%s:%s", branch, buildId, buildFlavor, os),
                            returnValue);
                }
                return returnValue;
            }
        }
        return queryForBuild(branch, buildId, buildFlavor, os, pattern, exclusionPatterns);
    }

    @VisibleForTesting
    public RemoteBuildInfo queryForBuild(
            Build build, String branch, String buildId, String buildFlavor, String os)
            throws BuildRetrievalError {
        return queryForBuild(build, branch, buildId, buildFlavor, os, null, null);
    }

    /**
     * Performs the actual query to fetch build artifact info, via buildartifact.list API call.
     *
     * @param build an optional {@link Build} metadata instance, in case a prior build.get call has
     *     already been made to obtain build info described by the <code>buildId</code> and <code>
     *     buildFlavor</code>; when <code>null</code> a new build.get will be made; when not <code>
     *     null</code>, no additional checks consistent checks will be performed against <code>
     *     buildId</code> and <code>buildFlavor</code>
     * @param branch
     * @param buildId
     * @param buildFlavor
     * @param os
     * @param pattern
     * @param exclusionPatterns Only applies if there are no positive patterns
     * @return
     */
    @VisibleForTesting
    public RemoteBuildInfo queryForBuild(
            Build build,
            String branch,
            String buildId,
            String buildFlavor,
            String os,
            String pattern,
            Set<String> exclusionPatterns)
            throws BuildRetrievalError {
        if (os == null) {
            os = PLATFORM_LINUX;
        }
        if (build == null) {
            build = getBuild(branch, buildId, buildFlavor, os);
        }
        if (build == null) {
            // build does not exist
            throw new BuildRetrievalError(
                    String.format(
                            "Could not find build for build-id %s and build flavor '%s'",
                            buildId, buildFlavor),
                    InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
        }
        // to emulate the old launch control target name format
        String lcid = String.format("%s-%s-%s", build.getBranch(), os, buildFlavor);
        List<String> files =
                getBuildArtifactList(buildId, buildFlavor, os, pattern, exclusionPatterns);
        // prepend the file names with a "path" that consists of lc target name and build id, this
        // is eventually used as lc_cache folder path
        List<String> artifacts =
                files.stream()
                        .map(f -> ArrayUtil.join("/", lcid, buildId, f))
                        .collect(Collectors.toList());
        if (mStrictMode) {
            CLog.d("Strict mode enabled.");
        }
        // construct the remote build info, based on returned artifact list, but emulating the
        // original format that maps to a lc response
        RemoteBuildInfo info = new RemoteBuildInfo();
        info.addAttribute(BuildAttributeKey.BUILD_TARGET_NAME, lcid);
        info.addAttribute(BuildAttributeKey.BUILD_ID, buildId);
        info.addAttribute(BuildAttributeKey.BUILD_ALIAS, build.getReleaseCandidateName());
        info.addAttribute(
                BuildAttributeKey.BUILD_TYPE, RemoteBuildInfo.parseBuildType(buildId).toString());
        // hard-coding to "latest" per b/24497675, also in v2beta1, "attempt" does not really exist
        // in the output of build.get anymore
        info.addAttribute(BuildAttributeKey.BUILD_ATTEMPT_ID, BUILD_ATTEMPT_LATEST);
        info.addAttribute(BuildAttributeKey.BRANCH, build.getBranch());
        // put all files into a comma separated list
        info.addAttribute(BuildAttributeKey.FILES, ArrayUtil.join(",", artifacts));
        // filter out all artifacts ends in .apks
        info.addAttribute(
                BuildAttributeKey.APP_APKS,
                ArrayUtil.join(
                        ",",
                        artifacts.stream()
                                .filter(file -> file.endsWith(".apk"))
                                .collect(Collectors.toList())));
        // find tests zip file
        List<String> selectedArtifacts =
                artifacts.stream()
                        .filter(file -> file.contains("-tests-") && file.endsWith(".zip"))
                        .collect(Collectors.toList());
        Optional<String> opt = getOptional(selectedArtifacts);
        if (!opt.isEmpty()) {
            info.addAttribute(BuildAttributeKey.TESTS_ZIP, opt.get());
        }
        // find target file, it should be a zip
        selectedArtifacts =
                artifacts.stream()
                        .filter(file -> file.contains("-target_files-") && file.endsWith(".zip"))
                        .collect(Collectors.toList());
        opt = getOptional(selectedArtifacts);
        if (opt.isPresent()) {
            info.addAttribute(BuildAttributeKey.TARGET_FILES, opt.get());
        }
        // find updater; note: unlike legacy lc response, only device image files are covered here
        selectedArtifacts =
                artifacts.stream()
                        .filter(file -> file.contains("-img-") && file.endsWith(".zip"))
                        .collect(Collectors.toList());
        opt = getOptional(selectedArtifacts);
        if (opt.isPresent()) {
            info.addAttribute(BuildAttributeKey.DEVICE_IMAGE, opt.get());
        }
        // find userdata
        selectedArtifacts =
                artifacts.stream()
                        .filter(file -> file.endsWith("userdata.img"))
                        .collect(Collectors.toList());
        opt = getOptional(selectedArtifacts);
        if (opt.isPresent()) {
            info.addAttribute(BuildAttributeKey.USER_DATA, opt.get());
        }
        // find ota files, it's expected to be a zip
        selectedArtifacts =
                artifacts.stream()
                        .filter(
                                file ->
                                        (file.startsWith("ota_") || file.contains("-ota-"))
                                                && !file.contains("partial-ota-")
                                                && file.endsWith(".zip"))
                        .collect(Collectors.toList());
        opt = getOptional(selectedArtifacts);
        if (opt.isPresent()) {
            info.addAttribute(BuildAttributeKey.OTA_PACKAGE, opt.get());
        }
        // find emulator zip if they exist
        selectedArtifacts =
                artifacts.stream()
                        .filter(
                                file ->
                                        file.contains("sdk-repo-linux-emulator")
                                                && file.endsWith(".zip"))
                        .collect(Collectors.toList());
        opt = getOptional(selectedArtifacts);
        if (opt.isPresent()) {
            info.addAttribute(BuildAttributeKey.EMULATOR, opt.get());
        }
        // the code above differs from atp-manager.googleplex.com's handling of LC response in a
        // number of ways:
        // 1. no special handling for jumper* branches (project is long dead)
        // 2. no special handling for *sdk build targets: we don't currently support sdk & emulator
        //    testing and any new infra code should read from "files" field
        // 3. no special handling for "cts" build targets: migrated to test_suites_<abi> already
        // 4. only device image zip will be put into "updater" field, not sdk related file
        // ref:
        // http://cs/piper///depot/google3/wireless/android/test_tools/atp/manager/lc_handler.py
        return info;
    }

    protected RemoteBuildInfo queryForBuild(
            String branch,
            String buildId,
            String buildFlavor,
            String os,
            String pattern,
            Set<String> exclusionPatterns)
            throws BuildRetrievalError {
        return queryForBuild(null, branch, buildId, buildFlavor, os, pattern, exclusionPatterns);
    }

    protected List<String> getBuildArtifactList(
            String buildId,
            String buildFlavor,
            String os,
            String pattern,
            Set<String> exclusionPatterns)
            throws BuildRetrievalError {
        List<String> ret = new ArrayList<>();
        String target = getBuildFlavor(buildFlavor, os);
        for (int attempts = 0; ; attempts++) {
            long startList = System.currentTimeMillis();
            try (CloseableTraceScope ignored = new CloseableTraceScope("getBuildArtifactList")) {
                Buildartifact.List listOp =
                        mClient.buildartifact().list(buildId, target, BUILD_ATTEMPT_LATEST);
                if (Strings.isNullOrEmpty(pattern)) {
                    if (exclusionPatterns != null && !exclusionPatterns.isEmpty()) {
                        String excludePatterns = Joiner.on('|').join(exclusionPatterns);
                        String notMatch = "NOT_MATCH(" + excludePatterns + ")";
                        CLog.d("Using %s as list pattern.", notMatch);
                        listOp.setNameRegexp(notMatch);
                    }
                } else {
                    listOp.setNameRegexp(pattern);
                }
                listOp.setMaxResults(PAGE_SIZE);
                String token;
                do {
                    BuildArtifactListResponse resp = listOp.execute();
                    if (resp == null) {
                        CLog.w("Null response from buildartifact.list");
                        return null;
                    }
                    List<BuildArtifactMetadata> artifacts = resp.getArtifacts();
                    if (artifacts != null) {
                        for (BuildArtifactMetadata artifact : artifacts) {
                            ret.add(artifact.getName());
                        }
                    }
                    token = resp.getNextPageToken();
                    listOp.setPageToken(token);
                } while (token != null);
                break;
            } catch (IOException ioe) {
                if (isRetryable(ioe, attempts)) {
                    CLog.w("Retrying on 5xx error, attempt number : %d", attempts + 1);
                    escalatingSleep(attempts);
                } else {
                    throw new BuildRetrievalError(
                            String.format(
                                    "Failed to query buildartifact.list for target '%s' "
                                            + "and build-id '%s'",
                                    target, buildId),
                            ioe,
                            InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
                }
            } finally {
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.AB_LIST_API_TIME_PAIR,
                        startList,
                        System.currentTimeMillis());
            }
        }
        CLog.d("Build artifacts: %s", ret);
        return ret;
    }

    protected Build getLatestBuild(String branch, String buildFlavor, String os)
            throws BuildRetrievalError {
        for (int attempts = 0; ; attempts++) {
            try {
                String target = getBuildFlavor(buildFlavor, os);
                Androidbuildinternal.Build.List listOp =
                        mClient.build()
                                .list()
                                .setBranch(branch)
                                .setTarget(target)
                                .setMaxResults(1L)
                                .setBuildAttemptStatus(BUILD_ATTEMPT_STATUS_COMPLETE)
                                .setSuccessful(true)
                                .setBuildType(RemoteBuildInfo.BuildType.SUBMITTED.toString())
                                .setSortingType(SORTING_TYPE_BUILDID);
                BuildListResponse resp = listOp.execute();
                if (resp == null) {
                    String errMessage =
                            String.format(
                                    "Null response from build.get API for branch '%s' and target"
                                            + " '%s'",
                                    branch, target);
                    CLog.w(errMessage);
                    throw new BuildRetrievalError(
                            errMessage, InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
                }
                List<Build> builds = resp.getBuilds();
                if (builds == null || builds.isEmpty()) {
                    String errMessage =
                            String.format(
                                    "List of builds returned is null or empty for branch '%s' and "
                                            + "target '%s'",
                                    branch, target);
                    CLog.w(errMessage);
                    throw new BuildRetrievalError(
                            errMessage,
                            null,
                            InfraErrorIdentifier.ARTIFACT_NOT_FOUND,
                            createErrorBuild(branch, "-1", buildFlavor));
                }
                if (builds.size() != 1) {
                    CLog.w(
                            "Multiple builds returned when only one requested: num = %d",
                            builds.size());
                }
                Build build = builds.get(0);
                String buildId = build.getBuildId();
                CLog.i("LATEST build branch '%s' target '%s': %s", branch, target, buildId);
                return build;
            } catch (IOException ioe) {
                if (isRetryable(ioe, attempts)) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.METADATA_RETRY_COUNT, 1);
                    CLog.w("Retrying on 5xx error, attempt number : %d", attempts + 1);
                    escalatingSleep(attempts);
                } else {
                    throw new BuildRetrievalError(
                            "Failed to query buildartifact.list",
                            ioe,
                            InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR);
                }
            }
        }
    }

    protected Build getBuild(String branch, String buildId, String buildFlavor, String os)
            throws BuildRetrievalError {
        BuildRetrievalError exception = null;
        long start = System.currentTimeMillis();
        try {
            for (int attempts = 0; attempts < 3; attempts++) {
                HttpHeaders requestHeaders = null;
                try (CloseableTraceScope ignored = new CloseableTraceScope("build.get()")) {
                    Get getOp = mClient.build().get(buildId, getBuildFlavor(buildFlavor, os));
                    requestHeaders = getOp.getRequestHeaders();
                    return getOp.execute();
                } catch (IOException ioe) {
                    exception =
                            new BuildRetrievalError(
                                    String.format(
                                            "%s. request headers: %s",
                                            ioe.getMessage(), requestHeaders),
                                    ioe,
                                    InfraErrorIdentifier.ARTIFACT_DOWNLOAD_ERROR,
                                    createErrorBuild(branch, buildId, buildFlavor));
                }
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.METADATA_RETRY_COUNT, 1);
            }
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.AB_BUILD_GET_API_TIME, System.currentTimeMillis() - start);
        }
        throw exception;
    }

    /**
     * Check if exception needs to be retried.
     *
     * @param e - exception thrown
     * @param attempts - number of attempts
     */
    private boolean isRetryable(Exception e, int attempts) {
        if (attempts >= maxRetryAttempts || !(e instanceof HttpResponseException)) {
            return false;
        }
        int statusCode = ((HttpResponseException) e).getStatusCode();
        return statusCode >= 500 && statusCode < 600;
    }

    /**
     * Get the {@link IRunUtil} instance to use.
     *
     * <p>Exposed so unit tests can mock.
     */
    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /**
     * Logic to sleep between http request attempts.
     *
     * @param attempts
     */
    private void escalatingSleep(int attempts) {
        long sleepTime = Math.round(HTTP_POLL_TIME_MS * Math.pow(attempts, 3));
        getRunUtil().sleep(sleepTime);
    }

    /**
    * The original purpose of this was to support targets with a "fastboot" suffix. This logic is no
    * longer considered relevant and may be removed in the future to avoid confusion. We keep this
    * method for now to avoid adding an unexpected suffix to the build flavor. See (b/448820244)
    */
    protected static String getBuildFlavor(String buildFlavor, String os) {
        if (os == null || STANDARD_PLATFORMS.contains(os)) {
            return buildFlavor;
        }
        return String.format("%s_%s", buildFlavor, os);
    }

    private IBuildInfo createErrorBuild(String branch, String buildId, String buildFlavor) {
        BuildInfo errorBuild = new BuildInfo(buildId, buildFlavor);
        errorBuild.setBuildBranch(branch);
        errorBuild.setBuildFlavor(buildFlavor);
        return errorBuild;
    }

    private Optional<String> getOptional(List<String> values) throws BuildRetrievalError {
        if (values.isEmpty()) {
            return Optional.empty();
        } else if (mStrictMode) {
            if (values.size() > 1) {
                throw new BuildRetrievalError(
                        String.format(
                                "Build artifacts '%s' might create inconsistencies "
                                        + "with infra default downloading patterns. If you are "
                                        + "adding new artifacts, reach out to android-tradefed@",
                                values));
            }
        }
        return Optional.of(values.get(0));
    }

    private RemoteBuildInfo throwOrReturn(String message) throws BuildRetrievalError {
        if (mThrowOnQueryFailure) {
            throw new BuildRetrievalError(message, InfraErrorIdentifier.ARTIFACT_INVALID);
        }
        return null;
    }
}
