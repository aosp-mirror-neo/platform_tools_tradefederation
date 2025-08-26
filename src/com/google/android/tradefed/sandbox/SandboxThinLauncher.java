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
package com.google.android.tradefed.sandbox;

import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.command.ICommandOptions;
import com.android.tradefed.config.ArgsOptionParser;
import com.android.tradefed.config.ClassNotFoundConfigurationException;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.sandbox.ISandbox;
import com.android.tradefed.sandbox.SandboxConfigDump.DumpCmd;
import com.android.tradefed.sandbox.SandboxConfigUtil;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.keystore.IKeyStoreClient;

import com.google.android.tradefed.build.TfLaunchControlProvider;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Thin launcher that attempts to create the versioned context early in order to potentially get a
 * configuration that does not exist in the parent.
 */
public class SandboxThinLauncher {

    private final ISandbox mSandbox;
    private final ITestLogger mLogger;
    private final String[] mCommandLineArgs;
    private final IRunUtil mRunUtil;
    private final File mGlobalConfigFile;

    private File mConfigFile = null;

    /** Ctor. */
    public SandboxThinLauncher(
            ISandbox sandbox,
            ITestLogger logger,
            String[] args,
            IRunUtil runUtil,
            File globalConfig) {
        mSandbox = sandbox;
        mLogger = logger;
        mCommandLineArgs = args;
        mRunUtil = runUtil;
        mGlobalConfigFile = globalConfig;
    }

    /**
     * Attempt loading the sandbox context in order to get the original configuration.
     *
     * @return The file pointing to the configuration that was generated. null if it failed.
     */
    private File attemptLoading() {
        IInvocationContext mStubContext = new InvocationContext();
        mStubContext.setTestTag("thin-launcher-context");
        try {
            final List<String> listArgs = new ArrayList<>(Arrays.asList(mCommandLineArgs));
            if (listArgs.isEmpty()) {
                throw new ConfigurationException(
                        "Could not find any args in the command line. Not even a config name.");
            }
            // First arg is config name
            listArgs.remove(0);
            // 1. Populate stub context with the args, only extract the CommandOptions options.
            ICommandOptions cmdOptions = new CommandOptions();
            TfLaunchControlProvider provider = new TfLaunchControlProvider();
            ArgsOptionParser parser = new ArgsOptionParser(cmdOptions, provider);
            parser.parseBestEffort(listArgs, true);
            mStubContext.addInvocationAttributes(cmdOptions.getInvocationData());

            // We create a fake temporary non-versioned config out of the command line args to
            // help to download the proper environment.
            IConfiguration nonVersionedFakeConfig =
                    new Configuration("Fake config", "Thin Launcher configuration");
            nonVersionedFakeConfig.setBuildProvider(provider);

            // 2. Create a working dir
            File workingDir =
                    mSandbox.getTradefedSandboxEnvironment(
                            mStubContext, nonVersionedFakeConfig, mLogger, mCommandLineArgs);

            // 3. Create a classpath out of the working dir
            String classpath = mSandbox.createClasspath(workingDir);

            // 4. Run the dump against that classpath
            return dumpConfig(classpath);
        } catch (Exception e) {
            CLog.e(e);
            FileUtil.deleteFile(mConfigFile);
            mSandbox.tearDown();
        }
        return null;
    }

    /**
     * Create the {@link IConfiguration} out of the file that was loaded.
     *
     * @param keyStoreClient The {@link IKeyStoreClient} to use when loading the configuration.
     * @return an {@link IConfiguration} created fully from the version artifact.
     */
    public IConfiguration createConfiguration(IKeyStoreClient keyStoreClient) {
        mConfigFile = attemptLoading();
        if (mConfigFile == null || !mConfigFile.exists()) {
            return null;
        }

        try {
            IConfiguration config =
                    ConfigurationFactory.getInstance()
                            .createConfigurationFromArgs(
                                    new String[] {mConfigFile.getAbsolutePath()},
                                    null,
                                    keyStoreClient);
            // Reset the command line to the original one.
            config.setCommandLine(mCommandLineArgs);
            config.setConfigurationObject(Configuration.SANDBOX_TYPE_NAME, mSandbox);
            return config;
        } catch (ClassNotFoundConfigurationException e) {
            // TODO: if we reach here, that means we managed to dump the config in the sandbox
            // but we cannot load it in the parent. We need a powerful resolution of class.
            CLog.e(
                    "Non versioned classes '%s' was found in sandbox but not in parent.",
                    e.getRejectedObjects());
            CLog.e(e);
            return null;
        } catch (ConfigurationException e) {
            CLog.e(e);
            return null;
        } finally {
            // Delete the config file since we have the loaded object.
            FileUtil.deleteFile(mConfigFile);
        }
    }

    /** Actually dumps the configuration file. */
    @VisibleForTesting
    File dumpConfig(String classpath) throws ConfigurationException, IOException {
        return SandboxConfigUtil.dumpConfigForVersion(
                classpath,
                mRunUtil,
                mCommandLineArgs,
                DumpCmd.NON_VERSIONED_CONFIG,
                mGlobalConfigFile);
    }
}
