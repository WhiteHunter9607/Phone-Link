package com.example.phonelink

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class TrustStore(ctx: Context) {
    data class Controller(
        val id: String, var name: String, val type: String,
        val token: String, var allowed: Set<String>, var label: String = ""
    ) {
        /** Name shown on the phone: the label set here, else the name the controller reported. */
        val display: String get() = label.ifEmpty { name }
    }

    private val sp = ctx.getSharedPreferences("trust", Context.MODE_PRIVATE)

    var accepting: Boolean
        get() = sp.getBoolean("accepting", true)
        set(v) { sp.edit().putBoolean("accepting", v).apply() }

    var autostart: Boolean
        get() = sp.getBoolean("autostart", true)
        set(v) { sp.edit().putBoolean("autostart", v).apply() }

    val hasCustomName: Boolean get() = sp.contains("phoneName")

    var phoneName: String
        get() = sp.getString("phoneName", null) ?: Build.MODEL
        set(v) { sp.edit().putString("phoneName", v).apply() }

    /** Random id that identifies this phone to controllers (stays the same after renaming). */
    val phoneId: String
        get() = sp.getString("phoneId", null)
            ?: ByteArray(6).also { SecureRandom().nextBytes(it) }.toHex()
                .also { sp.edit().putString("phoneId", it).apply() }

    fun all(): List<Controller> {
        val arr = JSONArray(sp.getString("list", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Controller(
                o.getString("id"), o.getString("name"), o.getString("type"), o.getString("token"),
                o.getString("allowed").split(",").filter { s -> s.isNotEmpty() }.toSet(),
                o.optString("label", "")
            )
        }
    }

    fun get(id: String) = all().firstOrNull { it.id == id }
    fun add(c: Controller) = save(all().filter { it.id != c.id } + c)
    fun remove(id: String) = save(all().filter { it.id != id })
    fun rename(id: String, label: String) { get(id)?.let { it.label = label.trim().take(24); add(it) } }

    private fun save(l: List<Controller>) {
        val arr = JSONArray()
        l.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("type", it.type)
                    .put("token", it.token).put("allowed", it.allowed.joinToString(","))
                    .put("label", it.label)
            )
        }
        sp.edit().putString("list", arr.toString()).apply()
    }
}
