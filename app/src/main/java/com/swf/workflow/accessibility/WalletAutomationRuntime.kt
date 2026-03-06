package com.swf.workflow.accessibility

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AutomationLogLine(
    val time: String,
    val message: String
)

data class AutomationRuntimeState(
    val isRunning: Boolean = false,
    val currentStep: String = "空闲",
    val stopRequested: Boolean = false,
    val logs: List<AutomationLogLine> = emptyList()
)

object WalletAutomationRuntime {

    private const val MAX_LOG_COUNT = 200

    private val _state = MutableStateFlow(AutomationRuntimeState())
    val state: StateFlow<AutomationRuntimeState> = _state

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun startFlow() {
        val current = _state.value
        _state.value = current.copy(
            isRunning = true,
            currentStep = "准备启动",
            stopRequested = false
        )
        appendLogLocked("开始自动领取流程")
    }

    @Synchronized
    fun step(step: String, message: String? = null) {
        val current = _state.value
        _state.value = current.copy(
            isRunning = true,
            currentStep = step
        )
        if (!message.isNullOrBlank()) {
            appendLogLocked(message)
        }
    }

    @Synchronized
    fun info(message: String) {
        appendLogLocked(message)
    }

    @Synchronized
    fun complete(message: String) {
        val current = _state.value
        _state.value = current.copy(
            isRunning = false,
            currentStep = "已完成",
            stopRequested = false
        )
        appendLogLocked(message)
    }

    @Synchronized
    fun fail(message: String) {
        val current = _state.value
        _state.value = current.copy(
            isRunning = false,
            currentStep = "执行失败",
            stopRequested = false
        )
        appendLogLocked(message)
    }

    @Synchronized
    fun stopByUser(message: String) {
        val current = _state.value
        _state.value = current.copy(
            isRunning = false,
            currentStep = "已停止",
            stopRequested = false
        )
        appendLogLocked(message)
    }

    @Synchronized
    fun requestStop(context: Context) {
        WalletAutomationRequestStore.clearPending(context)
        val current = _state.value
        _state.value = current.copy(
            isRunning = true,
            currentStep = "停止中",
            stopRequested = true
        )
        appendLogLocked("收到手动停止指令")
    }

    @Synchronized
    fun consumeStopRequest(): Boolean {
        val current = _state.value
        if (!current.stopRequested) {
            return false
        }
        _state.value = current.copy(stopRequested = false)
        return true
    }

    @Synchronized
    fun markIdle(message: String) {
        val current = _state.value
        _state.value = current.copy(
            isRunning = false,
            currentStep = "空闲",
            stopRequested = false
        )
        appendLogLocked(message)
    }

    @Synchronized
    fun clearLogs() {
        val current = _state.value
        _state.value = current.copy(logs = emptyList())
    }

    @Synchronized
    private fun appendLogLocked(message: String) {
        val current = _state.value
        val newLogs = (current.logs + AutomationLogLine(
            time = timeFormatter.format(Date()),
            message = message
        )).takeLast(MAX_LOG_COUNT)
        _state.value = current.copy(logs = newLogs)
    }
}
