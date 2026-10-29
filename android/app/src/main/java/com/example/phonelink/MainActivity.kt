package com.example.phonelink

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

class MainActivity : Activity() {
    private val BG = Color.parseColor("#0F1115")
    private val CARD = Color.parseColor("#1A1D24")
    private val LINE = Color.parseColor("#2A2F3A")
    private val TEXT = Color.parseColor("#E8EAF0")
    private val DIM = Color.parseColor("#8B93A5")
    private val ACCENT = Color.parseColor("#4F8CFF")
    private val OK = Color.parseColor("#3DDC84")
    private val BAD = Color.parseColor("#FF5D5D")
    private val WAIT = Color.parseColor("#FFB84D")
    private val NEUTRAL = Color.parseColor("#2A2F3A")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var store: TrustStore
    private val h = Handler(Looper.getMainLooper())
    private lateinit var subtitle: TextView
    private lateinit var dot: View
    private lateinit var status: TextView
    private lateinit var serviceBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var locChip: TextView
    private lateinit var dndChip: TextView
    private lateinit var adbHint: TextView
    private lateinit var list: LinearLayout
    private var lastSig = "?"
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 1000) }
    }

    // ---------- small UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun label(text: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(title: String? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 16, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        if (title != null) {
            addView(label(title.uppercase(), 12f, DIM, true).apply {
                letterSpacing = 0.08f
                setPadding(0, 0, 0, dp(10))
            })
        }
    }

    private fun button(text: String, color: Int = ACCENT, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        background = shape(color, 12)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun smallButton(text: String, color: Int = NEUTRAL, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(TEXT)
        textSize = 13f
        background = shape(color, 10)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(36)).apply { leftMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text
        isChecked = checked
        setTextColor(TEXT)
        textSize = 15f
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun chip(): TextView = TextView(this).apply {
        textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun setChip(c: TextView, ok: Boolean, okText: String, badText: String) {
        c.text = if (ok) okText else badText
        c.setTextColor(if (ok) OK else WAIT)
        c.background = shape(if (ok) Color.parseColor("#143A26") else Color.parseColor("#3D2E12"), 20)
    }

    private fun permRow(text: String, chip: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(label(text).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
        addView(chip)
    }

    // ---------- screen ----------
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
        @Suppress("DEPRECATION")
        window.statusBarColor = BG
        store = TrustStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        root.addView(label("PhoneLink", 28f, TEXT, true))
        subtitle = label("", 14f, DIM)
        root.addView(subtitle)

        // status
        val sc = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) }
        }
        status = label("", 16f)
        row.addView(dot)
        row.addView(status)
        sc.addView(row)
        serviceBtn = button("Start service") { toggleService() }
        stopBtn = button("Stop alarm", BAD) {
            startService(Intent(this, DeviceService::class.java).setAction("STOP_RING"))
        }
        sc.addView(serviceBtn)
        sc.addView(stopBtn)
        root.addView(sc)

        // phone name
        val nc = card("This phone's name")
        val nameEdit = EditText(this).apply {
            setText(store.phoneName)
            setTextColor(TEXT)
            setHintTextColor(DIM)
            hint = "e.g. Living room phone"
            background = shape(Color.parseColor("#12151B"), 10, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            filters = arrayOf(InputFilter.LengthFilter(20))
            setSingleLine(true)
        }
        nc.addView(nameEdit)
        nc.addView(button("Save name") { saveName(nameEdit.text.toString()) })
        nc.addView(label(
            "Shown to controllers. It is also set as this phone's Bluetooth name, so controllers can find it when they scan.",
            12f, DIM
        ).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(nc)

        // permissions
        val pc = card("Permissions")
        locChip = chip()
        dndChip = chip()
        pc.addView(permRow("Location on/off", locChip))
        adbHint = label(
            "Run once from a PC with USB debugging:\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
            12f, DIM
        ).apply { setTextIsSelectable(true); setPadding(0, dp(4), 0, dp(8)) }
        pc.addView(adbHint)
        pc.addView(permRow("Ringtone volume down to 0", dndChip))
        pc.addView(button("Allow Do Not Disturb access", NEUTRAL) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        pc.addView(button("Battery settings", NEUTRAL) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        root.addView(pc)

        // trusted controllers
        val tc = card("Trusted controllers")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tc.addView(list)
        root.addView(tc)

        // settings
        val st = card("Settings")
        st.addView(switchRow("Accept controllers", store.accepting) { store.accepting = it })
        st.addView(switchRow("Start when the phone boots", store.autostart) { store.autostart = it })
        root.addView(st)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        })
    }

    private fun saveName(raw: String) {
        val n = raw.replace("|", " ").replace("\n", " ").trim().take(20)
        if (n.isEmpty()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
            return
        }
        store.phoneName = n
        var btOk = false
        try { btOk = BluetoothAdapter.getDefaultAdapter()?.setName(n) == true } catch (_: SecurityException) {}
        if (Status.running) startService(Intent(this, DeviceService::class.java).setAction("RENAME"))
        Toast.makeText(
            this,
            if (btOk) "Saved" else "Saved. Turn Bluetooth on to change the name controllers see while scanning.",
            Toast.LENGTH_LONG
        ).show()
        refresh()
    }

    private fun toggleService() {
        val i = Intent(this, DeviceService::class.java)
        if (Status.running) stopService(i) else startForegroundService(i)
    }

    private fun refresh() {
        val running = Status.running
        subtitle.text = store.phoneName
        status.text = if (running) Status.text else "Service stopped"
        val color = when {
            !running -> BAD
            Status.text.startsWith("Ready") -> OK
            else -> WAIT
        }
        dot.background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(color) }
        serviceBtn.text = if (running) "Stop service" else "Start service"
        serviceBtn.background = shape(if (running) NEUTRAL else ACCENT, 12)
        stopBtn.visibility = if (Status.ringing) View.VISIBLE else View.GONE

        val secure = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
        val policy = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        setChip(locChip, secure, "Ready", "Needs adb")
        setChip(dndChip, policy, "Ready", "Needs access")
        adbHint.visibility = if (secure) View.GONE else View.VISIBLE

        val c = store.all()
        val sig = c.joinToString { it.id + it.display }
        if (sig != lastSig) { lastSig = sig; rebuildList(c) }
    }

    private fun rebuildList(c: List<TrustStore.Controller>) {
        list.removeAllViews()
        if (c.isEmpty()) {
            list.addView(label("No trusted controllers yet. Choose “Always allow” when one connects.", 13f, DIM))
            return
        }
        c.forEach { ctl ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val names = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            names.addView(label(ctl.display, 15f, TEXT, true))
            names.addView(label(if (ctl.label.isNotEmpty()) "${ctl.name} · ${ctl.type}" else ctl.type, 12f, DIM))
            row.addView(names)
            row.addView(smallButton("Rename") { askRename(ctl) })
            row.addView(smallButton("Revoke", Color.parseColor("#4A2227")) { confirmRevoke(ctl) })
            list.addView(row)
        }
    }

    private fun askRename(ctl: TrustStore.Controller) {
        val input = EditText(this).apply {
            setText(ctl.display)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(24))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename controller")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                store.rename(ctl.id, input.text.toString().replace("|", " "))
                lastSig = "?"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRevoke(ctl: TrustStore.Controller) {
        AlertDialog.Builder(this)
            .setTitle("Revoke ${ctl.display}?")
            .setMessage("It will need your approval again the next time it connects.")
            .setPositiveButton("Revoke") { _, _ -> store.remove(ctl.id); lastSig = "?" }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }
}
