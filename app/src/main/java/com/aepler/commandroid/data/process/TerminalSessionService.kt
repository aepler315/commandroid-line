// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** STUB: bound foreground service keeping shells alive; completed by the data workstream. */
class TerminalSessionService : Service() {
    inner class LocalBinder : Binder() {
        val service: TerminalSessionService get() = this@TerminalSessionService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder
}
