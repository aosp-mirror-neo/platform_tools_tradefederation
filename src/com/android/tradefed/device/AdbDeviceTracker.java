/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.device;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import com.google.common.collect.Sets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides device connection and state change events by running and parsing the output of the 'adb
 * track-devices' command.
 *
 * <p>This class is designed for the device detection. It runs in a background thread and notifies
 * registered {@link IAdbDeviceChangeListener}s of any changes.
 */
public class AdbDeviceTracker implements Runnable {

    // Regex to find a 'devices {' block in the output. The DOTALL flag allows '.' to match
    // newlines.
    private static final Pattern DEVICE_BLOCK_PATTERN =
            Pattern.compile("devices\\s*\\{\\s*([^}]+)\\}", Pattern.DOTALL);
    // Regex to extract a quoted value from a key, e.g., from 'serial: "12345"' it extracts '12345'.
    private static final Pattern SERIAL_PATTERN = Pattern.compile("serial:\\s*\"([^\"]+)\"");
    private static final Pattern STATE_PATTERN = Pattern.compile("state:\\s*\"([^\"]+)\"");
    private final IRunUtil mRunUtil;
    private volatile boolean mIsRunning = true;

    // Use a CopyOnWriteArrayList for thread-safe listener management. This is efficient
    // for scenarios with infrequent writes (add/remove listener) and frequent reads (iterations).
    private final List<IAdbDeviceChangeListener> mListeners = new CopyOnWriteArrayList<>();

    // This map holds the last known state of every device we have seen. It is the source of
    // truth for determining if a device is new, has changed state, or has disconnected.
    // Access to this map must be synchronized.
    private final Map<String, String> mKnownDeviceStates = new HashMap<>();

    /** Creates a new AdbDeviceTracker. */
    public AdbDeviceTracker() {
        mRunUtil = RunUtil.getDefault();
    }

    /**
     * Adds a listener to be notified of device events.
     *
     * @param listener the {@link IAdbDeviceChangeListener} to add.
     */
    public void addListener(IAdbDeviceChangeListener listener) {
        if (listener != null) {
            mListeners.add(listener);
        }
    }

    /**
     * Removes a previously added listener.
     *
     * @param listener the {@link IAdbDeviceChangeListener} to remove.
     */
    public void removeListener(IAdbDeviceChangeListener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    /** Signals the tracker to stop its execution loop. */
    public void stop() {
        mIsRunning = false;
    }

    /** The main execution loop. This should be run in a dedicated thread. */
    @Override
    public void run() {
        while (mIsRunning) {
            Process adbTrackProcess = null;
            try {
                // Use RunUtil to start the command in the background. This is a long-running
                // command that streams output indefinitely.
                adbTrackProcess =
                        mRunUtil.runCmdInBackground("adb", "track-devices", "--proto-text");

                // Use a try-with-resources block to ensure the reader is always closed.
                try (BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(adbTrackProcess.getInputStream()))) {
                    StringBuilder messageBuffer = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && mIsRunning) {
                        messageBuffer.append(line).append("\n");
                        // A blank line signifies the end of a message burst from adb.
                        if (line.trim().isEmpty() && messageBuffer.length() > 1) {
                            processDeviceList(messageBuffer.toString());
                            // Reset buffer for the next message.
                            messageBuffer.setLength(0);
                        }
                    }
                }
            } catch (IOException e) {
                CLog.e("Failed to start or read from 'adb track-devices' process. Retrying soon.");
                CLog.e(e);
            } finally {
                if (adbTrackProcess != null) {
                    adbTrackProcess.destroy();
                }
                // If the loop exits but we are supposed to keep running (e.g., adb server died),
                // wait for a bit before trying to restart the process to avoid spamming errors.
                if (mIsRunning) {
                    mRunUtil.sleep(5000);
                }
            }
        }
        CLog.d("AdbDeviceTracker has been stopped.");
    }

    /**
     * Manually parses the text proto output for a list of devices.
     *
     * @param protoText The complete text of a DeviceList message.
     */
    private void processDeviceList(String protoText) {
        Map<String, String> newSnapshot = new HashMap<>();
        Matcher blockMatcher = DEVICE_BLOCK_PATTERN.matcher(protoText);

        // Find each 'devices { ... }' block in the text
        while (blockMatcher.find()) {
            String deviceBlock = blockMatcher.group(1);

            Matcher serialMatcher = SERIAL_PATTERN.matcher(deviceBlock);
            Matcher stateMatcher = STATE_PATTERN.matcher(deviceBlock);

            if (serialMatcher.find() && stateMatcher.find()) {
                String serial = serialMatcher.group(1);
                String state = stateMatcher.group(1);
                // TODO: adb track-devices --proto-text also include information such as product
                // name,
                // model, device... we can use them while create new IDevice to save
                // adb shell getprop call.
                newSnapshot.put(serial, state);
            } else {
                CLog.w("Could not parse device block: %s", deviceBlock);
            }
        }
        processDeviceSnapshot(newSnapshot);
    }

    /**
     * Compares the new device snapshot with the last known state and fires appropriate events to
     * listeners. This method is synchronized to protect access to mKnownDeviceStates.
     *
     * @param newSnapshot A map of serials to states for all devices seen in the latest update.
     */
    private synchronized void processDeviceSnapshot(Map<String, String> newSnapshot) {
        // 1. Find newly connected devices: Present in the new snapshot but not in our known list.
        Set<String> newSerials = Sets.difference(newSnapshot.keySet(), mKnownDeviceStates.keySet());
        for (String serial : newSerials) {
            String state = newSnapshot.get(serial);
            CLog.d("Tracker detected new device: %s in state %s", serial, state);
            for (IAdbDeviceChangeListener listener : mListeners) {
                listener.deviceConnected(serial, state);
            }
            mKnownDeviceStates.put(serial, state);
        }

        // 2. Find disconnected devices: Present in our known list but not in the new snapshot.
        Set<String> disconnectedSerials =
                Sets.difference(mKnownDeviceStates.keySet(), newSnapshot.keySet());
        for (String serial : disconnectedSerials) {
            CLog.d("Tracker detected disconnected device: %s", serial);
            for (IAdbDeviceChangeListener listener : mListeners) {
                listener.deviceDisconnected(serial);
            }
            mKnownDeviceStates.remove(serial);
        }

        // 3. Find devices whose state changed: Present in both lists but with a different state.
        Set<String> potentiallyChangedSerials =
                Sets.intersection(mKnownDeviceStates.keySet(), newSnapshot.keySet());
        for (String serial : potentiallyChangedSerials) {
            String newState = newSnapshot.get(serial);
            String oldState = mKnownDeviceStates.get(serial);
            if (!newState.equals(oldState)) {
                CLog.d(
                        "Tracker detected state change for %s: %s -> %s",
                        serial, oldState, newState);
                for (IAdbDeviceChangeListener listener : mListeners) {
                    listener.deviceChanged(serial, newState);
                }
                mKnownDeviceStates.put(serial, newState);
            }
        }
    }
}
