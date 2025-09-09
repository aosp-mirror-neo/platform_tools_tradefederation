/*
 * Copyright (C) 2010 The Android Open Source Project
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

/** Interface deal with {@link IDevice} addition, deletion, and changes. */
public interface IAdbDeviceChangeListener {

    /**
     * Called when a new device is detected for the first time.
     *
     * @param serial The device serial number.
     * @param state The current state of the device (e.g., "device", "offline", "unauthorized").
     */
    void deviceConnected(String serial, String state);

    /**
     * Called when a previously known device's state has changed.
     *
     * @param serial The device serial number.
     * @param newState The new state of the device.
     */
    void deviceChanged(String serial, String newState);

    /**
     * Called when a device is no longer reported by the tracking command.
     *
     * @param serial The device serial number that has disconnected.
     */
    void deviceDisconnected(String serial);
}
