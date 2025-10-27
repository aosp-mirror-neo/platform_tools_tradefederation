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

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.DeviceSetup;
import com.android.tradefed.targetprep.ILabPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.BinaryState;
import com.android.tradefed.util.BrightnessSetupConfigUtil;
import com.android.tradefed.util.MultiMap;

import com.google.android.tradefed.device.SettingsUtil;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * An extension of {@link DeviceSetup} that allows setup of additional Google specific properties.
 */
@OptionClass(alias = "google-device-setup")
public class GoogleDeviceSetup extends DeviceSetup implements ILabPreparer {

    // Screen
    @Option(
            name = "screen-brightness-nits",
            description =
                    "Set the screen brightness to the number of nits. Currently, the value "
                            + "200 is supported for about 30 different Nexus, Pixel, and Android "
                            + "One devices. All other values and devices will throw a "
                            + "TargetSetupException. This option will override screen-brightness "
                            + "option.")
    private Integer mScreenBrighnessNit = null;

    @Option(
            name = "screen-brightness-lux",
            description =
                    "Set the screen brightness to simulate the auto-brightness in a specific "
                            + "ambient light level environment. 100 lux is supported for Pixel "
                            + "2016 through 2018.")
    private Integer mScreenBrighnessLux = null;

    // Location
    private static final boolean DEFAULT_DISABLE_LOCATION_PROMPT = true;

    @Option(
            name = "disable-use-location-prompt",
            description =
                    "Disable the 'Use My location for google services' prompt. Useful to "
                            + "prevent the dialog from interfering with UI based tests.")
    private boolean mDisableUseLocationPrompt = DEFAULT_DISABLE_LOCATION_PROMPT;

    // true: am start -a com.google.android.gsf.action.SET_USE_LOCATION_FOR_SERVICES --ez disable
    // true

    @Option(name = "location-collection", description = "Turn location collection on or off")
    private BinaryState mLocationCollection = BinaryState.IGNORE;

    // ON:  GServices: location:collection_enabled 1
    // OFF: GServices: location:collection_enabled 0

    @Option(name = "location-opt-in", description = "Turn location opt in on or off")
    private BinaryState mLocationOptIn = BinaryState.IGNORE;

    // ON:  Google partner: network_location_opt_in 1
    //      Google partner: use_location_for_services 1
    // OFF: Google partner: network_location_opt_in 0
    //      Google partner: use_location_for_services 0

    // Cast
    @Option(name = "cast-broadcast", description = "Turn the cast broadcast on or off")
    private BinaryState mCastBroadcast = BinaryState.IGNORE;

    // ON:  GServices: gms:cast:mdns_device_scanner:is_enabled 1
    // OFF: GServices: gms:cast:mdns_device_scanner:is_enabled 0

    // Disable Live Caption
    @Option(name = "disable-captions", description = "Disable Live Caption")
    private boolean mDisableCaptions = false;

    // device_config put device_personalization_services Captions__disable_prod true

    // Disable AiAi Webref download
    @Option(name = "disable-superpacks-download", description = "Disable AiAi Webref download")
    private boolean mDisablesuperpacks_download = false;

    // device_config put device_personalization_services Overview__enable_superpacks_download false

    // App
    @Option(name = "disable-playstore", description = "Disable the Play Store app")
    private boolean mDisablePlaystore = false;

    // pm disable-user com.android.vending

    // MusicIQ
    @Option(name = "disable-musiciq", description = "Deprecated in favor of musiciq")
    private boolean mDisablemusiciq = false;

    // Hotword
    @Option(name = "disable-hotword", description = "Deprecated in favor of hotword")
    private boolean mDisablehotword = false;

    // MusicIQ
    @Option(name = "musiciq", description = "Turn the MusicIQ on or off")
    private BinaryState mMusicIQ = BinaryState.IGNORE;

    // ON:  <boolean name="ambient_music_aod_notification_switch" value="true" />
    // OFF: <boolean name="ambient_music_aod_notification_switch" value="false" />

    // MusicIQ_AS_App
    @Option(
            name = "musiciq-of-as",
            description =
                    "Turn the MusicIQ of com.google.android.as on or off, The new apk is "
                            + "Device Personalization Services")
    private BinaryState mMusicIQOfAs = BinaryState.IGNORE;

    // ON:  <boolean name="ambient_music_aod_notification_switch" value="true" />
    // OFF: <boolean name="ambient_music_aod_notification_switch" value="false" />

    // CHRE
    @Option(name = "disable-chre", description = "Disable CHRE Buffering")
    private boolean mDisableCHRE = false;

    // setprop ctl.stop vendor.chre
    // CHRE

    @Option(name = "disable-pixellogger", description = "Disable Pixel logger")
    private boolean mDisablePixelLogger = false;

    // pm disable com.android.pixellogger

    // Hotword
    @Option(name = "hotword", description = "Turn the Hotword Detection on or off")
    private BinaryState mHotword = BinaryState.IGNORE;

    // 0- Enroll
    // 1- Re-enroll
    // 2- Disenroll
    // adb shell am start -a  com.android.intent.action.MANAGE_VOICE_KEYPHRASES --ei
    // com.android.intent.extra.VOICE_KEYPHRASE_ACTION <0,1,2 > --es
    // com.android.intent.extra.VOICE_KEYPHRASE_HINT_TEXT "demo" --es
    // "com.android.intent.extra.VOICE_KEYPHRASE_LOCALE" "en-US"
    // com.android.hotwordenrollment.okgoogle/com.android.hotwordenrollment.okgoogle.EnrollmentActivity

    @Option(name = "disable-volta", description = "Disable the Volta app")
    private boolean mDisableVolta = false;

    // pm disable-user com.google.android.volta

    // Test harness
    @Option(
            name = "setting-cmd-apk-path",
            description = "file system path to the settings cmd apk.")
    private File mSettingCmdApk = null;

    @Option(
            name = "set-google-partner-setting",
            description =
                    "Change a Google partner setting. Option may be repeated and all "
                            + "key/value pairs will be set in order.")
    // Use a Multimap since it is possible for a setting to have multiple values for the same key
    private MultiMap<String, String> mGooglePartnerSettings = new MultiMap<>();

    @Option(
            name = "set-gservice-setting",
            description =
                    "Override a GService setting. Option may be repeated and all key/value "
                            + "pairs will be set in order.")
    // Use a Multimap since it is possible for a setting to have multiple values for the same key
    protected MultiMap<String, String> mGServiceSettings = new MultiMap<>();

    // Deprecated Options
    @Option(
            name = "enable-airplane-mode",
            description = "deprecated, use option airplane-mode. Turn on airplane mode.")
    @Deprecated
    private boolean mDeprecatedAirplaneMode = false;

    @Option(
            name = "disable-use-location",
            description =
                    "deprecated, use option disable-use-location-prompt. Disable the 'Use my"
                        + " location for google services' prompt. Useful to prevent the dialog from"
                        + " interfering with UI based tests.")
    @Deprecated
    private boolean mDeprecatedDisableUseLocation = true;

    @Option(
            name = "setting",
            description =
                    "deprecated, use option set-system-setting. Change a system "
                            + "(non-secure) setting. Format: --setting key value. May be repeated.")
    @Deprecated
    private Map<String, String> mDeprecatedSettings = new HashMap<String, String>();

    @Option(
            name = "secure-setting",
            description =
                    "deprecated, use option set-secure-setting. Change a secure setting. "
                            + "Format: --secure-setting key value. May be repeated.")
    @Deprecated
    private Map<String, String> mDeprecatedSecureSettings = new HashMap<String, String>();

    @Option(
            name = "google-partner-setting",
            description =
                    "deprecated, use option set-google-partner-setting. Change a google "
                            + "partner setting. Format: --setting key value. May be repeated.")
    @Deprecated
    private Map<String, String> mDeprecatedPartnerSettings = new HashMap<String, String>();

    @Option(
            name = "global-setting",
            description =
                    "deprecated, use option set-global-setting. Change a global setting. "
                            + "Format: --setting key value. May be repeated.")
    @Deprecated
    private Map<String, String> mDeprecatedGlobalSettings = new HashMap<String, String>();

    @Option(
            name = "gservices-setting",
            description =
                    "deprecated, use option set-gservice-setting. Change a GServices "
                            + "setting. Format: --gservices-settings key value. May be repeated.")
    @Deprecated
    private Map<String, String> mDeprecatedGServicesSettings = new HashMap<String, String>();

    @Option(
            name = "default-data-call-subscription",
            description =
                    "deprecated, use option default-sim-data. Set the default data call"
                        + " subscription for a multi-SIM device.  Should be 1 for SIM slot 1 or 2"
                        + " for SIM slot 2 or unset for a single SIM device.")
    @Deprecated
    private Integer mDeprecatedDefaultDataCallSubscription = null;

    @Option(
            name = "default-voice-call-subscription",
            description =
                    "deprecated, use option default-sim-voice. Set the default voice call"
                        + " subscription for a multi-SIM device.  Should be 1 for SIM slot 1 or 2"
                        + " for SIM slot 2 or unset for a single SIM device.")
    @Deprecated
    private Integer mDeprecatedDefaultVoiceCallSubscription = null;

    @Option(
            name = "default-sms-subscription",
            description =
                    "deprecated, use option default-sim-sms. Set the default SMS subscription for a"
                        + " multi-SIM device.  Should be 1 for SIM slot 1 or 2 for SIM slot 2 or"
                        + " unset for a single SIM device.")
    @Deprecated
    private Integer mDeprecatedDefaultSmsSubscription = null;

    @Option(
            name = "force-skip-change-settings",
            description =
                    "deprecated, use option force-skip-settings. Force setup not to change any"
                        + " settings using settings cmd apk. All settings options and installing"
                        + " settings cmd apk will be ignored.")
    @Deprecated
    private boolean mDeprecatedForceSkipChangeSettings = false;

    @Option(name = "location-mode", description = "Turn location on or off")
    private BinaryState mLocationMode = BinaryState.IGNORE;

    // ON:  settings put secure location_mode 3
    // OFF: settings put secure location_mode 0

    /** {@inheritDoc} */
    @Override
    public void processDeprecatedOptions(ITestDevice device) throws TargetSetupError {
        super.processDeprecatedOptions(device);

        if (mDeprecatedAirplaneMode /* default false */) {
            if (!BinaryState.IGNORE.equals(mAirplaneMode)) {
                throw new TargetSetupError(
                        "Deprecated option enable-airplane-mode conflicts "
                                + "with option airplane-mode",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mAirplaneMode = BinaryState.ON;
        }

        if (mDeprecatedDisableUseLocation != DEFAULT_DISABLE_LOCATION_PROMPT) {
            if (mDisableUseLocationPrompt != DEFAULT_DISABLE_LOCATION_PROMPT) {
                throw new TargetSetupError(
                        "Deprecated option enable-airplane-mode conflicts "
                                + "with option airplane-mode",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mDisableUseLocationPrompt = mDeprecatedDisableUseLocation;
        }

        if (!mDeprecatedSettings.isEmpty()) {
            if (!mSystemSettings.isEmpty()) {
                throw new TargetSetupError(
                        "Deprecated option setting conflicts with option " + "set-system-setting",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mSystemSettings.putAll(mDeprecatedSettings);
        }

        if (!mDeprecatedSecureSettings.isEmpty()) {
            if (!mSecureSettings.isEmpty()) {
                throw new TargetSetupError(
                        "Deprecated option secure-setting conflicts with "
                                + "option set-secure-setting",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mSecureSettings.putAll(mDeprecatedSecureSettings);
        }

        if (!mDeprecatedPartnerSettings.isEmpty()) {
            if (!mGooglePartnerSettings.isEmpty()) {
                throw new TargetSetupError(
                        "Deprecated option google-partner-setting conflicts "
                                + "with option set-google-partner-setting",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mGooglePartnerSettings.putAll(mDeprecatedPartnerSettings);
        }

        if (!mDeprecatedGlobalSettings.isEmpty()) {
            if (!mGlobalSettings.isEmpty()) {
                throw new TargetSetupError(
                        "Deprecated option global-setting conflicts with "
                                + "option set-global-setting",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mGlobalSettings.putAll(mDeprecatedGlobalSettings);
        }

        if (!mDeprecatedGServicesSettings.isEmpty()) {
            if (!mGServiceSettings.isEmpty()) {
                throw new TargetSetupError(
                        "Deprecated option gservices-setting conflicts with "
                                + "option set-gservice-setting",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mGServiceSettings.putAll(mDeprecatedGServicesSettings);
        }

        if (mDeprecatedDefaultDataCallSubscription != null) {
            if (mDefaultSimData != null) {
                throw new TargetSetupError(
                        "Deprecated option default-data-call-subscription "
                                + "conflicts with option default-sim-data",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mDefaultSimData = mDeprecatedDefaultDataCallSubscription;
        }

        if (mDeprecatedDefaultVoiceCallSubscription != null) {
            if (mDefaultSimVoice != null) {
                throw new TargetSetupError(
                        "Deprecated option default-voice-call-subscription "
                                + "conflicts with option default-sim-voice",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mDefaultSimVoice = mDeprecatedDefaultVoiceCallSubscription;
        }

        if (mDeprecatedDefaultSmsSubscription != null) {
            if (mDefaultSimSms != null) {
                throw new TargetSetupError(
                        "Deprecated option default-sms-subscription conflicts "
                                + "with option default-sim-sms",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mDefaultSimSms = mDeprecatedDefaultSmsSubscription;
        }

        if (mDeprecatedForceSkipChangeSettings /* default false */) {
            if (mForceSkipSettings /* default false */) {
                throw new TargetSetupError(
                        "Deprecated option force-skip-change-settings "
                                + "conflicts with option force-skip-settings",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mForceSkipSettings = mDeprecatedForceSkipChangeSettings;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void processOptions(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        super.processOptions(device);

        if ((mScreenBrighnessNit != null || mScreenBrighnessLux != null)
                && BinaryState.ON.equals(mScreenAdaptiveBrightness)) {
            throw new TargetSetupError(
                    "Options screen-brightness-nits and screen-brightness-lux "
                            + "cannot be set when screen-adaptive-brightness is set to ON",
                    device.getDeviceDescriptor());
        }

        // If screen-brightness is already set, it will be overridden by brightnessFor200Nit
        if (mScreenBrighnessNit != null) {
            if (mScreenBrighnessNit == 200) {
                mSystemSettings.put(
                        "screen_brightness", Integer.toString(getBrightnessFor200Nit(device)));
            } else {
                throw new TargetSetupError(
                        "Option screen-brightness-nit must be 200", device.getDeviceDescriptor());
            }
        }

        // If screen-brightness is already set, it will be overridden by brightnessFor100lux
        if (mScreenBrighnessLux != null) {
            if (mScreenBrighnessLux == 100) {
                mSystemSettings.put(
                        "screen_brightness", Integer.toString(getBrightnessFor100Lux(device)));
            } else {
                throw new TargetSetupError(
                        "Option screen-brightness-lux must be 100", device.getDeviceDescriptor());
            }
        }

        if (mDisableUseLocationPrompt) {
            if (device.getApiLevel() < 19) {
                mRunCommandAfterSettings.add(
                        "am start -a com.google.android.gsf.action.SET_USE_LOCATION_FOR_SERVICES "
                                + "--ez disable true");
            }
        }

        setSettingForBinaryState(
                mLocationCollection, mGServiceSettings, "location:collection_enabled", "1", "0");

        setSettingForBinaryState(
                mLocationOptIn, mGooglePartnerSettings, "network_location_opt_in", "1", "0");
        setSettingForBinaryState(
                mLocationOptIn, mGooglePartnerSettings, "use_location_for_services", "1", "0");

        setSettingForBinaryState(
                mCastBroadcast,
                mGServiceSettings,
                "gms:cast:mdns_device_scanner:is_enabled",
                "true",
                "false");

        if (mDisablePlaystore) {
            mRunCommandAfterSettings.add("pm disable-user com.android.vending");
        }

        if (BinaryState.OFF.equals(mMusicIQ)) {
            mRunCommandAfterSettings.add(
                    "sed -i 's/\\(.*name=\"ambient_music_aod_notification_"
                            + "switch\".*\\)value=\"true\"/\\1value=\"false\"/g' "
                            + "data/data/com.google.intelligence.sense/shared_prefs/"
                            + "com.google.intelligence.sense_preferences.xml");
        }

        if (BinaryState.ON.equals(mMusicIQ)) {
            mRunCommandAfterSettings.add(
                    "sed -i 's/\\(.*name=\"ambient_music_aod_notification_"
                            + "switch\".*\\)value=\"false\"/\\1value=\"true\"/g' "
                            + "data/data/com.google.intelligence.sense/shared_prefs/"
                            + "com.google.intelligence.sense_preferences.xml");
        }

        if (!BinaryState.IGNORE.equals(mMusicIQ)) {
            mRunCommandAfterSettings.add("am force-stop com.google.intelligence.sense");
            mRunCommandAfterSettings.add(
                    "am start -n com.google.intelligence.sense/com.google.intelligence.sense."
                            + "ambientmusic.AmbientMusicSettingsActivity");
            // Previous command brings up the 'now playhing' settings window and this command
            // is to dismiss it and leave the phone with the original UI state.
            mRunCommandAfterSettings.add("input keyevent KEYCODE_HOME");
        }

        if (BinaryState.OFF.equals(mMusicIQOfAs)) {
            mRunCommandAfterSettings.add(
                    "sed -i 's/\\(.*name=\"ambient_music_aod_notification_"
                            + "switch\".*\\)value=\"true\"/\\1value=\"false\"/g' data/data/"
                            + "com.google.android.as/shared_prefs/now_playing_prefs.xml");
        }
        if (BinaryState.ON.equals(mMusicIQOfAs)) {
            mRunCommandAfterSettings.add(
                    "sed -i 's/\\(.*name=\"ambient_music_aod_notification_"
                            + "switch\".*\\)value=\"false\"/\\1value=\"true\"/g' data/data/"
                            + "com.google.android.as/shared_prefs/now_playing_prefs.xml");
        }

        if (!BinaryState.IGNORE.equals(mMusicIQOfAs)) {
            mRunCommandAfterSettings.add("am force-stop com.google.android.as");
            mRunCommandAfterSettings.add(
                    "am start -n com.google.android.as/com.google.intelligence.sense."
                            + "ambientmusic.AmbientMusicSettingsActivity");
            // Previous command brings up the 'now playing' settings window and this command
            // is to dismiss it and leave the phone with the original UI state.
            mRunCommandAfterSettings.add("input keyevent KEYCODE_HOME");
        }

        if (mDisableCHRE) {
            mRunCommandAfterSettings.add("setprop ctl.stop vendor.chre");
        }

        if (mDisableCaptions) {
            mRunCommandAfterSettings.add(
                    "device_config put device_personalization_services "
                            + "Captions__disable_prod true");
        }

        if (mDisablesuperpacks_download) {
            mRunCommandAfterSettings.add(
                    "device_config put device_personalization_services "
                            + "Overview__enable_superpacks_download false");
        }

        if (mDisablePixelLogger) {
            mRunCommandAfterSettings.add("pm disable com.android.pixellogger");
        }

        if (BinaryState.OFF.equals(mLocationMode)) {
            mRunCommandAfterSettings.add("settings put secure location_mode 0");
        }

        if (BinaryState.ON.equals(mLocationMode)) {
            mRunCommandAfterSettings.add("settings put secure location_mode 3");
        }

        if (!BinaryState.IGNORE.equals(mHotword)) {
            int mHotword_state = 0; // Enroll Hotword
            if (BinaryState.OFF.equals(mHotword)) {
                mHotword_state = 2; // Disenroll Hotword
            } else if (BinaryState.ON.equals(mHotword)) {
                mHotword_state = 0;
            }
            mRunCommandAfterSettings.add(
                    String.format(
                            "am start -a com.android.intent.action.MANAGE_VOICE_KEYPHRASES"
                                    + " --ei com.android.intent.extra.VOICE_KEYPHRASE_ACTION %d "
                                    + "--es com.android.intent.extra.VOICE_KEYPHRASE_HINT_TEXT"
                                    + " \"demo\" --es \"com.android.intent.extra.VOICE_"
                                    + "KEYPHRASE_LOCALE\" \"en-US\" com.android.hotwordenrollment."
                                    + "okgoogle/com.android.hotwordenrollment.okgoogle."
                                    + "EnrollmentActivity",
                            mHotword_state));
        }

        if (mDisableVolta) {
            mRunCommandAfterSettings.add("pm disable-user com.google.android.volta");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void changeSettings(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        if (mForceSkipSettings) {
            CLog.d("Skipping settings due to force-skip-settings");
            return;
        }

        if (mSystemSettings.isEmpty()
                && mSecureSettings.isEmpty()
                && mGlobalSettings.isEmpty()
                && mGooglePartnerSettings.isEmpty()
                && mGServiceSettings.isEmpty()
                && BinaryState.IGNORE.equals(mAirplaneMode)) {
            CLog.d("No settings to change");
            return;
        }

        SettingsUtil settingsUtil = new SettingsUtil(device, mSettingCmdApk);
        settingsUtil.installSettingCmdApk();

        // Special case airplane mode since it needs to be set before other connectivity settings
        // For example, it is possible to enable airplane mode and then turn wifi on
        String command = "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state %s";
        switch (mAirplaneMode) {
            case ON:
                CLog.d("Changing global setting airplane_mode_on to 1");
                settingsUtil.changeGlobalSetting("airplane_mode_on", "1");
                if (!mForceSkipRunCommands) {
                    device.executeShellCommand(String.format(command, "true"));
                }
                break;
            case OFF:
                CLog.d("Changing global setting airplane_mode_on to 0");
                settingsUtil.changeGlobalSetting("airplane_mode_on", "0");
                if (!mForceSkipRunCommands) {
                    device.executeShellCommand(String.format(command, "false"));
                }
                break;
            case IGNORE:
                // No-op
                break;
        }

        for (String key : mSystemSettings.keySet()) {
            for (String value : mSystemSettings.get(key)) {
                CLog.d("Changing system setting %s to %s", key, value);
                settingsUtil.changeSetting(key, value);
            }
        }
        for (String key : mSecureSettings.keySet()) {
            for (String value : mSecureSettings.get(key)) {
                CLog.d("Changing secure setting %s to %s", key, value);
                settingsUtil.changeSecureSetting(key, value);
            }
        }
        for (String key : mGlobalSettings.keySet()) {
            for (String value : mGlobalSettings.get(key)) {
                CLog.d("Changing global setting %s to %s", key, value);
                settingsUtil.changeGlobalSetting(key, value);
            }
        }
        for (String key : mGooglePartnerSettings.keySet()) {
            for (String value : mGooglePartnerSettings.get(key)) {
                CLog.d("Changing Google partner setting %s to %s", key, value);
                settingsUtil.changeGooglePartnerSetting(key, value);
            }
        }
        for (String key : mGServiceSettings.keySet()) {
            for (String value : mGServiceSettings.get(key)) {
                CLog.d("Overriding GService setting %s to %s", key, value);
                settingsUtil.overrideGServices(key, value);
            }
        }
        settingsUtil.uninstallSettingCmd();
    }

    /**
     * Helper method to get the calibrated brightness for the products supporting 200 nits.
     *
     * @param device The {@link ITestDevice}
     * @return the brightness value which maps to 200 nits.
     * @throws DeviceNotAvailableException if the device was not available
     * @throws TargetSetupError if there is no calibration data for 200 nits
     */
    private int getBrightnessFor200Nit(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        String product = getProductName(device);
        Integer brightness = BrightnessSetupConfigUtil.getBrightnessFor200Nits(product);
        if (brightness == null) {
            throw new TargetSetupError(
                    String.format(
                            "Setting screen to 200 nits not supported for product %s", product),
                    device.getDeviceDescriptor());
        }
        return brightness;
    }

    /**
     * Helper method to get the calibrated brightness for the products supporting 100 lux.
     *
     * @param device The {@link ITestDevice}
     * @return the brightness value which maps to ambient 100 lux.
     * @throws DeviceNotAvailableException if the device was not available
     * @throws TargetSetupError if there is no calibration data for 100 lux
     */
    private int getBrightnessFor100Lux(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        String product = getProductName(device);
        Integer brightness = BrightnessSetupConfigUtil.getBrightnessFor100Lux(product);
        if (brightness == null) {
            throw new TargetSetupError(
                    String.format(
                            "Setting screen for 100 lux is not supported for product %s", product),
                    device.getDeviceDescriptor());
        }
        return brightness;
    }

    /**
     * Helper method to return the product name
     *
     * @return the product name of the device or null if it's not set.
     * @throws DeviceNotAvailableException if the device was not available
     */
    private String getProductName(ITestDevice device) throws DeviceNotAvailableException {
        String product = device.getProperty("ro.product.name");
        if (product == null || product.isEmpty()) {
            return null;
        }
        return product;
    }

    /** Exposed for unit testing */
    @Override
    protected void setAirplaneMode(BinaryState airplaneMode) {
        mAirplaneMode = airplaneMode;
    }

    /** Exposed for unit testing */
    @Override
    protected void setWakeGesture(BinaryState wakeGesture) {
        mWakeGesture = wakeGesture;
    }

    /** Exposed for unit testing */
    void setDisableUseLocationPrompt(boolean disableUseLocationPrompt) {
        mDisableUseLocationPrompt = disableUseLocationPrompt;
    }

    /** Exposed for unit testing */
    void setLocationCollection(BinaryState locationCollection) {
        mLocationCollection = locationCollection;
    }

    /** Exposed for unit testing */
    void setLocationOptIn(BinaryState locationOptIn) {
        mLocationOptIn = locationOptIn;
    }

    /** Exposed for unit testing */
    void setCastBroadcast(BinaryState castBroadcast) {
        mCastBroadcast = castBroadcast;
    }

    /** Exposed for unit testing */
    @Override
    protected void setDefaultSimData(Integer defaultSimData) {
        mDefaultSimData = defaultSimData;
    }

    /** Exposed for unit testing */
    @Override
    protected void setDefaultSimVoice(Integer defaultSimVoice) {
        mDefaultSimVoice = defaultSimVoice;
    }

    /** Exposed for unit testing */
    @Override
    protected void setDefaultSimSms(Integer defaultSimSms) {
        mDefaultSimSms = defaultSimSms;
    }

    /** Exposed for unit testing */
    void setDisablePlaystore(boolean disable) {
        mDisablePlaystore = disable;
    }

    /** Exposed for unit testing */
    void setMusicIQ(BinaryState musicIQ) {
        mMusicIQ = musicIQ;
    }

    /** Exposed for unit testing */
    void setMusicIQOfAs(BinaryState musicIQOfAs) {
        mMusicIQOfAs = musicIQOfAs;
    }

    /** Exposed for unit testing */
    void setDisableCHRE(boolean disable) {
        mDisableCHRE = disable;
    }

    /** Exposed for unit testing */
    void setDisableCaptions(boolean disable) {
        mDisableCaptions = disable;
    }

    /** Exposed for unit testing */
    void setDisablesuperpacks_download(boolean disable) {
        mDisablesuperpacks_download = disable;
    }

    /** Exposed for unit testing */
    void setDisablePixelLogger(boolean disable) {
        mDisablePixelLogger = disable;
    }

    /** Exposed for unit testing */
    void setHotword(BinaryState hotword) {
        mHotword = hotword;
    }

    /** Exposed for unit testing */
    void setDisableVolta(boolean disable) {
        mDisableVolta = disable;
    }

    /** Exposed for unit testing */
    void setGooglePartnerSetting(String key, String value) {
        mGooglePartnerSettings.put(key, value);
    }

    /** Exposed for unit testing */
    void setGserviceSetting(String key, String value) {
        mGServiceSettings.put(key, value);
    }

    /** Exposed for unit testing */
    @Deprecated
    public void setDeprecatedAirplaneMode(boolean airplaneMode) {
        mDeprecatedAirplaneMode = airplaneMode;
    }

    /** Exposed for unit testing */
    @Deprecated
    void setDeprecatedDisableUseLocation(boolean disableUseLocation) {
        mDeprecatedDisableUseLocation = disableUseLocation;
    }

    /** Exposed for unit testing */
    @Deprecated
    public void setDeprecatedSetting(String key, String value) {
        mDeprecatedSettings.put(key, value);
    }

    /** Exposed for unit testing */
    @Deprecated
    public void setDeprecatedSecureSetting(String key, String value) {
        mDeprecatedSecureSettings.put(key, value);
    }

    /** Exposed for unit testing */
    @Deprecated
    public void setDeprecatedPartnerSetting(String key, String value) {
        mDeprecatedPartnerSettings.put(key, value);
    }

    /** Exposed for unit testing */
    @Deprecated
    public void setDeprecatedGlobalSetting(String key, String value) {
        mDeprecatedGlobalSettings.put(key, value);
    }

    /** Exposed for unit testing */
    @Deprecated
    public void setDeprecatedGServicesSetting(String key, String value) {
        mDeprecatedGServicesSettings.put(key, value);
    }

    /** Exposed for unit testing */
    @Deprecated
    public void setDeprecatedDefaultDataCallSubscription(int subscription) {
        mDeprecatedDefaultDataCallSubscription = subscription;
    }

    /** Exposed for unit testing */
    @Deprecated
    public void setDeprecatedDefaultVoiceCallSubscription(int subscription) {
        mDeprecatedDefaultVoiceCallSubscription = subscription;
    }

    /** Exposed for unit testing */
    @Deprecated
    public void setDeprecatedDefaultSmsSubscription(int subscription) {
        mDeprecatedDefaultSmsSubscription = subscription;
    }
}
