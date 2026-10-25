package com.example.phonelink

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import java.security.SecureRandom

object Status {
    @Volatile var text = "Stopped"
    @Volatile var running = false
    @Volatile var ringing = false
}

class DeviceService : Service() {
    private class Session(val device: BluetoothDevice) {
        var ctrl: TrustStore.Controller? = null
        var nonce: String? = null
        var allowed: Set<String>? = null            // non-null = authenticated
        var hello: Triple<String, String, String>? = null // id,name,type awaiting approval
        val buf = StringBuilder()
    }

    private val main = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<String, Session>()
    private val mtu = mutableMapOf<String, Int>()
    private val outQ = ArrayDeque<Pair<BluetoothDevice, ByteArray>>()
    private var sending = false
    private lateinit var store: TrustStore
    private lateinit var am: AudioManager
    private var server: BluetoothGattServer? = null
    private var evt: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var player: MediaPlayer? = null
    private var savedAlarmVol = -1
    private var torchId: String? = null
    private var torchOn = false
    private val autoStop = Runnable { stopRing() }
    private val nm get() = getSystemService(NotificationManager::class.java)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = TrustStore(this)
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        nm.createNotificationChannel(NotificationChannel("svc", "Service", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("ask", "Approvals", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("ring", "Find phone", NotificationManager.IMPORTANCE_HIGH))
        startForeground(1, serviceNotif("Starting…"))
        registerReceiver(decisionRx, IntentFilter(Proto.ACTION_DECISION))
        registerReceiver(stateRx, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction("android.location.MODE_CHANGED")
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        })
        Status.running = true
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP_RING") stopRing() else startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        stopRing()
        unregisterReceiver(decisionRx)
        unregisterReceiver(stateRx)
        main.removeCallbacks(stateTask)
        advertiser?.stopAdvertising(advCb)
        server?.close(); server = null
        Status.running = false; Status.text = "Stopped"
        super.onDestroy()
    }

    // ---------- status / notifications ----------
    private fun serviceNotif(text: String) =
        Notification.Builder(this, "svc").setContentTitle("PhoneLink")
            .setContentText(text).setOngoing(true)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()

    private fun updateStatus(text: String) { Status.text = text; nm.notify(1, serviceNotif(text)) }

    // ---------- BLE server ----------
    private fun startServer() {
        if (server != null) return
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        if (!bm.adapter.isEnabled) { updateStatus("Bluetooth is off — turn it on, then restart"); return }
        server = bm.openGattServer(this, cb)
        val svc = BluetoothGattService(Proto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(BluetoothGattCharacteristic(
            Proto.CMD,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
        val e = BluetoothGattCharacteristic(
            Proto.EVT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        e.addDescriptor(BluetoothGattDescriptor(
            Proto.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        evt = e
        svc.addCharacteristic(e)
        server!!.addService(svc)
        advertise()
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { updateStatus("Advertising failed (code $errorCode)") }
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { updateStatus("Ready — advertising") }
    }

    private fun advertise() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (!adapter.isEnabled) { updateStatus("Bluetooth is off"); return }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) { updateStatus("This phone can't advertise over BLE"); return }
        advertiser = adv
        adv.stopAdvertising(advCb)
        val st = AdvertiseSettings.Builder().setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Proto.SERVICE)).build()
        adv.startAdvertising(st, data, advCb)
    }

    private fun sess(d: BluetoothDevice) = sessions.getOrPut(d.address) { Session(d) }

    private val cb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) sess(d)
                else { sessions.remove(d.address); mtu.remove(d.address) }
                advertise() // keep advertising so more controllers / RSSI scans still work
            }
        }

        override fun onMtuChanged(d: BluetoothDevice, m: Int) { main.post { mtu[d.address] = m } }

        override fun onDescriptorWriteRequest(
            d: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            d: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            val text = String(value, Charsets.UTF_8)
            main.post { onData(d, text) }
        }

        override fun onNotificationSent(d: BluetoothDevice, status: Int) {
            main.post { sending = false; pump() }
        }
    }

    // ---------- outgoing (chunked to MTU, queued) ----------
    private fun send(d: BluetoothDevice, line: String) {
        val size = (mtu[d.address] ?: 23) - 3
        (line + "\n").toByteArray().toList().chunked(size).forEach { outQ.add(d to it.toByteArray()) }
        pump()
    }

    private fun pump() {
        if (sending) return
        val (d, bytes) = outQ.removeFirstOrNull() ?: return
        val e = evt ?: return
        sending = true
        e.value = bytes
        if (server?.notifyCharacteristicChanged(d, e, false) != true) { sending = false; pump() }
    }

    private fun sendState(d: BluetoothDevice) {
        fun pct(st: Int) = am.getStreamVolume(st) * 100 / maxOf(1, am.getStreamMaxVolume(st))
        val wifi = if ((applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).isWifiEnabled) 1 else 0
        @Suppress("DEPRECATION")
        val loc = if (Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0) 1 else 0
        send(d, "STATE|m=${pct(AudioManager.STREAM_MUSIC)};r=${pct(AudioManager.STREAM_RING)};" +
            "a=${pct(AudioManager.STREAM_ALARM)};wifi=$wifi;loc=$loc;" +
            "torch=${if (torchOn) 1 else 0};ring=${if (player != null) 1 else 0}")
    }

    private fun broadcastState() {
        sessions.values.filter { it.allowed != null }.forEach { sendState(it.device) }
    }

    // Debounced: system changes (Wi-Fi, location, volume) finish a moment after the command,
    // so report the real state once they settle instead of the stale one.
    private val stateTask = Runnable { broadcastState() }
    private fun scheduleState(delay: Long = 200L) {
        main.removeCallbacks(stateTask); main.postDelayed(stateTask, delay)
    }

    private val stateRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { scheduleState() }
    }

    // ---------- incoming ----------
    private fun onData(d: BluetoothDevice, chunk: String) {
        val s = sess(d)
        s.buf.append(chunk)
        while (true) {
            val i = s.buf.indexOf("\n")
            if (i < 0) break
            val line = s.buf.substring(0, i).trim()
            s.buf.delete(0, i + 1)
            if (line.isNotEmpty()) handle(s, line)
        }
    }

    private fun handle(s: Session, line: String) {
        val p = line.split("|")
        val cmd = p[0]
        val d = s.device
        when (cmd) {
            "HELLO", "PAIR" -> {
                if (p.size < 4) return send(d, "ERR|bad")
                if (!store.accepting) return send(d, "ERR|disabled")
                val id = p[1]; val name = p[2]; val type = p[3]
                val known = if (cmd == "PAIR") null else store.get(id)
                if (known != null) {
                    s.ctrl = known
                    s.nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
                    send(d, "CHAL|${s.nonce}")
                } else {
                    s.hello = Triple(id, name, type)
                    send(d, "PENDING")
                    askApproval(d, name, type)
                }
            }
            "AUTH" -> {
                val c = s.ctrl; val n = s.nonce
                if (c != null && n != null && p.size >= 2 && p[1].equals(hmacHex(c.token, n), true)) {
                    s.allowed = c.allowed; s.nonce = null
                    send(d, "OK|${c.allowed.joinToString(",")}")
                    sendState(d)
                } else send(d, "ERR|auth")
            }
            else -> {
                val allowed = s.allowed ?: return send(d, "ERR|noauth")
                if (cmd !in allowed) return send(d, "ERR|denied|$cmd")
                try { exec(cmd, p.drop(1)) } catch (e: Exception) {
                    send(d, "ERR|fail|$cmd|${if (e is SecurityException) "perm" else "error"}")
                }
                scheduleState(150L)
            }
        }
    }

    // ---------- approval ----------
    private fun askApproval(d: BluetoothDevice, name: String, type: String) {
        val i = Intent(this, ApprovalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("addr", d.address).putExtra("name", name).putExtra("type", type)
        val pi = PendingIntent.getActivity(this, d.address.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "ask").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Controller wants access").setContentText("$name ($type) — tap to review")
            .setContentIntent(pi).setAutoCancel(true).build()
        nm.notify(d.address.hashCode(), n)
        try { startActivity(i) } catch (_: Exception) { /* Vivo may block; notification is the fallback */ }
        main.postDelayed({ if (sessions[d.address]?.hello != null) decide(d.address, "DENY") }, 30_000)
    }

    private val decisionRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            decide(i.getStringExtra("addr") ?: return, i.getStringExtra("decision") ?: return)
        }
    }

    private fun decide(addr: String, decision: String) {
        val s = sessions[addr] ?: return
        val (id, name, type) = s.hello ?: return
        s.hello = null
        val d = s.device
        nm.cancel(addr.hashCode())
        when (decision) {
            "ONCE" -> {
                val prof = Proto.defaultProfile(type)
                s.allowed = prof
                send(d, "OK|${prof.joinToString(",")}|once"); sendState(d)
            }
            "ALWAYS" -> {
                val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
                val prof = Proto.defaultProfile(type)
                store.add(TrustStore.Controller(id, name, type, token, prof))
                s.allowed = prof
                send(d, "TOKEN|$token|${prof.joinToString(",")}"); sendState(d)
            }
            else -> {
                send(d, "ERR|denied")
                main.postDelayed({ server?.cancelConnection(d) }, 500)
            }
        }
    }

    // ---------- actions ----------
    private fun exec(cmd: String, a: List<String>) {
        when (cmd) {
            "VOL" -> {
                val st = when (a[0]) {
                    "media" -> AudioManager.STREAM_MUSIC
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    else -> return
                }
                val max = am.getStreamMaxVolume(st)
                val lvl = a[1].toInt().coerceIn(0, 100) * max / 100
                // Ringer volume 0 flips the phone to silent, which needs Do Not Disturb access.
                val canZero = st != AudioManager.STREAM_RING || nm.isNotificationPolicyAccessGranted
                am.setStreamVolume(st, if (canZero) lvl else maxOf(1, lvl), 0)
            }
            "MUTE" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            "WIFI" -> {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).isWifiEnabled = a[0] == "1"
            }
            "LOC" -> {
                @Suppress("DEPRECATION")
                Settings.Secure.putInt(contentResolver, Settings.Secure.LOCATION_MODE,
                    if (a[0] == "1") Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF)
            }
            "TORCH" -> setTorch(a[0] == "1")
            "RING" -> if (a[0] == "1") startRing() else stopRing()
            "GET" -> {}
        }
    }

    private fun setTorch(on: Boolean) {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = torchId ?: cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }?.also { torchId = it } ?: return
        cm.setTorchMode(id, on)
        torchOn = on
    }

    private fun startRing() {
        if (player != null) return
        savedAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(this@DeviceService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            isLooping = true; prepare(); start()
        }
        (getSystemService(VIBRATOR_SERVICE) as Vibrator)
            .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        try { setTorch(true) } catch (_: Exception) {}
        Status.ringing = true

        val stop = PendingIntent.getService(this, 2,
            Intent(this, DeviceService::class.java).setAction("STOP_RING"), PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "ring").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Phone is ringing").setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop alarm", stop).build())
            .build()
        nm.notify(3, n)
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("ring", true))
        } catch (_: Exception) {}
        main.postDelayed(autoStop, 5 * 60_000L)
    }

    private fun stopRing() {
        main.removeCallbacks(autoStop)
        player?.run { stop(); release() }
        player = null
        if (savedAlarmVol >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0)
        savedAlarmVol = -1
        (getSystemService(VIBRATOR_SERVICE) as Vibrator).cancel()
        try { setTorch(false) } catch (_: Exception) {}
        Status.ringing = false
        nm.cancel(3)
        broadcastState()
    }
}
