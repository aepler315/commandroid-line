// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.util.Xml
import com.termux.BuildConfig
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
    private val expectedPackage = "com.commandroid_line"
    private val expectedName = "commandroid-line"

    @Test fun installedIdentityOwnsItsDataWithoutJoiningTermuxSharedUid() {
        val info = packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(expectedPackage, context.packageName)
        assertEquals(expectedPackage, info.packageName)
        assertNull("The app must receive its own Android UID", info.sharedUserId)
        assertEquals(expectedName, context.getString(R.string.application_name))
        assertEquals("commandroid-line", TermuxConstants.TERMUX_APP_NAME)
        assertEquals(context.packageName, TermuxConstants.TERMUX_PACKAGE_NAME)
        assertEquals("/data/data/$expectedPackage/files", TermuxConstants.TERMUX_FILES_DIR_PATH)
        assertEquals("/data/data/$expectedPackage/files/home", TermuxConstants.TERMUX_HOME_DIR_PATH)
        assertEquals("/data/data/$expectedPackage/files/usr", TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        assertFalse(TermuxConstants.TERMUX_FILES_DIR_PATH.startsWith("/data/data/com.termux/"))
    }

    @Test fun userVisibleRuntimeTextNamesThisApp() {
        val texts = listOf(R.string.commandroid_runtime_notice_compat, R.string.commandroid_runtime_notice_compat_no_bootstrap,
            R.string.neoterm_runtime_error,
            R.string.neoterm_runtime_error_body, R.string.neoterm_unsupported_profile_body).map { context.getString(it) }
        texts.forEach { assertFalse(it, it.contains("NeoTerm")) }
        assertTrue(context.getString(R.string.neoterm_runtime_error).startsWith("commandroid-line"))
        assertTrue(context.getString(R.string.neoterm_unsupported_profile_body).contains("Open commandroid-line"))
        // Without a bundled bootstrap the notice must say so and how to get one.
        val fallback = context.getString(R.string.commandroid_runtime_notice_compat_no_bootstrap)
        assertTrue(fallback, fallback.contains("system shell") && fallback.contains("-PcommandroidBootstrapDir"))
    }

    @Test fun manifestComponentsKeepExistingJavaClassesInsideOwnPackage() {
        val launcher = packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull(launcher)
        assertEquals(ComponentName(expectedPackage, "com.termux.app.TermuxActivity"), launcher!!.component)

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

    @Test fun providersAndExternalCommandsUseOnlyOwnAuthorityAndPermission() {
        val info = packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        val documents = info.providers.single { it.name == "com.termux.filepicker.TermuxDocumentsProvider" }
        val files = info.providers.single { it.name == "com.termux.app.TermuxOpenReceiver\$ContentProvider" }
        assertEquals("$expectedPackage.documents", documents.authority)
        assertEquals("$expectedPackage.files", files.authority)
        assertEquals(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, files.authority)

        val permission = info.permissions.single { it.name == "$expectedPackage.permission.RUN_COMMAND" }
        assertEquals(PermissionInfo.PROTECTION_DANGEROUS, permission.protectionLevel)
        assertEquals(permission.name, TermuxConstants.PERMISSION_RUN_COMMAND)
        assertEquals(permission.name, files.readPermission)
        assertEquals(permission.name, files.writePermission)
        val commandService = info.services.single { it.name == "com.termux.app.RunCommandService" }
        assertEquals(permission.name, commandService.permission)
        val resolved = packageManager.resolveService(Intent("$expectedPackage.RUN_COMMAND").setPackage(context.packageName), 0)
        assertNotNull(resolved)
        assertEquals(commandService.name, resolved!!.serviceInfo.name)
        assertNull(packageManager.resolveService(Intent("com.termux.RUN_COMMAND").setPackage(context.packageName), 0))
        assertNull(packageManager.resolveContentProvider("com.termux.documents", 0))
        assertNull(packageManager.resolveContentProvider("com.termux.files", 0))
        assertTrue(info.providers.all { !it.authority.startsWith("com.termux.") && !it.authority.startsWith("com.neoterm.") })
        assertTrue(info.permissions.all { !it.name.startsWith("com.termux.") })
    }

    @Test fun compiledShortcutsLaunchOwnPackageWhileKeepingExistingComponentClasses() {
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
            assertEquals(ComponentName(expectedPackage, targetClass), intent.component)
            assertNotNull(packageManager.getActivityInfo(requireNotNull(intent.component), 0))
        }
        val failsafe = shortcuts.getValue("new_failsafe_session")
        assertTrue(failsafe.getBooleanExtra("$expectedPackage.app.failsafe_session", false))
        assertTrue(failsafe.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false))
        assertFalse(failsafe.hasExtra("com.termux.app.failsafe_session"))
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
