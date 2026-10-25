package com.example.phonelink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TrustStore(ctx: Context) {
    data class Controller(
        val id: String, var name: String, val type: String,
        val token: String, var allowed: Set<String>
    )

    private val sp = ctx.getSharedPreferences("trust", Context.MODE_PRIVATE)

    var accepting: Boolean
        get() = sp.getBoolean("accepting", true)
        set(v) { sp.edit().putBoolean("accepting", v).apply() }

    fun all(): List<Controller> {
        val arr = JSONArray(sp.getString("list", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Controller(
                o.getString("id"), o.getString("name"), o.getString("type"), o.getString("token"),
                o.getString("allowed").split(",").filter { s -> s.isNotEmpty() }.toSet()
            )
        }
    }

    fun get(id: String) = all().firstOrNull { it.id == id }
    fun add(c: Controller) = save(all().filter { it.id != c.id } + c)
    fun remove(id: String) = save(all().filter { it.id != id })

    private fun save(l: List<Controller>) {
        val arr = JSONArray()
        l.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("type", it.type)
                    .put("token", it.token).put("allowed", it.allowed.joinToString(","))
            )
        }
        sp.edit().putString("list", arr.toString()).apply()
    }
}
