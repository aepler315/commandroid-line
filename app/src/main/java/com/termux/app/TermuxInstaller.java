package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.os.UserManager;
import android.system.Os;
import com.commandroid.runtime.AppRuntime;
import com.termux.R;
import com.termux.app.utils.CrashUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.models.errors.Error;
import com.termux.shared.termux.TermuxConstants;
import java.io.File;
import java.io.IOException;

/** Startup. com.commandroid.runtime.AppRuntime decides what is installed. */
final class TermuxInstaller {
    /** Initialize only this app's own sandbox; never extract an official com.termux bootstrap. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        // NeoTerm still inherits static primary-user paths in session properties and providers.
        // Do not initialize a second profile with a different context.filesDir layout.
        if (!isSupportedAndroidUser(activity)) {
            new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.neoterm_unsupported_profile)
                .setMessage(R.string.neoterm_unsupported_profile_body)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> activity.finish())
                .show();
            return;
        }

        try {
            AppRuntime.install(activity, whenDone);
        } catch (IOException | SecurityException error) {
            new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.neoterm_runtime_error)
                .setMessage(R.string.neoterm_runtime_error_body)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> activity.finish())
                .show();
        }
    }

    private static boolean isSupportedAndroidUser(Context context) {
        try {
            UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return users != null && users.isSystemUser();
        } catch (SecurityException error) {
            // Unknown user identity must not permit initialization with primary-user paths.
            return false;
        }
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Error.getErrorMarkdownString(error), true, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    final File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 1) {
                        for (int i = 1; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)), true, true);
                }
            }
        }.start();
    }

}
