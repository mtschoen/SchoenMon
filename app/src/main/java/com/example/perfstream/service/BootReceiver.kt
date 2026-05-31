package com.example.perfstream.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.perfstream.service.PerformanceMonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Automatically launch the performance monitoring service on device startup
            PerformanceMonitorService.startService(context)
        }
    }
}
