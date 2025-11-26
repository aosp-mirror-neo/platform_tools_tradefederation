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

package com.android.tradefed.targetprep;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.AaptParser;
import com.android.tradefed.util.AaptParser.AaptVersion;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Installs Dex Metadata files for specified APKs. */
@OptionClass(alias = "dm-extractor")
public class DexMetadataExtractor extends BaseTargetPreparer {

    @Option(name = "aapt-version", description = "The version of AAPT for APK parsing.")
    private AaptVersion mAaptVersion = AaptVersion.AAPT2;

    @VisibleForTesting static final String THROW_IF_NOT_FOUND_OPTION = "throw-if-not-found";

    @Option(
            name = THROW_IF_NOT_FOUND_OPTION,
            description = "Throw exception if the specified file is not found.")
    private boolean mThrowIfNoFile = true;

    @VisibleForTesting static final String REINSTALL_EXISTING_APP_OPTION = "reinstall-existing-app";

    @Option(name = REINSTALL_EXISTING_APP_OPTION, description = "Reinstall existing app.")
    private boolean mReinstallExistingApp = true;

    @VisibleForTesting
    static final String ALLOW_VERSION_CODE_DOWNGRADE_OPTION = "allow-version-code-downgrade";

    @Option(
            name = ALLOW_VERSION_CODE_DOWNGRADE_OPTION,
            description = "Allow APK version code downgrade.")
    private boolean mAllowVersionCodeDowngrade = true;

    @VisibleForTesting static final String CHECK_MIN_SDK_OPTION = "check-min-sdk";

    @Option(
            name = CHECK_MIN_SDK_OPTION,
            description =
                    "check app's min sdk prior to install and skip if device api level is too low.")
    private boolean mCheckMinSdk = false;

    @Option(
            name = "install-multiple-apks",
            description = "Install multiple APKs with a dex metadata file.")
    protected List<File> mDoInstallMultipleApks = new ArrayList<>();

    private Integer mUserId = null;

    private Set<String> mPackagesInstalled = new HashSet<>();
    private TestInformation mTestInfo;
    private IRunUtil mRunUtil;

    /** Sets the version of AAPT for APK parsing. */
    public void setAaptVersion(AaptVersion aaptVersion) {
        mAaptVersion = aaptVersion;
    }

    /**
     * If the apk should be installed for a particular user, sets the id of the user to install for.
     */
    public void setUserId(int userId) {
        mUserId = userId;
    }

    /** Helper to parse an apk file with aapt. */
    @VisibleForTesting
    AaptParser doAaptParse(File apkFile) {
        return AaptParser.parse(apkFile, mAaptVersion);
    }

    /** Constructor for DexMetadataExtractor. */
    public DexMetadataExtractor() {
        this(RunUtil.getDefault());
    }

    @VisibleForTesting
    public DexMetadataExtractor(IRunUtil runUtil) {
        this.mRunUtil = runUtil;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Extracts Dex Metadata files from the provided APKs using profgen and installs them
     * alongside the APKs.
     *
     * @throws TargetSetupError, BuildError, DeviceNotAvailableException
     */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        mTestInfo = testInfo;
        if (mDoInstallMultipleApks.isEmpty()) {
            CLog.i("No apks to install, skipping");
            return;
        }

        File profgen = testInfo.getBuildInfo().getFile("profgen");

        if (profgen == null) {
            throw new TargetSetupError(
                    String.format(
                            "install-multiple-apks option selected, "
                                    + "but 'profgen' build artifact was not included."));
        }

        if (!profgen.canRead()) {
            throw new TargetSetupError(String.format("profgen build artifact cannot be read."));
        }

        CommandResult chmodResult =
                mRunUtil.runTimedCmd(
                        testInfo.getDevice().getOptions().getAdbCommandTimeout(),
                        "chmod",
                        "u+x",
                        profgen.getPath());
        if (!chmodResult.getStatus().equals(CommandStatus.SUCCESS)) {
            throw new TargetSetupError(
                    String.format("Failed to run chmod on profgen: %s", chmodResult));
        }

        CLog.i("Installing test apks with associated dex metadata payload");

        for (File testFileName : mDoInstallMultipleApks) {
            File localApk = resolveApkFile(testInfo, testFileName);

            if (localApk == null) {
                continue;
            }

            String localApkName = localApk.getPath();
            String localDmName = localApkName.replaceAll("\\.apk$", ".dm");

            List<String> profgenCommand =
                    new ArrayList<>(
                            List.of(
                                    "java",
                                    "-cp",
                                    profgen.getPath(),
                                    "com.android.tools.profgen.cli.MainKt",
                                    "extractProfile",
                                    "--apk",
                                    localApkName,
                                    "--output-dex-metadata",
                                    localDmName,
                                    "--profile-format",
                                    "v0_1_5_s"));

            CommandResult profgenResult =
                    mRunUtil.runTimedCmd(
                            testInfo.getDevice().getOptions().getAdbCommandTimeout(),
                            profgenCommand.toArray(String[]::new));
            if (!profgenResult.getStatus().equals(CommandStatus.SUCCESS)) {
                if (mThrowIfNoFile) {
                    throw new TargetSetupError(
                            String.format(
                                    "Failed to extract dex metadata profile from %s: %s",
                                    testFileName, profgenResult));
                }
                CLog.i(
                        String.format(
                                "Failed to extract dex metadata profile from %s: %s",
                                testFileName, profgenResult));
                continue;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("install-multiple");
            if (mReinstallExistingApp) {
                cmd.add("-r");
            }
            if (mAllowVersionCodeDowngrade) {
                cmd.add("-d");
            }
            if (mUserId != null) {
                cmd.add("--user");
                cmd.add(String.valueOf(mUserId));
            }
            cmd.add(localApkName);
            cmd.add(localDmName);

            CommandResult installMultipleResult =
                    testInfo.getDevice().executeAdbV2Command(cmd.toArray(String[]::new));
            if (!installMultipleResult.getStatus().equals(CommandStatus.SUCCESS)) {
                if (mThrowIfNoFile) {
                    throw new TargetSetupError(
                            String.format(
                                    "Failed to install %s dex metadata and apk files: %s",
                                    testFileName, installMultipleResult));
                }
                CLog.i(
                        "Failed to install %s dex metadata and apk files: %s",
                        testFileName, installMultipleResult);
            } else {
                mPackagesInstalled.add(parsePackageName(localApk));
            }

            CLog.i("Successfully installed %s with associated dex metadata payload", localApkName);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (!(e instanceof DeviceNotAvailableException)) {
            for (String packageName : mPackagesInstalled) {
                try {
                    uninstallPackage(getDevice(), packageName);
                } catch (TargetSetupError tse) {
                    CLog.e(tse);
                }
            }
        }
    }

    /**
     * Returns the device that the preparer should apply to.
     *
     * @throws TargetSetupError
     */
    public ITestDevice getDevice() throws TargetSetupError {
        return mTestInfo.getDevice();
    }

    /** Attempt to remove the package from the device. */
    private void uninstallPackage(ITestDevice device, String packageName)
            throws DeviceNotAvailableException {
        List<String> cmd = new ArrayList<>();
        cmd.add("uninstall");
        if (mUserId != null) {
            cmd.add("--user");
            cmd.add(String.valueOf(mUserId));
        }
        cmd.add(packageName);

        CommandResult uninstallPackageResult =
                device.executeAdbV2Command(cmd.toArray(String[]::new));

        if (!uninstallPackageResult.getStatus().equals(CommandStatus.SUCCESS)) {
            CLog.w(
                    String.format(
                            "error uninstalling package '%s': %s",
                            packageName, uninstallPackageResult));
        }
    }

    protected File getLocalPathForFilename(TestInformation testInfo, String apkFileName)
            throws TargetSetupError {
        File apkFile = null;
        try {
            apkFile = testInfo.getDependencyFile(apkFileName, true);
        } catch (FileNotFoundException e) {
            throw new TargetSetupError(
                    String.format("%s not found", apkFileName),
                    e,
                    testInfo.getDevice().getDeviceDescriptor(),
                    InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
        }
        return apkFile;
    }

    /** Helper to resolve an apk to its File. */
    @VisibleForTesting
    protected File resolveApkFile(TestInformation testInfo, File apkFile)
            throws TargetSetupError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        File testAppFile = null;
        if (apkFile.isAbsolute()) {
            testAppFile = apkFile;
        }
        if (testAppFile == null) {
            testAppFile = getLocalPathForFilename(testInfo, apkFile.getName());
        }
        if (testAppFile == null) {
            if (mThrowIfNoFile) {
                throw new TargetSetupError(
                        String.format("Test app %s was not found.", apkFile.getName()),
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
            }
            CLog.d("Test app %s was not found.", apkFile.getName());
            return null;
        }
        if (!testAppFile.canRead()) {
            if (mThrowIfNoFile) {
                throw new TargetSetupError(
                        String.format("Could not read file %s.", testAppFile.toString()),
                        device.getDeviceDescriptor());
            }
            CLog.d("Could not read file %s.", testAppFile.toString());
            return null;
        }

        if (mCheckMinSdk) {
            AaptParser aaptParser = doAaptParse(testAppFile);
            if (aaptParser == null) {
                throw new TargetSetupError(
                        String.format(
                                "Failed to extract info from `%s` using "
                                        + (mAaptVersion == AaptVersion.AAPT ? "aapt" : "aapt2"),
                                testAppFile.getAbsoluteFile().getName()),
                        device.getDeviceDescriptor());
            }
            if (device.getApiLevel() < aaptParser.getSdkVersion()) {
                CLog.w(
                        "Skipping installing apk %s on device %s because "
                                + "SDK level require is %d, but device SDK level is %d",
                        apkFile.toString(),
                        device.getSerialNumber(),
                        aaptParser.getSdkVersion(),
                        device.getApiLevel());
                return null;
            }
            return testAppFile;
        }
        return testAppFile;
    }

    /** Get the package name from the test app. */
    protected String parsePackageName(File testAppFile) throws TargetSetupError {
        AaptParser parser = AaptParser.parse(testAppFile, mAaptVersion);
        if (parser == null) {
            throw new TargetSetupError(
                    String.format(
                            "AaptParser failed for file %s. The APK won't be installed",
                            testAppFile.getName()),
                    null,
                    null,
                    false, // Not device side error, doesn't need descriptor
                    DeviceErrorIdentifier.AAPT_PARSER_FAILED);
        }
        return parser.getPackageName();
    }
}
