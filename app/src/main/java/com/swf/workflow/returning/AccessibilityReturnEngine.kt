package com.swf.workflow.returning

import android.content.Context
import com.swf.workflow.wallet.LaunchResult
import com.swf.workflow.wallet.WalletLauncher

object AccessibilityReturnEngine : ReturnEngine {

    override fun returnToWallet(context: Context): ReturnResult {
        val launchResult = WalletLauncher.launchMiWallet(context)
        return when (launchResult) {
            LaunchResult.Success -> {
                ReturnResult(
                    success = true,
                    channel = ReturnChannel.ACCESSIBILITY,
                    reason = "钱包拉起请求已发送"
                )
            }

            LaunchResult.FailedNotFound -> {
                ReturnResult(
                    success = false,
                    channel = ReturnChannel.ACCESSIBILITY,
                    reason = "未找到小米钱包"
                )
            }

            is LaunchResult.FailedException -> {
                ReturnResult(
                    success = false,
                    channel = ReturnChannel.ACCESSIBILITY,
                    reason = launchResult.throwable.message.orEmpty()
                )
            }
        }
    }
}
