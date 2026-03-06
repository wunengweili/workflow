package com.swf.workflow.accessibility

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

object UsageAccessStatusChecker {

    @Suppress("DEPRECATION")
    fun isEnabled(context: Context): Boolean {
        val appOpsManager = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
        }.getOrElse { return false }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    fun queryForegroundPackage(
        context: Context,
        lookbackMs: Long = 15_000L
    ): String? {
        if (!isEnabled(context)) {
            return null
        }

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endTimeMs = System.currentTimeMillis()
        val startTimeMs = endTimeMs - lookbackMs.coerceAtLeast(5_000L)
        val usageEvents = runCatching {
            usageStatsManager.queryEvents(startTimeMs, endTimeMs)
        }.getOrElse { return null }

        val event = UsageEvents.Event()
        var latestPkg: String? = null
        var latestTs = 0L
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val isForegroundEvent =
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (!isForegroundEvent) {
                continue
            }
            if (event.packageName.isNullOrBlank()) {
                continue
            }
            if (event.timeStamp >= latestTs) {
                latestTs = event.timeStamp
                latestPkg = event.packageName
            }
        }

        return latestPkg
    }
}
