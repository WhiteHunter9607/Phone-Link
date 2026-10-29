package com.example.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service after boot (and after the app is updated), if auto-start is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!TrustStore(c).autostart) return
        c.startForegroundService(Intent(c, DeviceService::class.java))
    }
}
