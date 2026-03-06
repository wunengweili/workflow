package com.swf.workflow.returning

import android.content.Context

enum class ReturnChannel {
    AGGRESSIVE,
    ACCESSIBILITY
}

data class ReturnResult(
    val success: Boolean,
    val channel: ReturnChannel,
    val reason: String? = null
)

interface ReturnEngine {
    fun returnToWallet(context: Context): ReturnResult
}
