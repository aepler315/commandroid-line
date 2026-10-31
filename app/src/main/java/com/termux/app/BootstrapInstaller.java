// SPDX-License-Identifier: GPL-3.0-only
package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.app.utils.CrashUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.file.TermuxFileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.models.errors.Error;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.shell.TermuxTask;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Installs a bootstrap bundled as an APK asset into this app's own prefix.
 *
 * <p>Upstream termux-app v0.118.3 TermuxInstaller logic, changed only to read the archive from
 * assets instead of a native library, and to refuse archive entries or symlink paths that would
 * land outside the staging prefix, and to make ELF and {@code #!} files executable wherever they
 * are (see {@link BootstrapEntries}). The archive must have been built from source for
 * {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH}; tools/bootstrap verifies that before bundling.
 */
public final class BootstrapInstaller {

    private static final String LOG_TAG = "BootstrapInstaller";

    private BootstrapInstaller() {}

    public static void install(final Activity activity, final String assetPath, final Runnable whenDone) {
        Error filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        if (filesDirectoryAccessibleError != null) {
            showBootstrapErrorDialog(activity, whenDone, Error.getMinimalErrorString(filesDirectoryAccessibleError) +
                "\nTERMUX_FILES_DIR: " + MarkdownUtils.getMarkdownCodeForString(TermuxConstants.TERMUX_FILES_DIR_PATH, false));
            return;
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing bootstrap packages from " + assetPath);
                    Error error;

                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }

                    final String stagingRoot = new File(TERMUX_STAGING_PREFIX_DIR_PATH).getCanonicalPath() + File.separator;
                    final byte[] buffer = new byte[8096];
                    final byte[] head = new byte[BootstrapEntries.HEADER_LENGTH];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    try (ZipInputStream zipInput = new ZipInputStream(new BufferedInputStream(activity.getAssets().open(assetPath)))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput, StandardCharsets.UTF_8));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new IOException("Malformed symlink line: " + line);
                                    String newPath = BootstrapEntries.symlinkPath(TERMUX_STAGING_PREFIX_DIR_PATH, parts[1]);
                                    BootstrapEntries.requireInside(stagingRoot, new File(newPath));
                                    symlinks.add(Pair.create(parts[0], newPath));
                                    error = FileUtils.createDirectoryFile(new File(newPath).getParentFile().getAbsolutePath());
                                    if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                BootstrapEntries.requireInside(stagingRoot, targetFile);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = FileUtils.createDirectoryFile((isDirectory ? targetFile : targetFile.getParentFile()).getAbsolutePath());
                                if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }

                                if (!isDirectory) {
                                    int headLength;
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        headLength = BootstrapEntries.copy(zipInput, outStream, buffer, head);
                                    }
                                    // Archive modes are lost; bin/ symlinks into lib/ (npm, npx, corepack)
                                    // need their ELF or #! targets executable too.
                                    if (BootstrapEntries.isExecutable(zipEntryName, head, headLength)) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new IOException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks)
                        Os.symlink(symlink.first, symlink.second);

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR))
                        throw new IOException("Moving prefix staging to prefix directory failed");

                    String secondStage = TERMUX_PREFIX_DIR_PATH + "/etc/termux/bootstrap/termux-bootstrap-second-stage.sh";
                    if (FileUtils.fileExists(secondStage, false)) {
                        Logger.logInfo(LOG_TAG, "Running bootstrap second stage.");
                        ExecutionCommand executionCommand = new ExecutionCommand(-1, secondStage, null, null, null, true, false);
                        executionCommand.commandLabel = "Bootstrap Second Stage Command";
                        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL;
                        TermuxTask termuxTask = TermuxTask.execute(activity, executionCommand, null, new TermuxShellEnvironmentClient(), true);
                        if (termuxTask == null || !executionCommand.isSuccessful() || executionCommand.resultData.exitCode != 0) {
                            showBootstrapErrorDialog(activity, whenDone, MarkdownUtils.getMarkdownCodeForString(executionCommand.toString(), true));
                            // A broken prefix would otherwise be reused on the next start.
                            error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                            if (error != null) Logger.logErrorExtended(LOG_TAG, error.toString());
                            return;
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                    activity.runOnUiThread(whenDone);
                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    private static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);
        CrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            "## Bootstrap Error\n\n" + message + "\n\n" + TermuxUtils.getTermuxDebugMarkdownString(activity), true, true);
        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }
}
