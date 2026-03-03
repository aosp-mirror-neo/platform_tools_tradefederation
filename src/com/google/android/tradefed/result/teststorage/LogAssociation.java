/*
 * Copyright (C) 2019 The Android Open Source Project
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
import com.android.tradefed.result.LogFile;

import com.google.android.tradefed.build.RemoteBuildInfo;
import com.google.api.client.util.Preconditions;
import com.google.api.services.androidbuildinternal.v3.model.Invocation;
import com.google.api.services.androidbuildinternal.v3.model.TestResult;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A class to represent a link between a log file and a test. An association represents that a
 * particular log file was created by a particular test.
 */
public class LogAssociation {
    // MUST update the logsaver pattern in com.android.tradefed.result.resultdb.ResultDBReporter,
    // if this pattern is changed.
    private static final Pattern LOGSAVER_PATTERN =
            Pattern.compile("https://android-build.googleplex.com/builds/I\\d+/(.+)");

    // Still keep the legacy pattern, because sandvoxes tf subprocesses
    // might not all be updated and still send legacy url.
    private static final Pattern LEGACY_LOGSAVER_PATTERN =
            Pattern.compile(
                    "https:\\/\\/android-build.googleplex.com\\/builds\\/.+\\/.+\\/.+\\/.+\\/tests\\/\\d+\\/(.+)");

    private final Invocation mInvocation;
    private final String mWorkUnitId;
    private final TestResult mTestResult;
    private final LogFile mLog;
    private String mResourceId;
    private final long mLegacyResultId;

    /**
     * Create an association for a log file. A log file can be associated to an individual test, or
     * to a test run or a module. If a log is associated with a test then a TestReseult object for
     * that test needs to be provided. Otherwise only provide invocation and work unit.
     *
     * @param invocation invocation to which the log file belongs to.
     * @param workUnitId work unit id representing the stage of the test that produced the log.
     * @param testResult if a log was produced by a test, testResult object that represents that
     *     test.
     * @param log log file that needs to be associated.
     */
    public LogAssociation(
            Invocation invocation,
            String workUnitId,
            TestResult testResult,
            long legacyResultId,
            LogFile log) {
        this.mInvocation = Preconditions.checkNotNull(invocation);
        this.mWorkUnitId = Preconditions.checkNotNull(workUnitId);
        this.mTestResult = testResult;
        this.mLog = Preconditions.checkNotNull(log);
        this.mLegacyResultId = legacyResultId;

        // We need to decode the resourceId because the backend expects that it is unencoded,
        // but TF gives as the url that is already encoded.
        mResourceId = "";
        if (mLog.getUrl() == null) {
            mResourceId = "";
        } else {
            Matcher m = LOGSAVER_PATTERN.matcher(mLog.getUrl());
            Matcher legacyMatcher = LEGACY_LOGSAVER_PATTERN.matcher(mLog.getUrl());
            if (m.find()) {
                try {
                    mResourceId = new URI(m.group(1)).getPath();
                } catch (URISyntaxException e) {
                    // ignore
                }
            } else if (legacyMatcher.find()) {
                try {
                    mResourceId = new URI(legacyMatcher.group(1)).getPath();
                } catch (URISyntaxException e) {
                    // ignore
                }
            } else {
                mResourceId = "";
            }
        }
        CLog.i("LogAssociation url:%s id:%s", mLog.getUrl(), mResourceId);
    }

    public String getInvocationId() {
        return mInvocation.getInvocationId();
    }

    public String getWorkUnitId() {
        return mWorkUnitId;
    }

    public String getTestResultId() {
        return mTestResult == null ? "" : mTestResult.getTestResultId();
    }

    public LogFile getLog() {
        return mLog;
    }

    /**
     * Returns true if log association is supported for this entry. Logs without resource id do not
     * have a valid association.
     */
    public boolean isSupported() {
        return !mResourceId.isEmpty();
    }

    /**
     * Returns resource id for the log which uniquely identifies the log on the remote server in
     * this particular invocation.
     */
    public String getResourceId() {
        return mResourceId;
    }

    /**
     * Returns the legacy result id used to upload the log. For backward compatibility the logs are
     * uploaded using V2 version of the api which requires legacy result id. After the log is
     * uploaded we create an associating using the v3 api, using invocation id and work unit id, but
     * we still want the logs to be accessible using the legacy tools which rely on the legacy
     * result id.
     */
    @Deprecated
    public long getLegacyResultId() {
        return this.mLegacyResultId;
    }

    /**
     * Returns the build type. This is required for v2 log upload, where build info is required to
     * upload the log. V3 does not need this because that information is already available in
     * invocation.
     */
    @Deprecated
    public String getBuildType() {
        return RemoteBuildInfo.parseBuildType(getBuildId()).toString();
    }

    /** Returns build id. This is required for v2 log upload which needs build info. */
    @Deprecated
    public String getBuildId() {
        return mInvocation.getPrimaryBuild().getBuildId();
    }

    /** Returns the name of the build target. This is required for v2 log upload. */
    @Deprecated
    public String getBuildTarget() {
        return mInvocation.getPrimaryBuild().getBuildTarget();
    }

    /** Returns attempt id which is required for v2 log upload. */
    @Deprecated
    public String getAttemptId() {
        return "0";
    }

    @Override
    public String toString() {
        return String.format(
                "LogAssociation{%s %s %s %s %d}",
                mInvocation,
                mWorkUnitId,
                mTestResult != null ? mTestResult.getTestResultId() : "(none)",
                mLog,
                mLegacyResultId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if (getClass() != o.getClass()) return false;
        LogAssociation other = (LogAssociation) o;
        if (!bothNullOrEqual(mInvocation, other.mInvocation)) return false;
        if (!bothNullOrEqual(mWorkUnitId, other.mWorkUnitId)) return false;
        if (!bothNullOrEqual(getTestResultId(), other.getTestResultId())) return false;
        if (!bothNullOrEqual(mLog, other.mLog)) return false;
        if (!bothNullOrEqual(mResourceId, other.mResourceId)) return false;
        if (mLegacyResultId != other.mLegacyResultId) return false;
        return true;
    }

    private boolean bothNullOrEqual(Object a, Object b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mInvocation, mWorkUnitId, getTestResultId(), mLog, mResourceId, mLegacyResultId);
    }
}
