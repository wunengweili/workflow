package com.swf.workflow.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings

object AccessibilityNavigator {

    fun openAccessibilitySettings(context: Context): Boolean {
        return runCatching {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.isSuccess
    }

    fun openUsageAccessSettings(context: Context): Boolean {
        return runCatching {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.isSuccess
    }
}
