package com.example.phonelink

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle

class ApprovalActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val addr = intent.getStringExtra("addr") ?: return finish()
        val name = intent.getStringExtra("name") ?: "Unknown"
        val type = intent.getStringExtra("type") ?: "?"

        fun reply(decision: String) {
            sendBroadcast(
                Intent(Proto.ACTION_DECISION).setPackage(packageName)
                    .putExtra("addr", addr).putExtra("decision", decision)
            )
            finish()
        }

        AlertDialog.Builder(this)
            .setTitle("Allow controller?")
            .setMessage("\"$name\" ($type) wants to control this phone.")
            .setPositiveButton("Allow once") { _, _ -> reply("ONCE") }
            .setNeutralButton("Always allow") { _, _ -> reply("ALWAYS") }
            .setNegativeButton("Deny") { _, _ -> reply("DENY") }
            .setCancelable(false)
            .show()
    }
}
