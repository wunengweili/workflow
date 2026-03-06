package com.swf.workflow.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityStatusChecker {

    fun isServiceEnabled(context: Context): Boolean {
        if (isEnabledByAccessibilityManager(context)) {
            return true
        }
        return isEnabledBySecureSettings(context)
    }

    private fun isEnabledByAccessibilityManager(context: Context): Boolean {
        val accessibilityManager =
            context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expectedComponent = ComponentName(context, WalletAutomationAccessibilityService::class.java)
        val enabledServices = accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        return enabledServices.any { serviceInfo ->
            val resolveInfo = serviceInfo.resolveInfo ?: return@any false
            val info = resolveInfo.serviceInfo ?: return@any false
            val actualComponent = ComponentName(info.packageName, info.name)
            sameComponent(actualComponent, expectedComponent)
        }
    }

    private fun isEnabledBySecureSettings(context: Context): Boolean {
        val expectedComponent = ComponentName(context, WalletAutomationAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        enabledServices
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { rawService ->
                val component = ComponentName.unflattenFromString(rawService) ?: return@forEach
                if (sameComponent(component, expectedComponent)) {
                    return true
                }
            }
        return false
    }

    private fun sameComponent(actual: ComponentName, expected: ComponentName): Boolean {
        if (actual.packageName != expected.packageName) {
            return false
        }

        val actualClass = normalizeClassName(actual.className, expected.packageName)
        val expectedClass = normalizeClassName(expected.className, expected.packageName)
        return actualClass == expectedClass
    }

    private fun normalizeClassName(className: String, packageName: String): String {
        return if (className.startsWith(".")) {
            "$packageName$className"
        } else {
            className
        }
    }
}
