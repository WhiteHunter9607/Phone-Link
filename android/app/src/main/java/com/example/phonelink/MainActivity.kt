package com.example.phonelink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*

class MainActivity : Activity() {
    private lateinit var store: TrustStore
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var serviceBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var perms: TextView
    private var lastSig = "?"
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 1000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (intent.getBooleanExtra("ring", false)) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        store = TrustStore(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

        status = TextView(this).apply { textSize = 18f }
        serviceBtn = btn("Start service") { toggleService() }
        stopBtn = btn("STOP ALARM") {
            startService(Intent(this, DeviceService::class.java).setAction("STOP_RING"))
        }
        val accept = Switch(this).apply {
            text = "Accept controllers"; isChecked = store.accepting
            setOnCheckedChangeListener { _, v -> store.accepting = v }
        }
        val battery = btn("Battery optimization settings") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        val dnd = btn("Allow Do Not Disturb access (ringtone volume)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        perms = TextView(this).apply { textSize = 14f }
        val hint = TextView(this).apply {
            setTextIsSelectable(true); textSize = 12f
            text = "One-time, from a PC with USB debugging (needed for the Location toggle):\n" +
                "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n\n" +
                "Vivo: also allow Autostart, background pop-ups and no battery restriction for this app."
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        listOf(status, serviceBtn, stopBtn, accept, perms, dnd, battery, hint,
            TextView(this).apply { text = "\nTrusted controllers"; textSize = 16f }, list)
            .forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun toggleService() {
        val i = Intent(this, DeviceService::class.java)
        if (Status.running) stopService(i) else startForegroundService(i)
    }

    private fun refresh() {
        status.text = if (Status.running) Status.text else "Service stopped"
        serviceBtn.text = if (Status.running) "Stop service" else "Start service"
        stopBtn.visibility = if (Status.ringing) View.VISIBLE else View.GONE
        val secure = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val policy = getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
        perms.text = "Location toggle: " + (if (secure) "ready" else "needs the adb command below") +
            "\nRingtone volume to 0: " + (if (policy) "ready" else "needs Do Not Disturb access")
        val c = store.all()
        val sig = c.joinToString { it.id }
        if (sig != lastSig) {
            lastSig = sig
            list.removeAllViews()
            if (c.isEmpty()) list.addView(TextView(this).apply { text = "None yet" })
            c.forEach { ctl ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(TextView(this).apply {
                    text = "${ctl.name} (${ctl.type})"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this).apply {
                    text = "Revoke"
                    setOnClickListener { store.remove(ctl.id); lastSig = "?" }
                })
                list.addView(row)
            }
        }
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }
}
