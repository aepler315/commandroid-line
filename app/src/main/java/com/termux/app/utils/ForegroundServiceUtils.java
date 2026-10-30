// SPDX-License-Identifier: GPL-3.0-only
package com.termux.app.utils;

import android.app.Notification;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ServiceCompat;

/**
 * NeoTerm: SDK 34 foreground service helpers for the upstream Termux services.
 *
 * Services calling these helpers must declare {@code android:foregroundServiceType="specialUse"}
 * and the {@code FOREGROUND_SERVICE_SPECIAL_USE} permission in the manifest, otherwise
 * {@link Service#startForeground(int, Notification)} throws on API 34+.
 */
public final class ForegroundServiceUtils {

    private ForegroundServiceUtils() {}

    /** Start {@code service} in the foreground with the special-use type on API 34+. */
    public static void startSpecialUseForeground(@NonNull Service service, int notificationId,
                                                 @NonNull Notification notification) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            // Before API 34 the type is ignored/legacy behavior; MANIFEST keeps upstream semantics.
            : (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST : 0);
        ServiceCompat.startForeground(service, notificationId, notification, type);
    }

    /** Remove {@code service} from the foreground state and its notification. */
    public static void stopForegroundAndRemoveNotification(@NonNull Service service) {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }
}
