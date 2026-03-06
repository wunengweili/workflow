package com.swf.workflow.returning

import android.content.Context

object ReturnEngineRouter {

    fun returnToWallet(
        context: Context,
        mode: ReturnMode,
        onFallbackRecoverAction: (() -> Unit)? = null
    ): ReturnResult {
        if (mode == ReturnMode.ACCESSIBILITY_ONLY) {
            return AccessibilityReturnEngine.returnToWallet(context)
        }

        val aggressiveResult = AggressiveReturnEngine.returnToWallet(context)
        if (aggressiveResult.success) {
            return aggressiveResult
        }

        val fallbackResult = AccessibilityReturnEngine.returnToWallet(context)
        if (fallbackResult.success) {
            return fallbackResult.copy(
                reason = mergeReason(
                    aggressiveReason = aggressiveResult.reason,
                    fallbackReason = fallbackResult.reason
                )
            )
        }

        onFallbackRecoverAction?.invoke()
        val retryFallbackResult = AccessibilityReturnEngine.returnToWallet(context)
        if (retryFallbackResult.success) {
            return retryFallbackResult.copy(
                reason = mergeReason(
                    aggressiveReason = aggressiveResult.reason,
                    fallbackReason = "首次无障碍拉起失败后重试成功"
                )
            )
        }

        return retryFallbackResult.copy(
            reason = mergeReason(
                aggressiveReason = aggressiveResult.reason,
                fallbackReason = retryFallbackResult.reason ?: fallbackResult.reason
            )
        )
    }

    private fun mergeReason(aggressiveReason: String?, fallbackReason: String?): String {
        val reasonParts = mutableListOf<String>()
        if (!aggressiveReason.isNullOrBlank()) {
            reasonParts += "激进拉起结果：$aggressiveReason"
        }
        if (!fallbackReason.isNullOrBlank()) {
            reasonParts += "无障碍结果：$fallbackReason"
        }
        return reasonParts.joinToString("；").ifBlank { "未知原因" }
    }
}
