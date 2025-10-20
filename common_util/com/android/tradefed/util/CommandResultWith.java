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
package com.android.tradefed.util;

/** Contains the structured result of a command. */
public class CommandResultWith<T> {

    private final T mValue;

    private final CommandResult mCommandResult;

    private CommandResultWith(T value, CommandResult commandResult) {
        this.mValue = value;
        this.mCommandResult = commandResult;
    }

    public static <T> CommandResultWith<T> success(T value) {
        return new CommandResultWith<>(value, new CommandResult(CommandStatus.SUCCESS));
    }

    public static <T> CommandResultWith<T> success(T value, CommandResult commandResult) {
        return new CommandResultWith<>(value, commandResult);
    }

    public static <T> CommandResultWith<T> error(CommandResult commandResult) {
        return new CommandResultWith<>(null, commandResult);
    }

    public static <T> CommandResultWith<T> errorString(String error) {
        CommandResult commandResult = new CommandResult(CommandStatus.EXCEPTION);
        commandResult.setStderr(error);
        return error(commandResult);
    }

    public boolean isSuccess() {
        return CommandStatus.SUCCESS.equals(mCommandResult.getStatus());
    }

    public T getValue() {
        return mValue;
    }

    public CommandResult getCommandResult() {
        return mCommandResult;
    }
}
