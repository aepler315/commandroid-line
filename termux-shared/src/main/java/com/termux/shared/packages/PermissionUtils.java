package com.termux.shared.packages;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.termux.shared.R;
import com.termux.shared.logger.Logger;

import java.util.Arrays;

public class PermissionUtils {

    public static final int REQUEST_GRANT_STORAGE_PERMISSION = 1000;

    public static final int REQUEST_DISABLE_BATTERY_OPTIMIZATIONS = 2000;
    public static final int REQUEST_GRANT_DISPLAY_OVER_OTHER_APPS_PERMISSION = 2001;

    private static final String LOG_TAG = "PermissionUtils";


    public static boolean checkPermission(Context context, String permission) {
        if (permission == null) return false;
        return checkPermissions(context, new String[]{permission});
    }

    public static boolean checkPermissions(Context context, String[] permissions) {
        if (permissions == null) return false;

        int result;
        for (String p:permissions) {
            result = ContextCompat.checkSelfPermission(context,p);
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }



    public static void requestPermission(Activity activity, String permission, int requestCode) {
        if (permission == null) return;
        requestPermissions(activity, new String[]{permission}, requestCode);
    }

    public static void requestPermissions(Activity activity, String[] permissions, int requestCode) {
        if (activity == null || permissions == null) return;

        int result;
        Logger.showToast(activity, activity.getString(R.string.message_sudo_please_grant_permissions), true);
        try {Thread.sleep(1000);} catch (InterruptedException e) {e.printStackTrace();}

        for (String permission:permissions) {
            result = ContextCompat.checkSelfPermission(activity, permission);
            if (result != PackageManager.PERMISSION_GRANTED) {
                Logger.logDebug(LOG_TAG, "Requesting Permissions: " + Arrays.toString(permissions));
                try {
                    activity.requestPermissions(new String[]{permission}, requestCode);
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to request permissions with request code " + requestCode + ": " + Arrays.toString(permissions), e);
                }
            }
        }
    }



    public static boolean checkDisplayOverOtherAppsPermission(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public static void requestDisplayOverOtherAppsPermission(Activity context, int requestCode) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()));
        context.startActivityForResult(intent, requestCode);
    }

    public static boolean validateDisplayOverOtherAppsPermissionForPostAndroid10(Context context, boolean logResults) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        
        if (!PermissionUtils.checkDisplayOverOtherAppsPermission(context)) {
            if (logResults)
                Logger.logWarn(LOG_TAG, context.getPackageName() + " does not have Display over other apps (SYSTEM_ALERT_WINDOW) permission");
            return false;
        } else {
            if (logResults)
                Logger.logDebug(LOG_TAG, context.getPackageName() + " already has Display over other apps (SYSTEM_ALERT_WINDOW) permission");
            return true;
        }
    }



    public static boolean checkIfBatteryOptimizationsDisabled(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    @SuppressLint("BatteryLife")
    public static void requestDisableBatteryOptimizations(Activity activity, int requestCode) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivityForResult(intent, requestCode);
    }

    /**
     * NeoTerm: whether the app may write to shared storage. At targetSdk 30+ scoped storage ignores
     * WRITE_EXTERNAL_STORAGE (and at 33+ it is never granted), so on API 30+ this checks "All files
     * access" (MANAGE_EXTERNAL_STORAGE) instead.
     */
    public static boolean checkStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return checkPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    /**
     * NeoTerm: request shared storage access. On API 30+ this opens the "All files access" settings
     * screen (the result arrives in {@code onActivityResult}/{@code onResume}, not in
     * {@code onRequestPermissionsResult}); below it requests WRITE_EXTERNAL_STORAGE.
     */
    public static void requestStoragePermission(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivityForResult(intent, requestCode);
            } catch (Exception e) {
                try {
                    activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), requestCode);
                } catch (Exception e2) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open all files access settings", e2);
                }
            }
            return;
        }
        requestPermission(activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE, requestCode);
    }

}
