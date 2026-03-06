package com.swf.workflow.returning

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.swf.workflow.wallet.LaunchResult
import com.swf.workflow.wallet.WalletLauncher

object AggressiveReturnEngine : ReturnEngine {

    private const val WALLET_PACKAGE = "com.mipay.wallet"

    private val fallbackComponents = listOf(
        ComponentName(WALLET_PACKAGE, "com.mipay.wallet.ui.MipayWalletActivity"),
        ComponentName(WALLET_PACKAGE, "com.mipay.wallet.home.ui.HomeActivity"),
        ComponentName(WALLET_PACKAGE, "com.mipay.wallet.main.MipayMainActivity")
    )

    override fun returnToWallet(context: Context): ReturnResult {
        val launchByWalletLauncher = WalletLauncher.launchMiWallet(context)
        if (launchByWalletLauncher is LaunchResult.Success) {
            return ReturnResult(
                success = true,
                channel = ReturnChannel.AGGRESSIVE,
                reason = "标准拉起成功"
            )
        }

        val commonFlags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        val launchIntent = context.packageManager.getLaunchIntentForPackage(WALLET_PACKAGE)
        if (launchIntent != null && runCatching {
                launchIntent.addFlags(commonFlags)
                context.startActivity(launchIntent)
            }.isSuccess
        ) {
            return ReturnResult(
                success = true,
                channel = ReturnChannel.AGGRESSIVE,
                reason = "包名拉起成功（增强flags）"
            )
        }

        fallbackComponents.forEach { component ->
            val intent = Intent(Intent.ACTION_MAIN).apply {
                this.component = component
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(commonFlags)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return ReturnResult(
                    success = true,
                    channel = ReturnChannel.AGGRESSIVE,
                    reason = "组件拉起成功：${component.className}"
                )
            }
        }

        val clearTaskFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val clearTaskLaunchIntent = context.packageManager.getLaunchIntentForPackage(WALLET_PACKAGE)
        if (clearTaskLaunchIntent != null && runCatching {
                clearTaskLaunchIntent.flags = clearTaskFlags
                context.startActivity(clearTaskLaunchIntent)
            }.isSuccess
        ) {
            return ReturnResult(
                success = true,
                channel = ReturnChannel.AGGRESSIVE,
                reason = "包名拉起成功（CLEAR_TASK）"
            )
        }

        fallbackComponents.forEach { component ->
            val intent = Intent(Intent.ACTION_MAIN).apply {
                this.component = component
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(clearTaskFlags)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return ReturnResult(
                    success = true,
                    channel = ReturnChannel.AGGRESSIVE,
                    reason = "组件拉起成功（CLEAR_TASK）：${component.className}"
                )
            }
        }

        return ReturnResult(
            success = false,
            channel = ReturnChannel.AGGRESSIVE,
            reason = "多路径拉起均失败"
        )
    }
}
