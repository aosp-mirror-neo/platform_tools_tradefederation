// Copyright 2010 Google Inc. All Rights Reserved.

package com.google.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static com.google.common.truth.Truth.assertThat;

import com.google.android.tradefed.build.RemoteBuildInfo.BuildType;
import com.google.android.tradefed.build.RemoteBuildInfo.InvalidResponseException;

/** Unit tests for {@link RemoteBuildInfo}. */
@RunWith(JUnit4.class)
public class RemoteBuildInfoTest {

    /**
     * Test that {@link RemoteBuildInfo#parseRemoteBuildInfo(String)} returns null for empty input.
     */
    @Test
    public void testParseRemoteBuildInfo_blank() throws InvalidResponseException {
        assertThat(RemoteBuildInfo.parseRemoteBuildInfo("")).isNull();
    }

    /**
     * Test that {@link RemoteBuildInfo#parseRemoteBuildInfo(String)} returns null when the input is
     * whitespace.
     */
    @Test
    public void testParseRemoteBuildInfo_newLines() throws InvalidResponseException {
        assertThat(RemoteBuildInfo.parseRemoteBuildInfo("\r\n")).isNull();
    }

    /**
     * Test that {@link RemoteBuildInfo#parseRemoteBuildInfo(String)} throws {@link
     * InvalidResponseException} if server returns response where buildId is missing.
     */
    @Test
    public void testParseRemoteBuildInfo_missingBuild() {
        try {
            RemoteBuildInfo.parseRemoteBuildInfo("key:value\n");
            fail("InvalidResponseException not thrown");
        } catch (InvalidResponseException e) {
            // expected
        }
    }

    /**
     * Test that {@link RemoteBuildInfo#parseRemoteBuildInfo(String)} throws {@link
     * InvalidResponseException} if server returns response where buildId is empty.
     */
    @Test
    public void testGetBuildId_emptyBuild() {
        try {
            RemoteBuildInfo.parseRemoteBuildInfo("key:value\n");
            fail("InvalidResponseException not thrown");
        } catch (InvalidResponseException e) {
            // expected
        }
    }

    /** Test success case for {@link RemoteBuildInfo#getBuildId()} on external builds. */
    @Test
    public void testGetBuildId_external() throws InvalidResponseException {
        RemoteBuildInfo info = RemoteBuildInfo.parseRemoteBuildInfo("bid:E123\n");
        assertThat(info.getBuildId()).isEqualTo("E123");
        assertThat(info.getAttribute(RemoteBuildInfo.BuildAttributeKey.BUILD_TYPE))
                .isEqualTo(RemoteBuildInfo.BuildType.EXTERNAL.toString());
    }

    /** Test success case for {@link RemoteBuildInfo#getBuildId()} on local builds. */
    @Test
    public void testGetBuildId_local() throws InvalidResponseException {
        RemoteBuildInfo info = RemoteBuildInfo.parseRemoteBuildInfo("bid:L123\n");
        assertThat(info.getBuildId()).isEqualTo("L123");
        assertThat(info.getAttribute(RemoteBuildInfo.BuildAttributeKey.BUILD_TYPE))
                .isEqualTo(RemoteBuildInfo.BuildType.LOCAL.toString());
    }

    /** Test success case for {@link RemoteBuildInfo#getBuildId()} on pending builds. */
    @Test
    public void testGetBuildId_pending() throws InvalidResponseException {
        RemoteBuildInfo info = RemoteBuildInfo.parseRemoteBuildInfo("bid:P123\n");
        assertThat(info.getBuildId()).isEqualTo("P123");
        assertThat(info.getAttribute(RemoteBuildInfo.BuildAttributeKey.BUILD_TYPE))
                .isEqualTo(RemoteBuildInfo.BuildType.PENDING.toString());
    }

    /** Test success case for {@link RemoteBuildInfo#getBuildId()} on submitted builds. */
    @Test
    public void testGetBuildId_submitted() throws InvalidResponseException {
        RemoteBuildInfo info = RemoteBuildInfo.parseRemoteBuildInfo("bid:123\n");
        assertThat(info.getBuildId()).isEqualTo("123");
        assertThat(info.getAttribute(RemoteBuildInfo.BuildAttributeKey.BUILD_TYPE))
                .isEqualTo(RemoteBuildInfo.BuildType.SUBMITTED.toString());
    }

    /** Test success case for {@link RemoteBuildInfo#getBuildId()} on train builds. */
    @Test
    public void testGetBuildId_train() throws InvalidResponseException {
        RemoteBuildInfo info = RemoteBuildInfo.parseRemoteBuildInfo("bid:T123\n");
        assertThat(info.getBuildId()).isEqualTo("T123");
        assertThat(info.getAttribute(RemoteBuildInfo.BuildAttributeKey.BUILD_TYPE))
                .isEqualTo(RemoteBuildInfo.BuildType.TRAIN.toString());
    }

    /** Test success case for get default build attempt id */
    @Test
    public void testGetBuildAttemptId_default() throws InvalidResponseException {
        RemoteBuildInfo info = RemoteBuildInfo.parseRemoteBuildInfo("bid:4\n");
        assertEquals(RemoteBuildInfo.DEFAULT_BUILD_ATTEMPT_ID,
                info.getAttribute(RemoteBuildInfo.BuildAttributeKey.BUILD_ATTEMPT_ID));
    }

    /** Test success case for get build attempt id */
    @Test
    public void testGetBuildAttemptId() throws InvalidResponseException {
        RemoteBuildInfo info = RemoteBuildInfo.parseRemoteBuildInfo("bid:4\nbuild_attempt_id:0");
        assertEquals("0",
                info.getAttribute(RemoteBuildInfo.BuildAttributeKey.BUILD_ATTEMPT_ID));
    }

    /** Test when build type is null. */
    @Test
    public void testParseBuildType_null() {
        assertThat(RemoteBuildInfo.parseBuildType(null)).isEqualTo(BuildType.SUBMITTED);
    }

    /** Test when build type is empty. */
    @Test
    public void testParseBuildType_empty() {
        assertThat(RemoteBuildInfo.parseBuildType("")).isEqualTo(BuildType.SUBMITTED);
    }

    /** Test when build type is external. */
    @Test
    public void testParseBuildType_external() {
        assertThat(RemoteBuildInfo.parseBuildType("E123")).isEqualTo(BuildType.EXTERNAL);
    }

    /** Test when build type is local. */
    @Test
    public void testParseBuildType_local() {
        assertThat(RemoteBuildInfo.parseBuildType("L123")).isEqualTo(BuildType.LOCAL);
    }

    /** Test when build type is pending. */
    @Test
    public void testParseBuildType_pending() {
        assertThat(RemoteBuildInfo.parseBuildType("P123")).isEqualTo(BuildType.PENDING);
    }

    /** Test when build type is submitted. */
    @Test
    public void testParseBuildType_submitted() {
        assertThat(RemoteBuildInfo.parseBuildType("123")).isEqualTo(BuildType.SUBMITTED);
    }

    /** Test when build type is train. */
    @Test
    public void testParseBuildType_train() {
        assertThat(RemoteBuildInfo.parseBuildType("T123")).isEqualTo(BuildType.TRAIN);
    }
}
