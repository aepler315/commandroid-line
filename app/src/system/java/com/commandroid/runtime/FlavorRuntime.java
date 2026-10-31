// SPDX-License-Identifier: GPL-3.0-only
package com.commandroid.runtime;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neoterm.runtime.AndroidShellEnvironment;
import com.termux.R;
import com.termux.shared.shell.ShellEnvironmentClient;

import java.io.IOException;

/**
 * "system" runtime: targetSdk 34, Android's /system/bin/sh only. Android denies execve() of files
 * in the app's own data directory at targetSdk 29+, so no Termux prefix can run here.
 */
public final class FlavorRuntime {
    public static final String ID = "system";

    private FlavorRuntime() {}

    /** The system flavor never has an executable Termux prefix. */
    public static boolean hasInstalledPrefix() {
        return false;
    }

    @NonNull
    public static ShellEnvironmentClient shellEnvironment(@NonNull Context context) {
        return new AndroidShellEnvironment(context);
    }

    /** Executable for a manually created session; never probe vendor login/bash/zsh. */
    @Nullable
    public static String defaultExecutable() {
        return "/system/bin/sh";
    }

    public static int runtimeNotice() {
        return R.string.neoterm_runtime_notice;
    }

    /** Creates the private home and temporary directories, then continues on the caller's thread. */
    public static void install(@NonNull android.app.Activity activity, @NonNull Runnable whenDone) throws IOException {
        new AndroidShellEnvironment(activity).prepare();
        whenDone.run();
    }
}
