package com.llamafarm.atmosphere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // Optionally start service on boot if user has enabled it
            // This would be controlled by a preference
        }
    }
}
