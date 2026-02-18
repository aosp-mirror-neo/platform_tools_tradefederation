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
package com.google.android.tradefed.targetprep;

import static com.google.android.tradefed.util.androidbuildapi.AndroidBuildAPIHelper.BUILD_ENV_INVOCATION_CONTEXT_KEY;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.VersionedFile;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.DeviceFlashPreparer;
import com.android.tradefed.targetprep.IDeviceFlasher;
import com.android.tradefed.targetprep.ILabPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.util.StaticCompatibleBuildFlavorUtil;
import com.android.tradefed.util.StaticDeviceInfoUtil;

import com.google.android.tradefed.build.LaunchControlProvider;
import com.google.common.base.Strings;

import java.io.File;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/**
 * A specialization of {@link DeviceFlashPreparer} that supports custom steps needed to flash Google
 * devices.
 *
 * <p>It handles this by creating the appropriate {@link IDeviceFlasher} based on product type
 */
@OptionClass(alias = "gdevice-flash")
public class GoogleDeviceFlashPreparer extends DeviceFlashPreparer
        implements IInvocationContextReceiver, ILabPreparer {

    @Option(
            name = "force-product-type",
            description = "Blindly assume that the device has the specified product type")
    private String mForceProductType = null;

    @Option(
            name = "force-product-variant",
            description = "Blindly assume that the device has the specified variant")
    private String mForceProductVariant = null;

    @Option(name = "use-build-api", description = "Fetch resource from android build apiary.")
    private boolean mUseBuildApi = true;

    @Option(
            name = "download-cache-dir",
            description = "[deprecated] Config via host options instead.")
    private File mDownloadCacheDir = null;

    @Option(
            name = "skip-pre-flash-product-check",
            description = "Specify if device product type should be checked before flashing")
    private boolean mSkipPreFlashProductType = false;

    @Option(
            name = "enable-incremental-flashing-default-rollout",
            description = "Whether to apply the default rollout of incremental flashing.")
    private boolean mEnableIncrementalFlashingDefaultRollout = true;

    @Option(
            name = "attempt-switch-base-build",
            description = "If enabled, inspect the build to switch the flashing target.")
    private boolean mAttemptToSwitchBuild = false;

    @Option(
            name = "rollout-wipe-after-apply",
            description = "Rollout the wipe after applying update.")
    private boolean mRolloutWipeAfterApply = true;

    @Option(
            name = "rollout-new-flow",
            description = "Rollout the new flow of update for applying update.")
    private boolean mRolloutNewFlow = true;

    @Option(
            name = "rollout-incremental-forced",
            description =
                    "Enable incremental flashing regardless if device"
                            + " is specified as supported. This helps to test new devices.")
    private boolean mRolloutIncrementalForced = false;

    @Option(
            name = "rollout-bootloader-update-from-userspace",
            description = "Rollout the update of bootloader in userspace")
    private boolean mRolloutBootloaderFromUserspace = true;

    @Option(
            name = "enable-bootloader-update-from-userspace-rollout",
            description = "Rollout the update of bootloader in userspace")
    private boolean mEnableBootloaderUpdateRollout = true;

    @Option(
            name = "incremental-flashing-default-full-rollout",
            description =
                    "List of target:branch combinations for incremental flashing full rollout.")
    private Set<String> mIncrementalFlashingDefaultFullRollout = new LinkedHashSet<>();

    @Option(
            name = "incremental-flashing-default-full-branch-rollout",
            description = "List of branches for incremental flashing full rollout.")
    private static final Set<String> mIncrementalFlashingDefaultFullBranchRollout =
            new LinkedHashSet<>();

    @Option(
            name = "rollout-bootloader-update",
            description =
                    "List of target:branch combinations where bootloader will be updated in"
                            + " userspace.")
    private static final Set<String> mRolloutBootloaderUpdate = new LinkedHashSet<>();

    @Option(
            name = "alt-static-device-info-class",
            description = "Alternative class to use for fetching static device info.")
    private String mAltStaticDeviceInfoClass = null;

    @Option(
            name = "device-flasher-class",
            description =
                    "A IDeviceFlasher class that will be used to flash the device. When this option"
                        + " is set, device product type check before flashing will be skipped. ")
    private String mDeviceFlasherClass = null;

    @Option(
            name = "enable-flashstation",
            description =
                    "Whether to enable Flashstation as the default flasher for flashing devices.")
    private boolean mEnableFlashstation = false;

    @Option(name = "flashstation-script", description = "Path to the cl_flashstation script.")
    private File mFlashstationScript = new File("/tradefed/cl_flashstation");

    @Option(
            name = "flashstation-args",
            description = "A list of args that will be passed to the flashstation script.")
    private List<String> mFlashstationArgs = new LinkedList<>();

    private static final String FLASHSTATION_DEVICE_FLASHER_CLASS =
            "com.google.android.tradefed.targetprep.FlashstationDeviceFlasher";

    private String mBuildEnvironment = "prod";

    public GoogleDeviceFlashPreparer() {
        super();
        for (String branch : mIncrementalFlashingDefaultFullBranchRollout) {
            addAllowedBranchForTransitionInIncremental(branch);
        }
        if (!Strings.isNullOrEmpty(mAltStaticDeviceInfoClass)) {
            StaticDeviceInfoUtil.setStaticDeviceInfoClass(mAltStaticDeviceInfoClass);
        }
    }

    @Override
    public IBuildInfo getBuild(TestInformation testInfo) {
        IBuildInfo buildInfo = testInfo.getBuildInfo();
        if (mAttemptToSwitchBuild) {
            if (buildInfo.getFile("boot-test-" + BuildInfoFileKey.DEVICE_IMAGE.toString())
                    != null) {
                IDeviceBuildInfo deviceBuildInfo = new DeviceBuildInfo();
                deviceBuildInfo.setBuildBranch(buildInfo.getBuildBranch());
                deviceBuildInfo.setBuildFlavor(buildInfo.getBuildFlavor());

                List<Entry<String, File>> entries = new ArrayList<>();
                entries.add(
                        new SimpleEntry<String, File>(
                                BuildInfoFileKey.DEVICE_IMAGE.toString(),
                                buildInfo.getFile(
                                        "boot-test-" + BuildInfoFileKey.DEVICE_IMAGE.toString())));
                // Copy all the boot-test-* stuff
                for (String originalKey : buildInfo.getVersionedFileKeys()) {
                    if (!originalKey.startsWith("boot-test-")) {
                        continue;
                    }
                    String key = originalKey.replaceFirst("boot-test-", "");
                    if (BuildInfoFileKey.DEVICE_IMAGE.toString().equals(key)) {
                        continue;
                    }
                    VersionedFile vFile = buildInfo.getVersionedFile(originalKey);
                    if (vFile != null) {
                        entries.add(new SimpleEntry<String, File>(key, vFile.getFile()));
                    }
                }
                try {
                    LaunchControlProvider.handleExtraTargetDownloads(deviceBuildInfo, entries);
                } catch (BuildRetrievalError e) {
                    throw new RuntimeException(e);
                }
                return deviceBuildInfo;
            }
        }
        return super.getBuild(testInfo);
    }

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException, BuildError {
        if (!isIncrementalFlashingForceDisabled()) {
            // Evaluate incremental-flashing rollout
            if (mEnableIncrementalFlashingDefaultRollout) {
                IBuildInfo info = testInfo.getBuildInfo();
                String rolloutKey =
                        String.format("%s:%s", info.getBuildFlavor(), info.getBuildBranch());
                boolean shouldDeployIncrementalFlashing =
                        mIncrementalFlashingDefaultFullRollout.contains(rolloutKey)
                                || mIncrementalFlashingDefaultFullBranchRollout.contains(
                                        info.getBuildBranch())
                                || mRolloutIncrementalForced;
                if (shouldDeployIncrementalFlashing) {
                    setUseIncrementalFlashing(true);
                    setAllowCrossReleaseFlashing(true);
                    setAllowUnzipBaseline(true);
                    if (mIncrementalFlashingDefaultFullRollout.contains(rolloutKey)
                            || mIncrementalFlashingDefaultFullBranchRollout.contains(
                                    info.getBuildBranch())) {
                        CLog.d("Using apply-update for %s", rolloutKey);
                        setApplySnapshot(true);
                        if (mRolloutWipeAfterApply) {
                            setWipeAfterApplySnapshot(true);
                            if (mRolloutNewFlow) {
                                setUseIncrementalNewFlow(true);
                                if (mRolloutBootloaderFromUserspace
                                        || (mRolloutBootloaderUpdate.contains(rolloutKey)
                                                && mEnableBootloaderUpdateRollout)) {
                                    setUpdateBootloaderFromUserspace(true);
                                }
                            }
                        }
                    }
                }
            }
        }
        super.setUp(testInfo);
    }

    /** {@inheritDoc} */
    @Override
    public void setInvocationContext(IInvocationContext invocationContext) {
        List<String> buildEnvironments =
                invocationContext.getAttributes().get(BUILD_ENV_INVOCATION_CONTEXT_KEY);
        if (buildEnvironments != null && !buildEnvironments.isEmpty()) {
            mBuildEnvironment = buildEnvironments.get(0);
        }
    }

    /**
     * Create {@link IDeviceFlasher} to use
     *
     * @throws DeviceNotAvailableException
     */
    @Override
    protected IDeviceFlasher createFlasher(ITestDevice device) throws DeviceNotAvailableException {
        if (mEnableFlashstation) {
            try {
                Class<?> loadedClass = Class.forName(FLASHSTATION_DEVICE_FLASHER_CLASS);
                Object flashstationFlasher = loadedClass.getDeclaredConstructor().newInstance();

                Method setTestLoggerMethod =
                        loadedClass.getDeclaredMethod("setTestLogger", ITestLogger.class);
                setTestLoggerMethod.invoke(flashstationFlasher, getTestLogger());

                Method setAwaitDeviceTimeoutMethod =
                        loadedClass.getDeclaredMethod("setAwaitDeviceTimeout", Duration.class);
                setAwaitDeviceTimeoutMethod.invoke(
                        flashstationFlasher,
                        Duration.ofMillis(device.getOptions().getOnlineTimeout()));

                if (mFlashstationScript != null && mFlashstationScript.exists()) {
                    Method setFlashstationScriptMethod =
                            loadedClass.getDeclaredMethod("setFlashstationScript", File.class);
                    setFlashstationScriptMethod.invoke(flashstationFlasher, mFlashstationScript);
                }
                if (!mFlashstationArgs.isEmpty()) {
                    Method addFlashstationArgs =
                            loadedClass.getDeclaredMethod("addFlashstationArgs", List.class);
                    addFlashstationArgs.invoke(flashstationFlasher, mFlashstationArgs);
                }
                return (IDeviceFlasher) flashstationFlasher;
            } catch (Exception e) {
                CLog.d(
                        "Failed to load FlashstationDeviceFlasher class. Flashstation will not be"
                                + " used for flashing.");
                CLog.e(e);
            }
        }
        String productType = mForceProductType;
        if (productType == null) {
            productType = device.getProductType();
        }
        String productVariant = mForceProductVariant;
        if (productVariant == null) {
            productVariant = device.getProductVariant();
        }

        if (productVariant == null) {
            // StaticDeviceInfo.getFlasherClass() does not support null productVariant.
            productVariant = "";
        }

        Class<? extends IDeviceFlasher> flasherClass = null;
        if (!Strings.isNullOrEmpty(mDeviceFlasherClass)) {
            try {
                flasherClass = Class.forName(mDeviceFlasherClass).asSubclass(IDeviceFlasher.class);
            } catch (ClassNotFoundException e) {
                CLog.w(
                        "Flasher class %s was explicitly set for flashing, but could not be found"
                                + " in the classpath.",
                        mDeviceFlasherClass);
                CLog.e(e);
            } catch (ClassCastException e) {
                CLog.w("%s is not a IDeviceFlasher class.", mDeviceFlasherClass);
                CLog.e(e);
            }
        }
        if (flasherClass == null) {
            flasherClass = StaticDeviceInfoUtil.getFlasherClass(productType, productVariant);
        }
        if (flasherClass == null) {
            throw new DeviceNotAvailableException(
                    String.format(
                            "Unrecognized product '%s:%s' for device %s: Cannot determine how"
                                    + " to flash.",
                            productType, productVariant, device.getSerialNumber()),
                    device.getSerialNumber(),
                    InfraErrorIdentifier.MISMATCHED_BUILD_DEVICE);
        }

        IDeviceFlasher flasher = null;
        try {
            flasher = flasherClass.newInstance();
        } catch (InstantiationException e) {
            throw new DeviceNotAvailableException(
                    String.format(
                            "Failed to instantiate flasher for '%s' device %s: %s",
                            productType, device.getSerialNumber(), e.getMessage()),
                    device.getSerialNumber());
        } catch (IllegalAccessException e) {
            throw new DeviceNotAvailableException(
                    String.format(
                            "Failed to instantiate flasher for '%s' device %s: %s",
                            productType, device.getSerialNumber(), e.getMessage()),
                    device.getSerialNumber());
        }

        flasher.setFlashingResourcesRetriever(createFlashingResources(productType));
        return flasher;
    }

    @VisibleForTesting
    protected FlashingResourcesRetrieverCacheWrapper createFlashingResources(String productType) {
        return new FlashingResourcesRetrieverCacheWrapper(
                getHostOptions().getDownloadCacheDir(),
                new BuildApiFlashingResourcesRetriever(productType, mBuildEnvironment));
    }

    /** {@inheritDoc} */
    @Override
    protected void checkDeviceProductType(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws BuildError, DeviceNotAvailableException {
        // skip device product type check when flashers are set via @Options, since a mapping of
        // StaticDeviceInfo is not provided in such case.
        if (mSkipPreFlashProductType || !Strings.isNullOrEmpty(mDeviceFlasherClass)) {
            return;
        }
        String deviceTypeFromBuild = deviceBuild.getDeviceBuildFlavor();
        // handle a case where build flavor format is not really quite as expected.
        // <'device board'>-<user|userdebug>
        if (deviceTypeFromBuild.indexOf("-") != -1) {
            deviceTypeFromBuild =
                    deviceTypeFromBuild.substring(0, deviceTypeFromBuild.indexOf("-"));
        }
        String deviceProduct = device.getProductType(); // ro.product.board of the device
        String deviceVariant = device.getProductVariant(); // ro.product.device of the device
        String lcFlavor = StaticDeviceInfoUtil.getDefaultLcFlavor(deviceProduct, deviceVariant);

        if (lcFlavor == null) {
            throw new BuildError(
                    String.format(
                            "Unable to get the LC flavor for '%s:%s'",
                            deviceProduct, deviceVariant),
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.MISMATCHED_BUILD_DEVICE);
        }
        // Case insensitive comparison
        if (!deviceTypeFromBuild.equalsIgnoreCase(lcFlavor)) {
            List<String> possibleFlavors =
                    StaticCompatibleBuildFlavorUtil.getPossibleFlavors(deviceProduct);
            // We attempt to match compatible known flavors to the build one.
            if (possibleFlavors != null) {
                if (possibleFlavors.contains(deviceTypeFromBuild)) {
                    CLog.d(
                            "Build flavor: %s is compatible with product type: %s",
                            deviceTypeFromBuild, deviceProduct);
                    return;
                }
            }
            // Partial case insensitive matching since device product type is part of the device
            // product type
            // TODO: Remove this partial match to enforce full checking.
            if (deviceTypeFromBuild.toLowerCase().contains(lcFlavor.toLowerCase())) {
                CLog.w(
                        "Partial check of product type. Device allocated is a '%s:%s' while build "
                                + "is meant for a '%s'. Please update StaticCompatibleBuildFlavor.",
                        deviceProduct, deviceVariant, deviceTypeFromBuild);
                return;
            }

            throw new BuildError(
                    String.format(
                            "Device allocated is a '%s:%s' while build is "
                                    + "meant for a '%s' (configured lc_flavor: '%s')",
                            deviceProduct, deviceVariant, deviceTypeFromBuild, lcFlavor),
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.MISMATCHED_BUILD_DEVICE);
        }
    }

    public void setAltStaticDeviceInfoClass(String altStaticDeviceInfoClass) {
        StaticDeviceInfoUtil.setStaticDeviceInfoClass(altStaticDeviceInfoClass);
    }
}
