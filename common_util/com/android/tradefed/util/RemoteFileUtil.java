/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.util;

import com.android.tradefed.log.LogUtil.CLog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Utility class to handle file from a remote instance */
public class RemoteFileUtil {

    private static final String DEFAULT_SSH_PORT = "22";

    /**
     * Fetch a remote file in the container instance.
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remoteFilePath The remote path where to find the file.
     * @return The pulled filed if successful, null otherwise
     */
    public static File fetchRemoteFile(
            String host,
            String user,
            File sshPrivateKey,
            IRunUtil runUtil,
            long timeout,
            String remoteFilePath) {
        return fetchRemoteFile(
                host,
                user,
                sshPrivateKey,
                new ArrayList<String>(),
                runUtil,
                timeout,
                remoteFilePath);
    }

    /**
     * Fetch a remote file in the container instance.
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param scpArgs extra args to be passed to the scp command
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remoteFilePath The remote path where to find the file.
     * @return The pulled filed if successful, null otherwise
     */
    public static File fetchRemoteFile(
            String host,
            String user,
            File sshPrivateKey,
            List<String> scpArgs,
            IRunUtil runUtil,
            long timeout,
            String remoteFilePath) {
        String fileName = new File(remoteFilePath).getName();
        File localFile = null;
        try {
            localFile =
                    FileUtil.createTempFile(
                            FileUtil.getBaseName(fileName) + "_", FileUtil.getExtension(fileName));
            if (fetchRemoteFile(
                    host,
                    user,
                    sshPrivateKey,
                    scpArgs,
                    runUtil,
                    timeout,
                    remoteFilePath,
                    localFile)) {
                return localFile;
            }
        } catch (IOException e) {
            CLog.e(e);
        }
        FileUtil.deleteFile(localFile);
        return null;
    }

    /**
     * Fetch a remote file in the device or container instance.
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remoteFilePath The remote path where to find the file.
     * @param localFile The local {@link File} where the remote file will be pulled
     * @return True if successful, False otherwise
     */
    public static boolean fetchRemoteFile(
            String host,
            String user,
            File sshPrivateKey,
            IRunUtil runUtil,
            long timeout,
            String remoteFilePath,
            File localFile) {
        return fetchRemoteFile(
                host, user, sshPrivateKey, null, runUtil, timeout, remoteFilePath, localFile);
    }

    /**
     * Fetch a remote file in the device or container instance.
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remoteFilePath The remote path where to find the file.
     * @param localFile The local {@link File} where the remote file will be pulled
     * @param scpArgs extra args to be passed to the scp command
     * @return True if successful, False otherwise
     */
    public static boolean fetchRemoteFile(
            String host,
            String user,
            File sshPrivateKey,
            List<String> scpArgs,
            IRunUtil runUtil,
            long timeout,
            String remoteFilePath,
            File localFile) {
        return internalScpExec(
                host,
                user,
                sshPrivateKey,
                scpArgs,
                runUtil,
                timeout,
                remoteFilePath,
                localFile,
                GceRemoteCmdFormatter.ScpMode.PULL);
    }

    /**
     * Fetch a remote directory from the remote host.
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remoteDirPath The remote path where to find the directory.
     * @param localDir The local directory where to put the pulled files.
     * @return True if successful, False otherwise
     */
    public static boolean fetchRemoteDir(
            String host,
            String user,
            File sshPrivateKey,
            IRunUtil runUtil,
            long timeout,
            String remoteDirPath,
            File localDir) {
        return fetchRemoteDir(
                host,
                user,
                sshPrivateKey,
                new ArrayList<String>(),
                runUtil,
                timeout,
                remoteDirPath,
                localDir);
    }

    /**
     * Fetch a remote directory from the remote host.
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param scpArgs extra args to be passed to the scp command
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remoteDirPath The remote path where to find the directory.
     * @param localDir The local directory where to put the pulled files.
     * @return True if successful, False otherwise
     */
    public static boolean fetchRemoteDir(
            String host,
            String user,
            File sshPrivateKey,
            List<String> scpArgs,
            IRunUtil runUtil,
            long timeout,
            String remoteDirPath,
            File localDir) {
        if (scpArgs == null) {
            scpArgs = new ArrayList<>();
        }
        scpArgs.add("-r");
        return internalScpExec(
                host,
                user,
                sshPrivateKey,
                scpArgs,
                runUtil,
                timeout,
                remoteDirPath,
                localDir,
                GceRemoteCmdFormatter.ScpMode.PULL);
    }

    /**
     * Fetch a remote directory from the remote host.
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remoteDirPath The remote path where to find the directory.
     * @return The pulled directory {@link File} if successful, null otherwise
     */
    public static File fetchRemoteDir(
            String host,
            String user,
            File sshPrivateKey,
            IRunUtil runUtil,
            long timeout,
            String remoteDirPath) {
        return fetchRemoteDir(
                host,
                user,
                sshPrivateKey,
                new ArrayList<String>(),
                runUtil,
                timeout,
                remoteDirPath);
    }

    /**
     * Fetch a remote directory from the remote host.
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remoteDirPath The remote path where to find the directory.
     * @param scpArgs extra args to be passed to the scp command
     * @return The pulled directory {@link File} if successful, null otherwise
     */
    public static File fetchRemoteDir(
            String host,
            String user,
            File sshPrivateKey,
            List<String> scpArgs,
            IRunUtil runUtil,
            long timeout,
            String remoteDirPath) {
        if (scpArgs == null) {
            scpArgs = new ArrayList<>();
        }
        scpArgs.add("-r");
        String dirName = new File(remoteDirPath).getName();
        File localFile = null;
        try {
            localFile = FileUtil.createTempDir(dirName);
            if (internalScpExec(
                    host,
                    user,
                    sshPrivateKey,
                    scpArgs,
                    runUtil,
                    timeout,
                    remoteDirPath,
                    localFile,
                    GceRemoteCmdFormatter.ScpMode.PULL)) {
                return localFile;
            }
        } catch (IOException e) {
            CLog.e(e);
        }
        FileUtil.deleteFile(localFile);
        return null;
    }

    /**
     * Check if a file (or directory) exists on the remote instance
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remotePath The remote path where to find the file.
     * @return whether the file exists or not
     */
    public static boolean doesRemoteFileExist(
            String host,
            String user,
            File sshPrivateKey,
            IRunUtil runUtil,
            long timeout,
            String remotePath) {
        return doesRemoteFileExist(
                host, user, sshPrivateKey, runUtil, timeout, remotePath, DEFAULT_SSH_PORT);
    }

    /**
     * Check if a file (or directory) exists on the remote instance
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKey The file containing the ssh key to use.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remotePath The remote path where to find the file.
     * @param sshPortNumber port number for ssh.
     * @return whether the file exists or not
     */
    public static boolean doesRemoteFileExist(
            String host,
            String user,
            File sshPrivateKey,
            IRunUtil runUtil,
            long timeout,
            String remotePath,
            String sshPortNumber) {
        List<String> sshCmd =
                GceRemoteCmdFormatter.getSshCommand(
                        sshPrivateKey, null, user, host, "-p", sshPortNumber, "ls", remotePath);
        CommandResult resSsh = runUtil.runTimedCmd(timeout, sshCmd.toArray(new String[0]));
        return resSsh != null && CommandStatus.SUCCESS.equals(resSsh.getStatus());
    }

    /**
     * Push a {@link File} from the local host to the remote instance
     *
     * @param host The remote host.
     * @param user The user on the remote host
     * @param sshPrivateKeyPath The file containing the ssh key to use.
     * @param scpArgs extra args to be passed to the scp command
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeout in millisecond for the fetch to complete
     * @param remoteFilePath The remote path where to find the file.
     * @param localFile The local {@link File} where the remote file will be pulled
     * @return True if successful, False otherwise
     */
    public static boolean pushFileToRemote(
            String host,
            String user,
            File sshPrivateKey,
            List<String> scpArgs,
            IRunUtil runUtil,
            long timeout,
            String remoteFilePath,
            File localFile) {
        return internalScpExec(
                host,
                user,
                sshPrivateKey,
                scpArgs,
                runUtil,
                timeout,
                remoteFilePath,
                localFile,
                GceRemoteCmdFormatter.ScpMode.PUSH);
    }

    private static boolean internalScpExec(
            String host,
            String user,
            File sshPrivateKey,
            List<String> scpArgs,
            IRunUtil runUtil,
            long timeout,
            String remoteFilePath,
            File localFile,
            GceRemoteCmdFormatter.ScpMode mode) {
        List<String> scpCmd =
                GceRemoteCmdFormatter.getScpCommand(
                        sshPrivateKey,
                        scpArgs,
                        user,
                        host,
                        remoteFilePath,
                        localFile.getAbsolutePath(),
                        mode);
        CommandResult resScp = runUtil.runTimedCmd(timeout, scpCmd.toArray(new String[0]));
        if (!CommandStatus.SUCCESS.equals(resScp.getStatus())) {
            StringBuilder builder = new StringBuilder();
            builder.append("Issue when ");
            if (GceRemoteCmdFormatter.ScpMode.PULL.equals(mode)) {
                builder.append("pulling ");
            } else {
                builder.append("pushing ");
            }
            builder.append(String.format("file, status: %s", resScp.getStatus()));
            CLog.e(builder.toString());
            CLog.e("%s", resScp.getStderr());
            return false;
        } else {
            return true;
        }
    }
}
