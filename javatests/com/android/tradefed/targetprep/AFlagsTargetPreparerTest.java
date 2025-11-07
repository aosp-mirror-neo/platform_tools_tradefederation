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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.flag.AFlagsFeatureFlag;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;

@RunWith(JUnit4.class)
public class AFlagsTargetPreparerTest {
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private TestInformation mTestInfo;
    @Mock private ITestDevice mDevice;

    private CommandResult mCommandResult;

    private AFlagsTargetPreparer mPreparer;

    @Before
    public void setUp() throws Exception {
        mPreparer = new AFlagsTargetPreparer();

        when(mTestInfo.getDevice()).thenReturn(mDevice);

        mCommandResult = new CommandResult(CommandStatus.SUCCESS);
        when(mDevice.executeShellV2Command(anyString())).thenReturn(mCommandResult);
    }

    @Test
    public void testSetUpAndTearDown_setsAndRestoresDefaultFlags() throws Exception {
        AFlagsFeatureFlag f =
                new AFlagsFeatureFlag(
                        "f1",
                        AFlagsFeatureFlag.State.DISABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.DEFAULT,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        AFlagsFeatureFlag f2 =
                new AFlagsFeatureFlag(
                        "f2",
                        AFlagsFeatureFlag.State.ENABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.DEFAULT,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        setInitialFlags(f, f2);

        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f1=true");
        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f2=false");

        mPreparer.setUp(mTestInfo);
        verify(mDevice).executeShellV2Command("su root aflags list");
        verify(mDevice).executeShellV2Command("su root aflags enable 'f1'");
        verify(mDevice).executeShellV2Command("su root aflags disable 'f2'");
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);

        clearInvocations(mDevice);
        mPreparer.tearDown(mTestInfo, null);
        verify(mDevice).executeShellV2Command("su root aflags unset 'f1'");
        verify(mDevice).executeShellV2Command("su root aflags unset 'f2'");
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);
    }

    @Test
    public void testSetUpAndTearDown_setsAndRestoresLocalFlags() throws Exception {
        AFlagsFeatureFlag f =
                new AFlagsFeatureFlag(
                        "f1",
                        AFlagsFeatureFlag.State.DISABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.LOCAL,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        AFlagsFeatureFlag f2 =
                new AFlagsFeatureFlag(
                        "f2",
                        AFlagsFeatureFlag.State.ENABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.LOCAL,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        setInitialFlags(f, f2);

        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f1=true");
        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f2=false");

        mPreparer.setUp(mTestInfo);
        verify(mDevice).executeShellV2Command("su root aflags list");
        verify(mDevice).executeShellV2Command("su root aflags enable 'f1'");
        verify(mDevice).executeShellV2Command("su root aflags disable 'f2'");
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);

        clearInvocations(mDevice);
        mPreparer.tearDown(mTestInfo, null);
        verify(mDevice).executeShellV2Command("su root aflags disable 'f1'");
        verify(mDevice).executeShellV2Command("su root aflags enable 'f2'");
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);
    }

    @Test
    public void testSetUpAndTearDown_setsAndRestoresServerFlags() throws Exception {
        AFlagsFeatureFlag f =
                new AFlagsFeatureFlag(
                        "f1",
                        AFlagsFeatureFlag.State.DISABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.UNKNOWN,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        AFlagsFeatureFlag f2 =
                new AFlagsFeatureFlag(
                        "f2",
                        AFlagsFeatureFlag.State.ENABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.UNKNOWN,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        setInitialFlags(f, f2);

        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f1=true");
        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f2=false");

        mPreparer.setUp(mTestInfo);
        verify(mDevice).executeShellV2Command("su root aflags list");
        verify(mDevice).executeShellV2Command("su root aflags enable 'f1'");
        verify(mDevice).executeShellV2Command("su root aflags disable 'f2'");
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);

        clearInvocations(mDevice);
        mPreparer.tearDown(mTestInfo, null);
        verify(mDevice).executeShellV2Command("su root aflags unset 'f1'");
        verify(mDevice).executeShellV2Command("su root aflags unset 'f2'");
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);
    }

    @Test(expected = TargetSetupError.class)
    public void testSetUpAndTearDown_cannotSetReadOnlyFlag() throws Exception {
        AFlagsFeatureFlag f =
                new AFlagsFeatureFlag(
                        "f1",
                        AFlagsFeatureFlag.State.DISABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.DEFAULT,
                        AFlagsFeatureFlag.Mutability.READ_ONLY,
                        "system");
        setInitialFlags(f);

        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f1=true");
        mPreparer.setUp(mTestInfo);
    }

    @Test
    public void testSetUpAndTearDown_doesNotSetUnchangedFlag() throws Exception {
        AFlagsFeatureFlag f =
                new AFlagsFeatureFlag(
                        "f1",
                        AFlagsFeatureFlag.State.DISABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.DEFAULT,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        AFlagsFeatureFlag f2 =
                new AFlagsFeatureFlag(
                        "f2",
                        AFlagsFeatureFlag.State.ENABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.LOCAL,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        setInitialFlags(f, f2);

        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f1=false");
        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f2=true");

        mPreparer.setUp(mTestInfo);
        verify(mDevice).executeShellV2Command("su root aflags list");
        verifyNoMoreInteractions(mDevice);

        clearInvocations(mDevice);
        mPreparer.tearDown(mTestInfo, null);
        verifyNoMoreInteractions(mDevice);
    }

    @Test
    public void testSetUpAndTearDown_doesSetsUnchangedDefaultFlag_withForceSet() throws Exception {
        AFlagsFeatureFlag f =
                new AFlagsFeatureFlag(
                        "f1",
                        AFlagsFeatureFlag.State.DISABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.DEFAULT,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        AFlagsFeatureFlag f2 =
                new AFlagsFeatureFlag(
                        "f2",
                        AFlagsFeatureFlag.State.ENABLED,
                        AFlagsFeatureFlag.State.NONE,
                        AFlagsFeatureFlag.Setter.LOCAL,
                        AFlagsFeatureFlag.Mutability.READ_WRITE,
                        "system");
        setInitialFlags(f, f2);

        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f1=false");
        new OptionSetter(mPreparer).setOptionValue("flag-value", "namespace/f2=true");
        new OptionSetter(mPreparer).setOptionValue("force-set", "true");

        mPreparer.setUp(mTestInfo);
        verify(mDevice).executeShellV2Command("su root aflags list");
        verify(mDevice).executeShellV2Command("su root aflags disable 'f1'");
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);

        clearInvocations(mDevice);
        mPreparer.tearDown(mTestInfo, null);
        verify(mDevice).executeShellV2Command("su root aflags unset 'f1'");
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);
    }

    private void setInitialFlags(AFlagsFeatureFlag... flags) {
        String allOutput =
                Arrays.stream(flags)
                        .map(AFlagsFeatureFlag::toString)
                        .reduce((curr, next) -> curr + "\n" + next)
                        .get();
        mCommandResult.setStdout(allOutput + "\n");
    }
}
