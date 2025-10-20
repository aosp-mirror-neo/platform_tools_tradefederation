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

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AndroidBuildArtifactHelper} */
@RunWith(JUnit4.class)
public class AndroidBuildArtifactHelperTest {

  @Test
  public void testGetBuildFlavor() {
    assertEquals("flavor", AndroidBuildArtifactHelper.getBuildFlavor("flavor", "linux"));
    assertEquals("flavor", AndroidBuildArtifactHelper.getBuildFlavor("flavor", "mac"));
    assertEquals("flavor", AndroidBuildArtifactHelper.getBuildFlavor("flavor", "mac_arm64"));
    assertEquals(
        "flavor_something", AndroidBuildArtifactHelper.getBuildFlavor("flavor", "something"));
    assertEquals("flavor", AndroidBuildArtifactHelper.getBuildFlavor("flavor", null));
  }
}
