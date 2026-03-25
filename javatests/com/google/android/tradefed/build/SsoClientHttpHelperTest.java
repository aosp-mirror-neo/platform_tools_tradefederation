// Copyright 2014 Google Inc. All Rights Reserved.

package com.google.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/**
 * Unit tests for {@link SsoClientHttpHelper}
 */
@RunWith(JUnit4.class)
public class SsoClientHttpHelperTest {

    private SsoClientHttpHelper mSsoClientHttpHelper = null;
    private IRunUtil mRunUtil = null;

    @Before
    public void setUp() throws Exception {
        mRunUtil = Mockito.mock(IRunUtil.class);
        mSsoClientHttpHelper = new SsoClientHttpHelper() {
           @Override
           public IRunUtil getRunUtil() {
               return mRunUtil;
           }
        };
    }

    @Test
    public void  testDoGet() throws Exception {
        CommandResult mockResult = new CommandResult();
        mockResult.setStdout("HTTP/1.1 200 Internal Server Error\r\n\r\nCanned Successful Result");
        mockResult.setStatus(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(
                    Mockito.anyLong(),
                    Mockito.eq("sso_client"),
                    Mockito.eq("--dump_header"),
                    Mockito.eq("--location"),
                    Mockito.eq("test url"),
                    Mockito.eq("--connect_timeout"),
                    Mockito.eq(Integer.toString(mSsoClientHttpHelper.getOpTimeout() / 1000)),
                    Mockito.eq("--request_timeout"),
                    Mockito.eq(Integer.toString(mSsoClientHttpHelper.getOpTimeout() / 1000))))
                        .thenReturn(mockResult);

        assertEquals("Canned Successful Result", mSsoClientHttpHelper.doGet("test url"));
    }

    @Test
    public void  testDoGet_successHttpHeader() throws Exception {
        CommandResult mockResult = new CommandResult();
        String response = "HTTP/1.1 200 OK\n" +
                "Cache-Control: no-cache, must-revalidate\r\n\r\n" +
                "Canned Successful Result";
        mockResult.setStdout(response);
        mockResult.setStatus(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(
                    Mockito.anyLong(),
                    Mockito.eq("sso_client"),
                    Mockito.eq("--dump_header"),
                    Mockito.eq("--location"),
                    Mockito.eq("test url"),
                    Mockito.eq("--connect_timeout"),
                    Mockito.eq(Integer.toString(mSsoClientHttpHelper.getOpTimeout() / 1000)),
                    Mockito.eq("--request_timeout"),
                    Mockito.eq(Integer.toString(mSsoClientHttpHelper.getOpTimeout() / 1000))))
                        .thenReturn(mockResult);

        assertEquals("Canned Successful Result", mSsoClientHttpHelper.doGet("test url"));
    }

    @Test
    public void  testDoGet_serverErrorHttpHeader() throws Exception {
        CommandResult mockResult = new CommandResult();
        String response = "HTTP/1.1 500 Internal Server Error\r\n" +
                "Cache-Control: no-cache, must-revalidate\r\n\r\n" +
                "Canned Successful Result";
        mockResult.setStdout(response);
        mockResult.setStatus(CommandStatus.SUCCESS);
        when(mRunUtil.runTimedCmd(
                    Mockito.anyLong(),
                    Mockito.eq("sso_client"),
                    Mockito.eq("--dump_header"),
                    Mockito.eq("--location"),
                    Mockito.eq("test url"),
                    Mockito.eq("--connect_timeout"),
                    Mockito.eq(Integer.toString(mSsoClientHttpHelper.getOpTimeout() / 1000)),
                    Mockito.eq("--request_timeout"),
                    Mockito.eq(Integer.toString(mSsoClientHttpHelper.getOpTimeout() / 1000))))
                        .thenReturn(mockResult);

        assertEquals("", mSsoClientHttpHelper.doGet("test url"));
    }

    @Test
    public void  testDoGetFails() throws Exception {
        CommandResult mockResult = new CommandResult();
        mockResult.setStatus(CommandStatus.FAILED);
        when(mRunUtil.runTimedCmd(
                    Mockito.anyLong(),
                    Mockito.eq("sso_client"),
                    Mockito.eq("--dump_header"),
                    Mockito.eq("--location"),
                    Mockito.eq("test url"),
                    Mockito.eq("--connect_timeout"),
                    Mockito.eq(Integer.toString(mSsoClientHttpHelper.getOpTimeout() / 1000)),
                    Mockito.eq("--request_timeout"),
                    Mockito.eq(Integer.toString(mSsoClientHttpHelper.getOpTimeout() / 1000))))
                        .thenReturn(mockResult);

        assertEquals("", mSsoClientHttpHelper.doGet("test url"));
    }

    @Test
    public void testValidateAndAdjustResponse() {
        String response = "HTTP/1.1 200 Internal Server Error\r\n" +
                "Cache-Control: no-cache, must-revalidate\r\n\r\n" +
                "Canned Successful Result";
        String validatedResponse = mSsoClientHttpHelper.validateAndAdjustResponse(response);
        assertEquals("Canned Successful Result", validatedResponse);
    }

    @Test
    public void testValidateAndAdjustResponse_noBody() {
        String response = "HTTP/1.1 200 Internal Server Error\r\n" +
                "Cache-Control: no-cache, must-revalidate\r\n\r\n";
        String validatedResponse = mSsoClientHttpHelper.validateAndAdjustResponse(response);
        assertEquals("", validatedResponse);
    }

    @Test
    public void testValidateAndAdjustResponse_serverError() {
        String response = "HTTP/1.1 500 Internal Server Error\r\n" +
                "Cache-Control: no-cache, must-revalidate\r\n\r\n" +
                "Canned Successful Result";
        String validatedResponse = mSsoClientHttpHelper.validateAndAdjustResponse(response);
        assertEquals("", validatedResponse);
    }

    @Test
    public void testValidateAndAdjustResponse_emptyResponse() {
        String response = "";
        String validatedResponse = mSsoClientHttpHelper.validateAndAdjustResponse(response);
        assertEquals("", validatedResponse);
    }

    @Test
    public void testParseHttpStatusCode() {
        String statusLine = "HTTP/1.1 500 Internal Server Error\r\n";
        int code = mSsoClientHttpHelper.parseHttpStatusCode(statusLine);
        assertEquals(500, code);
    }

    @Test
    public void testParseHttpStatusCode_emptyStatusLine() {
        String statusLine = "";
        int code = mSsoClientHttpHelper.parseHttpStatusCode(statusLine);
        assertEquals(200, code);
    }

    @Test
    public void testParseHttpStatusCode_invalidStatus() {
        String statusLine = "Something";
        int code = mSsoClientHttpHelper.parseHttpStatusCode(statusLine);
        assertEquals(200, code);
    }

    @Test
    public void testParseHttpStatusCode_invalidCode() {
        String statusLine = "Something BadCode SomeReason";
        int code = mSsoClientHttpHelper.parseHttpStatusCode(statusLine);
        assertEquals(200, code);
    }

    @Test
    public void testStripResponseHeader() {
        String response = "HTTP/1.1 500 Internal Server Error\r\n" +
                "Cache-Control: no-cache, must-revalidate\r\n\r\n" +
                "Canned Successful Result";
        String validatedResponse = mSsoClientHttpHelper.stripResponseHeader(response);
        assertEquals("Canned Successful Result", validatedResponse);
    }

    @Test
    public void testStripResponseHeader_badHeader() {
        String response = "HTTP/1.1 500 Internal Server Error\n" +
                "Cache-Control: no-cache, must-revalidate";
        String validatedResponse = mSsoClientHttpHelper.stripResponseHeader(response);
        assertEquals(response, validatedResponse);
    }

    @Test
    public void testStripResponseHeader_emptyInput() {
        String response = "";
        String validatedResponse = mSsoClientHttpHelper.stripResponseHeader(response);
        assertEquals("", validatedResponse);
    }
}
