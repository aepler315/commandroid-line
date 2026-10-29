// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.util.Xml
import com.termux.R
import com.termux.shared.termux.TermuxConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Exercises Android's merged manifest and compiled shortcuts, not source-file text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class StandaloneIdentityTest {
    private val context get() = RuntimeEnvironment.application
    private val packageManager get() = context.packageManager

    @Test fun installedIdentityOwnsItsDataWithoutJoiningTermuxSharedUid() {
        val info = packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("com.neoterm", context.packageName)
        assertEquals("com.neoterm", info.packageName)
        assertNull("NeoTerm must receive its own Android UID", info.sharedUserId)
        assertEquals("NeoTerm", context.getString(R.string.application_name))
        assertEquals("NeoTerm", TermuxConstants.TERMUX_APP_NAME)
        assertEquals(context.packageName, TermuxConstants.TERMUX_PACKAGE_NAME)
        assertEquals("/data/data/com.neoterm/files", TermuxConstants.TERMUX_FILES_DIR_PATH)
        assertEquals("/data/data/com.neoterm/files/home", TermuxConstants.TERMUX_HOME_DIR_PATH)
        assertEquals("/data/data/com.neoterm/files/usr", TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        assertFalse(TermuxConstants.TERMUX_FILES_DIR_PATH.startsWith("/data/data/com.termux/"))
    }

    @Test fun manifestComponentsKeepExistingJavaClassesInsideNeoTermPackage() {
        val launcher = packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull(launcher)
        assertEquals(ComponentName("com.neoterm", "com.termux.app.TermuxActivity"), launcher!!.component)

        val classes = listOf(
            TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME to "com.termux.app.TermuxActivity",
            TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME to "com.termux.app.activities.SettingsActivity",
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME to "com.termux.app.TermuxService",
            TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE_NAME to "com.termux.app.RunCommandService"
        )
        classes.forEach { (actual, expected) ->
            assertEquals(expected, actual)
            assertNotNull(Class.forName(actual))
        }
        val info = packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
        val declaredClasses = info.activities.map { it.name } + info.services.map { it.name }
        classes.forEach { (name, _) -> assertTrue("Undeclared component $name", name in declaredClasses) }
    }

    @Test fun providersAndExternalCommandsUseOnlyNeoTermAuthorityAndPermission() {
        val info = packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        val documents = info.providers.single { it.name == "com.termux.filepicker.TermuxDocumentsProvider" }
        val files = info.providers.single { it.name == "com.termux.app.TermuxOpenReceiver\$ContentProvider" }
        assertEquals("com.neoterm.documents", documents.authority)
        assertEquals("com.neoterm.files", files.authority)
        assertEquals(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, files.authority)

        val permission = info.permissions.single { it.name == "com.neoterm.permission.RUN_COMMAND" }
        assertEquals(PermissionInfo.PROTECTION_DANGEROUS, permission.protectionLevel)
        assertEquals(permission.name, TermuxConstants.PERMISSION_RUN_COMMAND)
        assertEquals(permission.name, files.readPermission)
        assertEquals(permission.name, files.writePermission)
        val commandService = info.services.single { it.name == "com.termux.app.RunCommandService" }
        assertEquals(permission.name, commandService.permission)
        val resolved = packageManager.resolveService(Intent("com.neoterm.RUN_COMMAND").setPackage(context.packageName), 0)
        assertNotNull(resolved)
        assertEquals(commandService.name, resolved!!.serviceInfo.name)
        assertNull(packageManager.resolveService(Intent("com.termux.RUN_COMMAND").setPackage(context.packageName), 0))
        assertNull(packageManager.resolveContentProvider("com.termux.documents", 0))
        assertNull(packageManager.resolveContentProvider("com.termux.files", 0))
        assertTrue(info.providers.all { !it.authority.startsWith("com.termux.") })
        assertTrue(info.permissions.all { !it.name.startsWith("com.termux.") })
    }

    @Test fun compiledShortcutsLaunchNeoTermWhileKeepingExistingComponentClasses() {
        val shortcuts = mutableMapOf<String, Intent>()
        val parser = context.resources.getXml(R.xml.shortcuts)
        try {
            var shortcutId: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                if (parser.name == "shortcut") {
                    shortcutId = parser.getAttributeValue(ANDROID_NAMESPACE, "shortcutId")
                } else if (parser.name == "intent") {
                    shortcuts[requireNotNull(shortcutId)] = Intent.parseIntent(context.resources, parser, Xml.asAttributeSet(parser))
                }
            }
        } finally {
            parser.close()
        }
        assertEquals(setOf("new_session", "new_failsafe_session", "settings"), shortcuts.keys)
        shortcuts.forEach { (id, intent) ->
            val targetClass = if (id == "settings") "com.termux.app.activities.SettingsActivity" else "com.termux.app.TermuxActivity"
            assertEquals(ComponentName("com.neoterm", targetClass), intent.component)
            assertNotNull(packageManager.getActivityInfo(requireNotNull(intent.component), 0))
        }
        val failsafe = shortcuts.getValue("new_failsafe_session")
        assertTrue(failsafe.getBooleanExtra("com.neoterm.app.failsafe_session", false))
        assertTrue(failsafe.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false))
        assertFalse(failsafe.hasExtra("com.termux.app.failsafe_session"))
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
