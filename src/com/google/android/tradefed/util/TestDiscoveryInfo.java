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

package com.google.android.tradefed.util;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.MultiMap;

import com.google.common.collect.ImmutableSet;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Utility class that stores the dependency information that test discovery needs. */
public class TestDiscoveryInfo {

    // TODO: Reevaluate when b/262397555 resolve
    private static final Set<String> HOST_REQUIRE_DEPENDENCIES =
            ImmutableSet.of(
                    "art-run-test-checker",
                    "cts-dalvik-host-test-runner/cts-dalvik-host-test-runner.jar",
                    "cts-dynamic-config/",
                    "xts-business-logic/");
    private static final Set<String> MAIN_HOST_REQUIRE_DEPENDENCIES =
            ImmutableSet.of("art-run-test-checker", "xts-business-logic/");

    private static final MultiMap<String, String> MAPPED_MAIN_MODULE_DEPENDENCIES =
            new MultiMap<>();
    private static final MultiMap<String, String> MAPPED_NON_MAIN_MODULE_DEPENDENCIES =
            new MultiMap<>();

    static {
        // b/277261121
        MAPPED_MAIN_MODULE_DEPENDENCIES.put("vts_ltp_test_arm_64", "vts_kernel_ltp_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put("vts_ltp_test_arm_64_lowmem", "vts_kernel_ltp_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put("vts_ltp_test_arm_64_hwasan", "vts_kernel_ltp_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put(
                "vts_ltp_test_arm_64_lowmem_hwasan", "vts_kernel_ltp_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put("vts_ltp_test_arm", "vts_kernel_ltp_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put("vts_ltp_test_arm_lowmem", "vts_kernel_ltp_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put("vts_ltp_test_x86_64", "vts_kernel_ltp_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put("vts_ltp_test_x86", "vts_kernel_ltp_tests");
        // b/277261121
        MAPPED_MAIN_MODULE_DEPENDENCIES.put(
                "vts_linux_kselftest_arm_64", "vts_kernel_kselftest_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put(
                "vts_linux_kselftest_arm_32", "vts_kernel_kselftest_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put(
                "vts_linux_kselftest_x86_64", "vts_kernel_kselftest_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put(
                "vts_linux_kselftest_x86_32", "vts_kernel_kselftest_tests");
        MAPPED_MAIN_MODULE_DEPENDENCIES.put(
                "vts_linux_kselftest_riscv_64", "vts_kernel_kselftest_tests");

        // b/262397555
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsMediaProviderTestCases", "compatibility-host-provider-preconditions");
        // CtsProviderTestCases media tests have been renamed to CtsMediaProviderTestCases,
        // leaving old mapping for branch back-compatibility
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsProviderTestCases", "compatibility-host-provider-preconditions");

        // Copy main mapping to non-main mapping to avoid duplicate.
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.putAll(MAPPED_MAIN_MODULE_DEPENDENCIES);

        // b/262397555
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsTelephonyTestCases", "compatibility-host-telephony-preconditions");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsTelephonySdk28TestCases", "compatibility-host-telephony-preconditions");
        // b/262397555
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsCarrierApiTestCases", "CtsCarrierApiTargetPrep");

        // b/262397555
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsShortcutHostTestCases", "CtsBackupHostTestCases.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put("CtsBackupTestCases", "CtsBackupHostTestCases.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsBackupTestCases_cts_permissiontest", "CtsBackupHostTestCases.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsSecurityTestCases", "CtsBackupHostTestCases.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsSecurityTestCases_CertBlockist", "CtsBackupHostTestCases.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsSecurityTestCases_cts_fileintegritymanagertest", "CtsBackupHostTestCases.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsSecurityTestCases_cts_motioneventtest", "CtsBackupHostTestCases.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsSecurityTestCases_cts_advancedprotectiontest", "CtsBackupHostTestCases.jar");
        // b/262397555
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "vm-tests-tf", "compatibility-host-vm-targetprep.jar");
        // b/277655002
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "MicrodroidHostTestCases", "MicrodroidTestPreparer.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put("AuthFsBenchmarks", "MicrodroidTestPreparer.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "MicrodroidBenchmarkApp", "MicrodroidTestPreparer.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put("AVFHostTestCases", "MicrodroidTestPreparer.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put("ComposBenchmarkApp", "MicrodroidTestPreparer.jar");

        // b/380422821
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "VirtualnetExampleTests", "virtualnet-host-lib.jar");
        // b/382186355
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "DesktopPowerPerformanceCujTestCases", "powerperformance-host-lib.jar");

        // b/262397555
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "NetworkStackIntegrationTests", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "NetworkStackNextIntegrationTests", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "NetworkStackRootTests", "net-tests-utils-host-common.jar");

        // b/343464660
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsPermissionUiTestCases", "permission-test-util-host-lib.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsPermissionTestCases", "permission-test-util-host-lib.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsPermissionMultiDeviceTestCases", "permission-test-util-host-lib.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsPermissionMultiUserTestCases", "permission-test-util-host-lib.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsPermissionPolicyTestCases", "permission-test-util-host-lib.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsRoleTestCases", "permission-test-util-host-lib.jar");

        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CarPropertyStressTestDeviceApp",
                "CarPropertyManagerStressTestLogPostProcessor.jar");
        // b/343955495
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNetTestCasesLatestSdk", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsVcnTestCases", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "resolv_gold_test", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "resolv_stress_test", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "resolv_integration_test", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put("doh_ffi_test", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNetTestCasesUpdateStatsPermission", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNetTestCases", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNetTestCasesDefaults", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNetTestCasesInternetPermission", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsTetheringTestLatestSdk", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNetTestCasesMaxTargetSdk30", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNetTestCasesMaxTargetSdk31", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNetTestCasesMaxTargetSdk33", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsTetheringTest", "net-tests-utils-host-common.jar");
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNativeNetDnsTestCases", "net-tests-utils-host-common.jar");
        // b/409359893
        MAPPED_NON_MAIN_MODULE_DEPENDENCIES.put(
                "CtsNetApi23TestCases", "net-tests-utils-host-common.jar");
    }

    public static Set<String> getAlwaysDownloadFilesList(List<String> testModules, String branch) {
        Set<String> toBeDownloaded = new LinkedHashSet<>();
        if (branch.equals("git_main")) {
            toBeDownloaded.addAll(MAIN_HOST_REQUIRE_DEPENDENCIES);
        } else {
            toBeDownloaded.addAll(HOST_REQUIRE_DEPENDENCIES);
        }

        for (String module : testModules) {
            if (branch.equals("git_main")) {
                CLog.d("Using main only dependency mapping.");
                List<String> mappedDep = MAPPED_MAIN_MODULE_DEPENDENCIES.get(module);
                if (mappedDep == null) {
                    continue;
                }
                toBeDownloaded.addAll(mappedDep);
            } else {
                List<String> mappedDep = MAPPED_NON_MAIN_MODULE_DEPENDENCIES.get(module);
                if (mappedDep == null) {
                    continue;
                }
                toBeDownloaded.addAll(mappedDep);
            }
        }

        return toBeDownloaded;
    }

    public static Set<String> getAlwaysDownloadFilesList(List<String> testModules) {
        return getAlwaysDownloadFilesList(testModules, "");
    }
}
