package com.swf.workflow.wallet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.swf.workflow.shizuku.ShizukuBridge

object WalletLauncher {

    private const val WALLET_PACKAGE = "com.mipay.wallet"

    private val fallbackComponents = listOf(
        ComponentName(WALLET_PACKAGE, "com.mipay.wallet.ui.MipayWalletActivity"),
        ComponentName(WALLET_PACKAGE, "com.mipay.wallet.home.ui.HomeActivity"),
        ComponentName(WALLET_PACKAGE, "com.mipay.wallet.main.MipayMainActivity")
    )

    fun launchMiWallet(context: Context): LaunchResult {
        val shizukuLaunchResult = ShizukuBridge.startPackage(context, WALLET_PACKAGE)
        if (shizukuLaunchResult.success) {
            return LaunchResult.Success
        }

        val packageManager = context.packageManager

        val packageIntent = packageManager.getLaunchIntentForPackage(WALLET_PACKAGE)
        val packageResult = startActivitySafely(context, packageIntent)
        if (packageResult is LaunchResult.Success) {
            return packageResult
        }

        fallbackComponents.forEach { componentName ->
            val intent = Intent(Intent.ACTION_MAIN).apply {
                this.component = componentName
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val componentResult = startActivitySafely(context, intent)
            if (componentResult is LaunchResult.Success) {
                return componentResult
            }
        }

        return LaunchResult.FailedNotFound
    }

    private fun startActivitySafely(context: Context, intent: Intent?): LaunchResult {
        if (intent == null) {
            return LaunchResult.FailedNotFound
        }

        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LaunchResult.Success
        }.getOrElse {
            LaunchResult.FailedException(it)
        }
    }
}

sealed interface LaunchResult {
    data object Success : LaunchResult
    data object FailedNotFound : LaunchResult
    data class FailedException(val throwable: Throwable) : LaunchResult
}
