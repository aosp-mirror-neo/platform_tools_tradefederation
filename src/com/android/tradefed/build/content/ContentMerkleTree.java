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
package com.android.tradefed.build.content;

import com.android.tradefed.build.content.ArtifactDetails.ArtifactFileDescriptor;
import com.android.tradefed.cache.DigestCalculator;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.skipped.AnalysisHeuristic;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FileNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Compute a MerkleTree from the content information. */
public class ContentMerkleTree {

    /** Builds a merkle tree and returns the root digest from the common location information */
    public static Digest buildCommonLocationFromContext(ContentAnalysisContext context) {
        try (CloseableTraceScope commonLoc =
                new CloseableTraceScope("buildCommonLocationFromContext")) {
            ArtifactDetails currentContent =
                    ArtifactDetails.parseFile(
                            context.contentInformation().currentContent, context.contentEntry());
            Directory.Builder rootBuilder = Directory.newBuilder();
            List<ArtifactFileDescriptor> allFiles = currentContent.details;
            List<ArtifactFileDescriptor> commonFiles =
                    allFiles.parallelStream()
                            .filter(
                                    p -> {
                                        for (String common : context.commonLocations()) {
                                            if (p.path.startsWith(common)) {
                                                return true;
                                            }
                                        }
                                        return false;
                                    })
                            .collect(Collectors.toList());
            // Remove ignored files
            commonFiles.removeIf(d -> context.ignoredChanges().contains(d.path));
            // Remove xTS style version
            String rootPackage = context.contentEntry().replaceAll(".zip", "");
            commonFiles.removeIf(d -> d.path.equals(rootPackage + "/tools/version.txt"));
            // Sort to ensure final messages are identical
            Collections.sort(
                    commonFiles,
                    new Comparator<ArtifactFileDescriptor>() {
                        @Override
                        public int compare(
                                ArtifactFileDescriptor arg0, ArtifactFileDescriptor arg1) {
                            return arg0.path.compareTo(arg1.path);
                        }
                    });
            for (ArtifactFileDescriptor afd : commonFiles) {
                Digest digest =
                        Digest.newBuilder().setHash(afd.digest).setSizeBytes(afd.size).build();
                rootBuilder.addFiles(
                        FileNode.newBuilder()
                                .setDigest(digest)
                                .setName(afd.path)
                                .setIsExecutable(false));
            }
            Directory root = rootBuilder.build();
            Digest d = DigestCalculator.compute(root);
            CLog.d("Digest for common location of '%s' is '%s'", context.contentEntry(), d);
            return d;
        } catch (IOException | RuntimeException e) {
            CLog.e(e);
            return null;
        }
    }

    public static Digest buildTestsDirFromContext(
            ContentAnalysisContext context,
            List<String> discoveredModule,
            AnalysisHeuristic analysisLevel) {
        try (CloseableTraceScope testLoc = new CloseableTraceScope("buildTestsDirFromContext")) {
            ArtifactDetails currentContent =
                    ArtifactDetails.parseFile(
                            context.contentInformation().currentContent, context.contentEntry());
            Directory.Builder rootBuilder = Directory.newBuilder();
            List<ArtifactFileDescriptor> allFiles = currentContent.details;
            allFiles.removeIf(d -> context.ignoredChanges().contains(d.path));
            // Remove xTS style version
            String rootPackage = context.contentEntry().replaceAll(".zip", "");
            allFiles.removeIf(d -> d.path.equals(rootPackage + "/tools/version.txt"));
            // Don't consider common locations
            allFiles.removeIf(
                    d -> {
                        for (String common : context.commonLocations()) {
                            if (d.path.startsWith(common)) {
                                return true;
                            }
                        }
                        return false;
                    });

            if (analysisLevel.ordinal() >= AnalysisHeuristic.REMOVE_EXEMPTION.ordinal()) {
                boolean removed = false;
                for (String path :
                        Arrays.asList(
                                // b/451443647
                                "/testcases/VtsSeeHalTargetTest/arm64/trusty_test_vm.elf",
                                "/testcases/vts_treble_vintf_trusted_hal_test/arm64/trusty_test_vm.elf")) {
                    removed = allFiles.removeIf(d -> d.path.endsWith(path)) || removed;
                }
                if (removed) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.TEST_ARTIFACT_USED_HEURISTIC, analysisLevel.name());
                }
            }

            List<ArtifactFileDescriptor> relevantArtifacts = new ArrayList<>();
            if (!discoveredModule.isEmpty()) {
                for (String module : discoveredModule) {
                    relevantArtifacts.addAll(
                            allFiles.parallelStream()
                                    .filter(
                                            d -> {
                                                if (d.path.contains("/" + module + "/")) {
                                                    return true;
                                                }
                                                return false;
                                            })
                                    .collect(Collectors.toList()));
                }
                CLog.d("Considering for testsdir key the following paths:");
                for (ArtifactFileDescriptor afd : relevantArtifacts) {
                    CLog.d("%s", afd.path);
                }

            } else {
                relevantArtifacts.addAll(allFiles);
            }

            // Sort to ensure final messages are identical
            Collections.sort(
                    relevantArtifacts,
                    new Comparator<ArtifactFileDescriptor>() {
                        @Override
                        public int compare(
                                ArtifactFileDescriptor arg0, ArtifactFileDescriptor arg1) {
                            return arg0.path.compareTo(arg1.path);
                        }
                    });
            for (ArtifactFileDescriptor afd : relevantArtifacts) {
                Digest digest =
                        Digest.newBuilder().setHash(afd.digest).setSizeBytes(afd.size).build();
                rootBuilder.addFiles(
                        FileNode.newBuilder()
                                .setDigest(digest)
                                .setName(afd.path)
                                .setIsExecutable(false));
            }
            Directory root = rootBuilder.build();
            Digest d = DigestCalculator.compute(root);
            CLog.d("Digest for testsdir location of '%s' is '%s'", context.contentEntry(), d);
            return d;
        } catch (IOException | RuntimeException e) {
            CLog.e(e);
            return null;
        }
    }
}
