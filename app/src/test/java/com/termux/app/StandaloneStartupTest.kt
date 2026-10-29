// SPDX-License-Identifier: GPL-3.0-only
package com.termux.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.UserManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowProcess
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StandaloneStartupTest {
    @Before fun usePrimaryAndroidUser() {
        // Robolectric uses a random application UID by default; force Android user 0.
        ShadowProcess.setUid(10000)
    }

    @Test fun firstLaunchPreparesOwnDirectoriesWithoutBootstrapNativeLibrary() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var completed = false
        TermuxInstaller.setupBootstrapIfNeeded(activity) { completed = true }
        assertTrue("System-shell startup never completed", completed)
        assertTrue(File(activity.filesDir,"home").isDirectory)
        assertTrue(File(activity.filesDir,"usr/tmp").isDirectory)
    }

    @Test fun secondaryUserCannotInitializeOrStartAShell() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.getSystemService(Context.USER_SERVICE) as UserManager).setIsSystemUser(false)
        assertStartupBlocked(activity)
    }

    @Test fun workProfileCannotInitializeOrStartAShell() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val users = shadowOf(activity.getSystemService(Context.USER_SERVICE) as UserManager)
        users.setIsSystemUser(false)
        users.setManagedProfile(true)
        assertStartupBlocked(activity)
    }

    @Test fun unavailableUserIdentityFailsClosed() {
        val activity = Robolectric.buildActivity(NoUserManagerActivity::class.java).setup().get()
        assertStartupBlocked(activity)
    }

    private fun assertStartupBlocked(activity: Activity) {
        val home = File(activity.filesDir, "home")
        val temp = File(activity.filesDir, "usr/tmp")
        home.deleteRecursively()
        temp.deleteRecursively()
        var completed = false
        TermuxInstaller.setupBootstrapIfNeeded(activity) { completed = true }
        assertFalse("Unsupported profiles must not reach session startup", completed)
        assertFalse("Do not initialize HOME before profile validation", home.exists())
        assertFalse("Do not initialize TMPDIR before profile validation", temp.exists())
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Explain why this Android profile is unsupported", dialog)
        assertTrue(shadowOf(dialog).message.toString().contains("primary Android user"))
        assertTrue(shadowOf(dialog).message.toString().contains("work profiles"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("Acknowledging the unsupported-profile message closes the activity", activity.isFinishing)
    }

    class NoUserManagerActivity : Activity() {
        override fun getSystemService(name: String): Any? =
            if (name == Context.USER_SERVICE) null else super.getSystemService(name)
    }
}
