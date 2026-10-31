// SPDX-License-Identifier: GPL-3.0-only
package com.commandroid.runtime;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neoterm.runtime.AndroidShellEnvironment;
import com.termux.R;
import com.termux.app.BootstrapInstaller;
import com.termux.shared.shell.ShellEnvironmentClient;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * "compat" runtime: Termux-compatible execution. targetSdk 28 lets binaries in this app's own
 * prefix ({@link TermuxConstants#TERMUX_PREFIX_DIR_PATH}) execve, so a bootstrap built from source
 * for that prefix runs bash, apt, node and the agent CLIs inside the upstream PTY terminal.
 *
 * <p>Only a bootstrap bundled as an APK asset under {@code bootstrap/} is ever installed. When the
 * build carries none, the app says so and uses /system/bin/sh; it never downloads or substitutes an
 * official com.termux bootstrap, whose binaries hardcode another app's prefix.
 */
public final class FlavorRuntime {
    public static final String ID = "compat";

    private FlavorRuntime() {}

    /** True once a bootstrap's bash exists in this app's own prefix. */
    public static boolean hasInstalledPrefix() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash").isFile();
    }

    @NonNull
    public static ShellEnvironmentClient shellEnvironment(@NonNull Context context) {
        return hasInstalledPrefix() ? new TermuxShellEnvironmentClient() : new AndroidShellEnvironment(context);
    }

    /** {@code null} lets TermuxSession pick $PREFIX/bin/login (upstream behavior) once a prefix exists. */
    @Nullable
    public static String defaultExecutable() {
        return hasInstalledPrefix() ? null : "/system/bin/sh";
    }

    public static int runtimeNotice() {
        return hasInstalledPrefix() ? R.string.commandroid_runtime_notice_compat : R.string.commandroid_runtime_notice_compat_no_bootstrap;
    }

    public static void install(@NonNull Activity activity, @NonNull Runnable whenDone) throws IOException {
        if (hasInstalledPrefix()) {
            whenDone.run();
            return;
        }
        String asset = bundledBootstrapAsset(activity);
        if (asset == null) {
            new AndroidShellEnvironment(activity).prepare();
            whenDone.run();
            return;
        }
        BootstrapInstaller.install(activity, asset, whenDone);
    }

    /** Asset path of the first bundled bootstrap matching a supported ABI, in preference order. */
    @Nullable
    static String bundledBootstrapAsset(@NonNull Context context) {
        for (String abi : Build.SUPPORTED_ABIS) {
            String arch = termuxArch(abi);
            if (arch == null) continue;
            String path = "bootstrap/bootstrap-" + arch + ".zip";
            try (InputStream ignored = context.getAssets().open(path)) {
                return path;
            } catch (IOException absent) {
                // Not bundled for this ABI.
            }
        }
        return null;
    }

    @Nullable
    static String termuxArch(@NonNull String abi) {
        switch (abi) {
            case "arm64-v8a": return "aarch64";
            case "armeabi-v7a": return "arm";
            case "x86": return "i686";
            case "x86_64": return "x86_64";
            default: return null;
        }
    }
}
