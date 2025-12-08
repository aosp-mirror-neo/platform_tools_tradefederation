/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tradefed.util.avd;

import static com.android.tradefed.util.avd.HostOrchestratorClient.CreateCvdRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.CreateSnapshotRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.Cvd;
import static com.android.tradefed.util.avd.HostOrchestratorClient.DisplayAddRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.DisplayAddResponse;
import static com.android.tradefed.util.avd.HostOrchestratorClient.DisplayListResponse;
import static com.android.tradefed.util.avd.HostOrchestratorClient.DisplayScreenshotResponse;
import static com.android.tradefed.util.avd.HostOrchestratorClient.ErrorResponseException;
import static com.android.tradefed.util.avd.HostOrchestratorClient.HoHttpClient;
import static com.android.tradefed.util.avd.HostOrchestratorClient.IHoHttpClient;
import static com.android.tradefed.util.avd.HostOrchestratorClient.ListCvdsResponse;
import static com.android.tradefed.util.avd.HostOrchestratorClient.Operation;
import static com.android.tradefed.util.avd.HostOrchestratorClient.StartCVDRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildCreateBugreportRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildCreateCvdRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildCreateSnapshotRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildDeleteSnapshotRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildDisplayAddRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildDisplayListRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildDisplayRemoveRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildDisplayScreenshotRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildGetOperationRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildGetOperationResultRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildListCvdsRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildPowerwashRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildRemoveInstanceRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildStartCvdRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildStopInstanceRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.saveToFile;
import static com.android.tradefed.util.avd.HostOrchestratorClient.sendRequest;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandResultWith;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil2;
import com.android.tradefed.util.avd.OxygenClient.LHPTunnelMode;

import com.google.android_cuttlefish.base.cvd.cuttlefish.host.commands.cvd.cli.parser.CvdConfig.EnvironmentSpecification;
import com.google.common.annotations.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Utility to execute commands via Host Orchestrator on remote instances. */
public class HostOrchestratorUtil {
    public static final String URL_HOST_KERNEL_LOG = "_journal/entries?_TRANSPORT=kernel";
    public static final String URL_HO_LOG =
            "_journal/entries?_SYSTEMD_UNIT=cuttlefish-host_orchestrator.service";
    public static final String URL_OXYGEN_CONTAINER_LOG = "_journal/entries?CONTAINER_NAME=oxygen";
    private static final long CMD_TIMEOUT_MS = 5 * 6 * 1000 * 10; // 5 min
    private static final long WAIT_FOR_OPERATION_MS = 5 * 1000; // 5 sec
    private static final long WAIT_FOR_OPERATION_TIMEOUT_MS = 5 * 6 * 1000 * 10; // 5 min
    private static final String CVD_HOST_LOGZ = "cvd_hostlog_zip";
    private static final String URL_CVD_BUGREPORTS = "cvdbugreports/%s";
    private static final String UNSUPPORTED_API_RESPONSE = "404 page not found";

    private File mTunnelLog;
    private FileOutputStream mTunnelLogStream;
    private boolean mUseOxygenation = false;
    private boolean mUseCvdOxygen = false;

    // User name and key file to ssh to the host VM
    private File mSshPrivateKeyPath;
    private String mInstanceUser;

    // Oxygen instance name, host name, target region, accounting user, device ID, and other oxygen
    // arguments.
    private String mInstanceName;
    private String mHost;
    private String mOxygenationDeviceId;
    private String mTargetRegion;
    private String mAccountingUser;
    private Map<String, String> mExtraOxygenArgs;
    private OxygenClient mOxygenClient;
    private IHoHttpClient mHttpClient;
    private String mHOPortNumber = "2080";
    private Process mHOTunnel;
    // Intervals to wait before retrying a HO operation.
    private long mWaitForOperationMs = WAIT_FOR_OPERATION_MS;

    public HostOrchestratorUtil(
            boolean useOxygenation,
            Map<String, String> extraOxygenArgs,
            String instanceName,
            String host,
            String oxygenationDeviceId,
            String targetRegion,
            String accountingUser,
            OxygenClient oxygenClient) {
        this(
                useOxygenation,
                extraOxygenArgs,
                instanceName,
                host,
                oxygenationDeviceId,
                targetRegion,
                accountingUser,
                oxygenClient,
                new HoHttpClient());
    }

    public HostOrchestratorUtil(
            boolean useOxygenation,
            Map<String, String> extraOxygenArgs,
            String instanceName,
            String host,
            String oxygenationDeviceId,
            String targetRegion,
            String accountingUser,
            OxygenClient oxygenClient,
            IHoHttpClient httpClient) {
        mUseOxygenation = useOxygenation;
        mExtraOxygenArgs = extraOxygenArgs;
        mInstanceName = instanceName;
        mHost = host;
        mOxygenationDeviceId = oxygenationDeviceId;
        mTargetRegion = targetRegion;
        mAccountingUser = accountingUser;
        mOxygenClient = oxygenClient;
        mHttpClient = httpClient;
        if (mUseOxygenation) {
            mHOTunnel = createHostOrchestratorTunnel();
        }
    }

    /**
     * Create cuttlefish instance
     *
     * @return A {@link CommandResult}.
     */
    public CommandResult createCvd(EnvironmentSpecification specs) {
        try {
            if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
                CLog.e("oxygenation: invalid tunnel state");
                return buildHOFailureResult();
            }
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            CreateCvdRequest request = new CreateCvdRequest(specs);
            HttpRequest httpRequest = buildCreateCvdRequest(baseUrl, request);
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
        } catch (IOException | InterruptedException | ErrorResponseException e) {
            CLog.e("Failed to create cuttlefish instance via Host Orchestrator: %s", e);
            return new CommandResult(CommandStatus.EXCEPTION);
        }
        return new CommandResult(CommandStatus.SUCCESS);
    }

    /**
     * Download log files.
     *
     * @param logName the log name to use when reporting to the {@link ITestLogger}
     * @param urlPath url path indicating the log to download.
     */
    public File downloadLogFile(String logName, String urlPath) {
        File tempFile = null;
        try {
            tempFile = Files.createTempFile(logName, ".txt").toFile();
            if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
                CLog.e("Failed portforwarding Host Orchestrator tunnel.");
                FileUtil.deleteFile(tempFile);
                return null;
            }
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest request =
                    HttpRequest.newBuilder().uri(URI.create(baseUrl + "/" + urlPath)).build();
            saveToFile(mHttpClient, request, Paths.get(tempFile.getAbsolutePath()));
            return tempFile;
        } catch (IOException | InterruptedException | ErrorResponseException e) {
            CLog.e("Failed downloading logs with url path %s: %s", urlPath, e);
            FileUtil.deleteFile(tempFile);
            return null;
        }
    }

    /** Pull CF host logs via Host Orchestrator. */
    public File pullCvdHostLogs() {
        File cvdLogsDir = null;
        File cvdLogsZip = null;
        try {
            cvdLogsZip = Files.createTempFile(CVD_HOST_LOGZ, ".zip").toFile();
            if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
                CLog.e("Failed portforwarding Host Orchestrator CURL tunnel.");
                return null;
            }
            ListCvdsResponse listCvdsRes = listCvds();
            if (listCvdsRes.cvds.size() == 0) {
                CLog.e("No cvd found.");
                return null;
            }
            String cvdGroup = listCvdsRes.cvds.get(0).group;
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest = buildCreateBugreportRequest(baseUrl, cvdGroup);
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
            httpRequest = buildGetOperationResultRequest(baseUrl, operation.name);
            String bugreportId = sendRequest(mHttpClient, httpRequest, String.class);
            CommandResult curlRes =
                    curlCommandExecution(
                            mHOPortNumber,
                            "GET",
                            String.format(URL_CVD_BUGREPORTS, bugreportId),
                            true,
                            "--output",
                            cvdLogsZip.getAbsolutePath());
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e(
                        "Failed downloading cvd host logs via Host Orchestrator: %s",
                        curlRes.getStdout());
                return null;
            }
            cvdLogsDir = ZipUtil2.extractZipToTemp(cvdLogsZip, "cvd_logs");
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            CLog.e("Failed pulling cvd host logs via Host Orchestrator: %s", e);
        } finally {
            cvdLogsZip.delete();
        }
        return cvdLogsDir;
    }

    /**
     * Get CF running status via Host Orchestrator.
     *
     * @param maxWaitTime The max timeout expected to getting the CF running status.
     * @return True if device boot complete, false otherwise.
     */
    public boolean deviceBootCompleted(long maxWaitTime) {
        if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
            CLog.e("Failed portforwarding Host Orchestrator CURL tunnel.");
            return false;
        }
        long maxEndTime = System.currentTimeMillis() + maxWaitTime;
        int retryCount = 0;
        while (System.currentTimeMillis() < maxEndTime) {
            ListCvdsResponse listCvdsRes = null;
            try {
                listCvdsRes = listCvds();
            } catch (IOException | InterruptedException | ErrorResponseException e) {
                CLog.e("Failed listing cvds (attempt %d): %s", retryCount, e);
                // TODO(b/444465436): Reduce the retry count to 5 after the bug is fixed.
                // Retry at most 20 times for HO exception
                if (retryCount == 20) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricLogger.InvocationMetricKey.DEVICE_ERROR_SIGNATURES,
                            "ho_call_failed");
                    return false;
                }
                retryCount++;
                getRunUtil().sleep(mWaitForOperationMs);
                continue;
            }
            if (listCvdsRes.cvds.size() > 0 && listCvdsRes.cvds.get(0).status.equals("Running")) {
                if (retryCount > 0) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricLogger.InvocationMetricKey.HO_CALL_RETRY_COUNT,
                            retryCount);
                }
                return true;
            }
            getRunUtil().sleep(mWaitForOperationMs);
        }
        return false;
    }

    /**
     * Performs list cvds request against HO.
     *
     * @return A {@link ListCvdsResponse} response of list cvds request.
     */
    ListCvdsResponse listCvds() throws InterruptedException, IOException, ErrorResponseException {
        String baseUrl = getHOBaseUrl(mHOPortNumber);
        HttpRequest httpRequest = buildListCvdsRequest(baseUrl);
        return sendRequest(mHttpClient, httpRequest, ListCvdsResponse.class);
    }

    /**
     * Attempt to powerwash a Cuttlefish instance via Host Orchestrator.
     *
     * @return A {@link CommandResult} containing the status and logs.
     */
    public CommandResult powerwashGce() {
        try {
            if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
                return buildHOFailureResult();
            }
            ListCvdsResponse listCvdsRes = listCvds();
            if (listCvdsRes.cvds.size() == 0) {
                CLog.e("No cvd found.");
                return null;
            }
            Cvd cvd = listCvdsRes.cvds.get(0);
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest = buildPowerwashRequest(baseUrl, cvd.group, cvd.name);
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            CLog.e("Failed to powerwash the cuttlefish instance via Host Orchestrator: %s", e);
            return new CommandResult(CommandStatus.EXCEPTION);
        }
        return new CommandResult(CommandStatus.SUCCESS);
    }

    /**
     * Attempt to remote a Cuttlefish instance via Host Orchestrator.
     *
     * @return A {@link CommandResult} containing the status and logs.
     */
    public CommandResult removeInstance() {
        try {
            if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
                return buildHOFailureResult();
            }
            ListCvdsResponse listCvdsRes = listCvds();
            if (listCvdsRes.cvds.size() == 0) {
                CLog.e("No cvd found.");
                return null;
            }
            Cvd cvd = listCvdsRes.cvds.get(0);
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest = buildRemoveInstanceRequest(baseUrl, cvd.group, cvd.name);
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            CLog.e("Failed to remove the cuttlefish instance via Host Orchestrator: %s", e);
            return new CommandResult(CommandStatus.EXCEPTION);
        }
        return new CommandResult(CommandStatus.SUCCESS);
    }

    /**
     * Attempt to stop a Cuttlefish instance via Host Orchestrator.
     *
     * @return A {@link CommandResult} containing the status and logs.
     */
    public CommandResult stopInstance() {
        try {
            if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
                return buildHOFailureResult();
            }
            ListCvdsResponse listCvdsRes = listCvds();
            if (listCvdsRes.cvds.size() == 0) {
                CLog.e("No cvd found.");
                return null;
            }
            Cvd cvd = listCvdsRes.cvds.get(0);
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest = buildStopInstanceRequest(baseUrl, cvd.group, cvd.name);
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            CLog.e("Failed to stop the cuttlefish instance via Host Orchestrator: %s", e);
            return new CommandResult(CommandStatus.EXCEPTION);
        }
        return new CommandResult(CommandStatus.SUCCESS);
    }

    /**
     * Attempt to snapshot a Cuttlefish instance with the given id via Host Orchestrator.
     *
     * @param snapshotId the id that the snapshot file should be stored.
     * @return A {@link CommandResult} containing the status and logs.
     */
    public CommandResult snapshotInstance(String snapshotId) {
        try {
            if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
                return buildHOFailureResult();
            }
            ListCvdsResponse listCvdsRes = listCvds();
            if (listCvdsRes.cvds.size() == 0) {
                CLog.e("No cvd found.");
                return null;
            }
            Cvd cvd = listCvdsRes.cvds.get(0);
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest =
                    buildCreateSnapshotRequest(
                            baseUrl, cvd.group, cvd.name, new CreateSnapshotRequest(snapshotId));
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            CLog.e(
                    "Failed to snapshot the cuttlefish instance with given id: %s via Host"
                            + " Orchestrator: %s",
                    snapshotId, e);
            return new CommandResult(CommandStatus.EXCEPTION);
        }
        return new CommandResult(CommandStatus.SUCCESS);
    }

    /**
     * Attempt to start CVD with a given id via Host Orchestrator.
     *
     * @param snapshotId the id that the snapshot file should be stored.
     * @return A {@link CommandResult} containing the status and logs.
     */
    public CommandResult startCvd(String snapshotId) {
        try {
            if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
                return buildHOFailureResult();
            }
            ListCvdsResponse listCvdsRes = listCvds();
            if (listCvdsRes.cvds.size() == 0) {
                CLog.e("No cvd found.");
                return null;
            }
            Cvd cvd = listCvdsRes.cvds.get(0);
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest =
                    buildStartCvdRequest(
                            baseUrl, cvd.group, cvd.name, new StartCVDRequest(snapshotId));
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            String errorMsg =
                    String.format(
                            "Failed to start a cuttlefish instance with the given id: %s via Host"
                                    + " Orchestrator: %s",
                            snapshotId, e);
            CLog.e(errorMsg);
            CommandResult result = new CommandResult(CommandStatus.EXCEPTION);
            result.setStderr(errorMsg);
            return result;
        }
        return new CommandResult(CommandStatus.SUCCESS);
    }

    /** Attempt to delete snapshot of a Cuttlefish instance via Host Orchestrator. */
    public CommandResult deleteSnapshotGce(String snapshotId) {
        try {
            if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
                return buildHOFailureResult();
            }
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest = buildDeleteSnapshotRequest(baseUrl, snapshotId);
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            String errorMsg =
                    String.format(
                            "Failed to delete the snapshot instance %s via Host Orchestrator: %s",
                            snapshotId, e);
            CLog.e(errorMsg);
            CommandResult result = new CommandResult(CommandStatus.EXCEPTION);
            result.setStderr(errorMsg);
            return result;
        }
        return new CommandResult(CommandStatus.SUCCESS);
    }

    private CommandResult buildErrorCommandResult(String message) {
        CommandResult result = new CommandResult(CommandStatus.EXCEPTION);
        result.setStderr(message);
        return result;
    }

    private CommandResultWith<Cvd> getCvd() {
        if (mUseOxygenation && (mHOTunnel == null || !mHOTunnel.isAlive())) {
            return CommandResultWith.error(buildHOFailureResult());
        }

        Cvd cvd;
        try {
            ListCvdsResponse listCvdsResponse = listCvds();
            if (listCvdsResponse.cvds.size() != 1) {
                return CommandResultWith.errorString(
                        String.format(
                                "Expected exactly 1 cvd instance but found %d",
                                listCvdsResponse.cvds.size()));
            }
            cvd = listCvdsResponse.cvds.get(0);
            return CommandResultWith.success(cvd);
        } catch (InterruptedException | IOException | ErrorResponseException e) {
            return CommandResultWith.errorString(
                    String.format("Failed to list cvd instances: %s", e));
        }
    }

    /** Attempt to hotplug add a display to a Cuttlefish instance via Host Orchestrator. */
    public CommandResultWith<DisplayAddResponse> addDisplay(
            int width, int height, int dpi, int refreshRateHz) {
        CommandResultWith<Cvd> cvdCommandResult = getCvd();
        if (!cvdCommandResult.isSuccess()) {
            return CommandResultWith.errorString(
                    String.format(
                            "Failed to add display: failed to get cvd: %s",
                            cvdCommandResult.getCommandResult().getStderr()));
        }
        Cvd cvd = cvdCommandResult.getValue();

        String baseUrl = getHOBaseUrl(mHOPortNumber);
        HttpRequest httpRequest =
                buildDisplayAddRequest(
                        baseUrl,
                        cvd.group,
                        cvd.name,
                        new DisplayAddRequest(width, height, dpi, refreshRateHz));
        try {
            DisplayAddResponse response =
                    sendRequest(mHttpClient, httpRequest, DisplayAddResponse.class);
            return CommandResultWith.success(response);
        } catch (IOException | InterruptedException | ErrorResponseException e) {
            return CommandResultWith.error(
                    buildErrorCommandResult(
                            String.format(
                                    "Failed to add display: failed to add via host orchestrator:"
                                            + " %s",
                                    e)));
        }
    }

    /** Attempt to list the displays of a Cuttlefish instance via Host Orchestrator. */
    public CommandResultWith<DisplayListResponse> listDisplays() {
        CommandResultWith<Cvd> cvdCommandResult = getCvd();
        if (!cvdCommandResult.isSuccess()) {
            return CommandResultWith.errorString(
                    String.format(
                            "Failed to list displays: failed to get cvd: %s",
                            cvdCommandResult.getCommandResult().getStderr()));
        }
        Cvd cvd = cvdCommandResult.getValue();

        String baseUrl = getHOBaseUrl(mHOPortNumber);
        HttpRequest httpRequest = buildDisplayListRequest(baseUrl, cvd.group, cvd.name);
        try {
            DisplayListResponse response =
                    sendRequest(mHttpClient, httpRequest, DisplayListResponse.class);
            return CommandResultWith.success(response);
        } catch (IOException | InterruptedException | ErrorResponseException e) {
            return CommandResultWith.error(
                    buildErrorCommandResult(
                            String.format(
                                    "Failed to list displays: failed to list displays via host"
                                            + " orchestrator: %s",
                                    e)));
        }
    }

    /** Attempt to hotplug remove a display from a Cuttlefish instance via Host Orchestrator. */
    public CommandResult removeDisplay(int displayNumber) {
        CommandResultWith<Cvd> cvdCommandResult = getCvd();
        if (!cvdCommandResult.isSuccess()) {
            return buildErrorCommandResult(
                    String.format(
                            "Failed to remove display: failed to get cvd: %s",
                            cvdCommandResult.getCommandResult().getStderr()));
        }
        Cvd cvd = cvdCommandResult.getValue();

        String baseUrl = getHOBaseUrl(mHOPortNumber);
        HttpRequest httpRequest =
                buildDisplayRemoveRequest(baseUrl, cvd.group, cvd.name, displayNumber);
        try {
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
        } catch (IOException | InterruptedException | ErrorResponseException e) {
            return buildErrorCommandResult(
                    String.format(
                            "Failed to remove display: failed to remove displays via host"
                                    + " orchestrator: %s",
                            e));
        }
        return new CommandResult(CommandStatus.SUCCESS);
    }

    /** Attempt to screenshot a display from a Cuttlefish instance via Host Orchestrator. */
    public CommandResultWith<DisplayScreenshotResponse> screenshotDisplay(int displayNumber) {
        CommandResultWith<Cvd> cvdCommandResult = getCvd();
        if (!cvdCommandResult.isSuccess()) {
            return CommandResultWith.errorString(
                    String.format(
                            "Failed to screenshot display: failed to get cvd: %s",
                            cvdCommandResult.getCommandResult().getStderr()));
        }
        Cvd cvd = cvdCommandResult.getValue();

        String baseUrl = getHOBaseUrl(mHOPortNumber);
        HttpRequest httpRequest =
                buildDisplayScreenshotRequest(baseUrl, cvd.group, cvd.name, displayNumber);
        try {
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);

            httpRequest = buildGetOperationResultRequest(baseUrl, operation.name);
            DisplayScreenshotResponse screenshotResponse =
                    sendRequest(mHttpClient, httpRequest, DisplayScreenshotResponse.class);
            return CommandResultWith.success(screenshotResponse);
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            return CommandResultWith.error(
                    buildErrorCommandResult(
                            String.format(
                                    "Failed to screenshot display: failed to screenshot display via"
                                            + " host orchestrator: %s",
                                    e)));
        }
    }

    /**
     * Create Host Orchestrator Tunnel with an automatically created port number.
     *
     * @return A {@link Process} of the Host Orchestrator connection between CuttleFish and TF.
     */
    @VisibleForTesting
    Process createHostOrchestratorTunnel() {
        mHOPortNumber = Integer.toString(mOxygenClient.createServerSocket());
        if (mTunnelLog == null || !mTunnelLog.exists()) {
            try {
                mTunnelLog = FileUtil.createTempFile("host-orchestrator-connection", ".txt");
                mTunnelLogStream = new FileOutputStream(mTunnelLog, true);
            } catch (IOException e) {
                FileUtil.deleteFile(mTunnelLog);
                CLog.e(e);
            }
        }
        CLog.i("Portforwarding host orchestrator for oxygenation CF.");
        return mOxygenClient.createTunnelViaLHP(
                LHPTunnelMode.CURL,
                mHOPortNumber,
                mInstanceName,
                mHost,
                mTargetRegion,
                mAccountingUser,
                mOxygenationDeviceId,
                mExtraOxygenArgs,
                mTunnelLogStream);
    }

    /**
     * Execute a curl command via Host Orchestrator.
     *
     * @param portNumber The port number that Host Orchestrator communicates with.
     * @param method The HTTP Request containing GET, POST, PUT, DELETE, PATCH, etc...
     * @param api The API that Host Orchestrator supports.
     * @param commands The command to be executed.
     * @return A {@link CommandResult} containing the status and logs.
     */
    @VisibleForTesting
    CommandResult curlCommandExecution(
            String portNumber,
            String method,
            String api,
            boolean shouldDisplay,
            String... commands) {
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-0");
        cmd.add("-v");
        cmd.add("-X");
        cmd.add(method);
        cmd.add(getHOBaseUrl(portNumber) + "/"  + api);
        for (String cmdOption : commands) {
            cmd.add(cmdOption);
        }
        CommandResult commandRes =
                getRunUtil().runTimedCmd(CMD_TIMEOUT_MS, null, null, cmd.toArray(new String[0]));
        if (shouldDisplay) {
            CLog.logAndDisplay(
                    LogLevel.INFO,
                    "Executing Host Orchestrator curl command: %s, Stdout: %s, Stderr: %s, Status:"
                            + " %s",
                    cmd,
                    commandRes.getStdout(),
                    commandRes.getStderr(),
                    commandRes.getStatus());
        }
        if (commandRes.getStdout().contains(UNSUPPORTED_API_RESPONSE)) {
            commandRes.setStatus(CommandStatus.FAILED);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricLogger.InvocationMetricKey.UNSUPPORTED_HOST_ORCHESTRATOR_API,
                    api);
        }
        return commandRes;
    }

    /** Return the value by parsing the simple JSON content with a given keyword. */
    private String parseCvdContent(String content, String keyword) {
        String output = "";
        try {
            JSONObject object = new JSONObject(content);
            output = object.get(keyword).toString();
        } catch (JSONException e) {
            CLog.e(e);
        }
        return output;
    }

    /**
     * Execute long-run operations via Host Orchestrator. A certain HO APIs would take longer time
     * to complete, in order not to execute a long-run operations and wait for the output. This
     * method calls the operation, get the operation id, periodically do quick check the operation's
     * status util it's done, and return the result.
     *
     * @param portNumber The port number that Host Orchestrator communicates with.
     * @param method The HTTP Request containing GET, POST, PUT, DELETE, PATCH, etc...
     * @param request The HTTP request to be executed.
     * @param maxWaitTime The max timeout expected to execute the HTTP request.
     * @return A CommandResult containing the status and logs after running curl command.
     */
    @VisibleForTesting
    CommandResult cvdOperationExecution(
            IHoHttpClient client,
            String portNumber,
            String method,
            String request,
            long maxWaitTime)
            throws IOException, InterruptedException, ErrorResponseException {
        CommandResult commandRes = curlCommandExecution(portNumber, method, request, true);
        if (!CommandStatus.SUCCESS.equals(commandRes.getStatus())) {
            CLog.e("Failed running %s, error: %s", request, commandRes.getStdout());
            return commandRes;
        }
        String operationId = parseCvdContent(commandRes.getStdout(), "name");
        long maxEndTime = System.currentTimeMillis() + maxWaitTime;
        while (System.currentTimeMillis() < maxEndTime) {
            HttpRequest httpRequest =
                buildGetOperationRequest(getHOBaseUrl(portNumber), operationId);
            Operation op = sendRequest(client, httpRequest, Operation.class);
            if (op.done) {
                return commandRes;
            }
            getRunUtil().sleep(WAIT_FOR_OPERATION_MS);
        }
        CLog.e("Running long operation cvd request timedout!");
        // Return the last command result and change the status to TIMED_OUT.
        commandRes.setStatus(CommandStatus.TIMED_OUT);
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricLogger.InvocationMetricKey.CVD_LONG_OPERATION_TIMEOUT_API, request);
        return commandRes;
    }

    /**
     * Wait for operation to finish or timeout.
     *
     * @param client http client to perm
     * @param name Operation name.
     * @param maxWaitTime waiting time, if reached out, an execption will be thrown.
     */
    public void waitForOperation(
            IHoHttpClient client, String baseUrl, String name, long maxWaitTimeMs)
            throws IOException, InterruptedException, TimeoutException, ErrorResponseException {
        long maxEndTime = System.currentTimeMillis() + maxWaitTimeMs;
        while (System.currentTimeMillis() < maxEndTime) {
            HttpRequest httpRequest = buildGetOperationRequest(baseUrl, name);
            Operation op = sendRequest(client, httpRequest, Operation.class);
            if (op.done) {
                return;
            }
            getRunUtil().sleep(WAIT_FOR_OPERATION_MS);
        }
        CLog.e("Timeout waiting for operation: " + name);
        throw new TimeoutException("Operation wait timeout, operation name: " + name);
    }

    /** Get {@link IRunUtil} to use. Exposed for unit testing. */
    // TODO(dshi): Restore VisibleForTesting after the unittest is moved to the same package
    // (tradefed-avd-util)
    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /** Return the unsupported api response. Exposed for unit testing. */
    @VisibleForTesting
    String getUnsupportedHoResponse() {
        return UNSUPPORTED_API_RESPONSE;
    }

    /** Return the host orchestrator tunnel log file. */
    public File getTunnelLog() {
        return mTunnelLog;
    }

    /** Return the host orchestrator URL. */
    String getHOBaseUrl(String port) {
        String host = mUseOxygenation ? "127.0.0.1" : mHost;
        return String.format("http://%s:%s", host, port);
    }

    /** Return the host orchestrator base URL */
    public String getHOBaseUrl() {
        return getHOBaseUrl(mHOPortNumber);
    }

    /** Close the connection to the remote oxygenation device with a given {@link Process}. */
    public void closeTunnelConnection() {
        if (mUseOxygenation) {
            mOxygenClient.closeLHPConnection(mHOTunnel);
        }
    }

    private CommandResult buildHOFailureResult() {
        CommandResult curlRes = new CommandResult(CommandStatus.EXCEPTION);
        String msg = "Failed portforwarding Host Orchestrator tunnel.";
        CLog.e(msg);
        curlRes.setStderr(msg);
        return curlRes;
    }

    @VisibleForTesting
    void setWaitForOperationMs(long interval) {
        mWaitForOperationMs = interval;
    }
}
