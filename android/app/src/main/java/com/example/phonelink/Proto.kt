package com.example.phonelink

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared protocol (also used later by controller mode, ESP32 and PC).
 * Text lines, fields split by '|', every message ends with '\n'.
 *
 * Controller -> phone (CMD characteristic, write):
 *   HELLO|id|name|type        type = pc | esp32 | phone
 *   AUTH|hmacHex              HMAC-SHA256(token, nonce) answering CHAL
 *   VOL|media/ring/alarm|0-100   MUTE   WIFI|1/0   LOC|1/0   TORCH|1/0   RING|1/0   GET
 * Phone -> controller (EVT characteristic, notify):
 *   PENDING | CHAL|nonceHex | TOKEN|tokenHex|allowedCsv | OK|allowedCsv
 *   STATE|m=..;r=..;a=..;wifi=..;loc=..;torch=..;ring=.. | ERR|reason
 */
object Proto {
    val SERVICE: UUID = UUID.fromString("6e5a0001-7b1c-4f0e-9a52-1c0d5e7a0001")
    val CMD: UUID = UUID.fromString("6e5a0002-7b1c-4f0e-9a52-1c0d5e7a0001")
    val EVT: UUID = UUID.fromString("6e5a0003-7b1c-4f0e-9a52-1c0d5e7a0001")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val ACTION_DECISION = "com.example.phonelink.DECISION"

    val ALL = setOf("VOL", "MUTE", "WIFI", "LOC", "TORCH", "RING", "GET")

    // Placeholder split per controller type: edit freely.
    fun defaultProfile(type: String): Set<String> = when (type) {
        "esp32" -> setOf("VOL", "MUTE", "LOC", "WIFI", "RING", "GET")
        else -> ALL
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun hmacHex(tokenHex: String, nonceHex: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(tokenHex.hexToBytes(), "HmacSHA256"))
    return mac.doFinal(nonceHex.hexToBytes()).toHex()
}
