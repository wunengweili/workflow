package com.swf.workflow.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.swf.workflow.MainActivity
import com.swf.workflow.returning.ReturnChannel
import com.swf.workflow.returning.ReturnEngineRouter
import com.swf.workflow.returning.ReturnModeStore
import com.swf.workflow.shizuku.ShizukuBridge
import com.swf.workflow.wallet.WalletLauncher
import kotlin.math.abs
import kotlin.random.Random

class WalletAutomationAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val textRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private var currentStep: AutomationStep = AutomationStep.IDLE
    private var currentRetry = 0
    private var roundCount = 1

    private var ocrInProgress = false
    private var nextAllowedScreenshotAtMs = 0L
    private var screenshotNoticeLogged = false
    private var lastOcrCooldownLogAtMs = 0L
    private var lastEventHandledAtMs = 0L
    private var scheduledStepAtMs = 0L

    private var externalPackageName: String? = null
    private var externalWaitDeadlineMs = 0L
    private var nextExternalNotifyAtMs = 0L
    private var completionReason: String? = null
    private var stopReason: String? = null
    private var nextSelfReturnActionAtMs = 0L
    private var walletCloseAttemptedForFinalReturn = false
    private var lastEventPackageName: String? = null
    private var floatingReminderView: View? = null
    private var quickActionOverlayView: View? = null
    private var lastShizukuForegroundPackage: String? = null
    private var lastShizukuQueryAtMs = 0L
    private var lastUsageForegroundPackage: String? = null
    private var lastUsageQueryAtMs = 0L
    private var lastStageReminderText: String? = null
    private var lastStageReminderAtMs = 0L
    private var finishHintFirstSeenAtMs = 0L
    private var walletSettleUntilMs = 0L
    private var nextWalletSettleNotifyAtMs = 0L
    private var rewardResultForegroundConfirmRetry = 0
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val removeFloatingReminderRunnable = Runnable {
        removeFloatingReminderInternal()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        WalletAutomationRuntime.info("无障碍服务已连接")

        if (WalletAutomationRequestStore.consumePending(this)) {
            activateWorkflow("检测到待执行请求，开始自动流程")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        val eventPackage = event.packageName?.toString()
        if (!eventPackage.isNullOrBlank()) {
            val previousPackage = lastEventPackageName
            lastEventPackageName = eventPackage
            if (eventPackage == WALLET_PACKAGE &&
                previousPackage != WALLET_PACKAGE &&
                shouldArmWalletSettleOnWalletEnter()
            ) {
                armWalletSettleWindow()
            } else if (eventPackage != WALLET_PACKAGE) {
                walletSettleUntilMs = 0L
                nextWalletSettleNotifyAtMs = 0L
            }
        }

        if (WalletAutomationRuntime.consumeStopRequest()) {
            requestStopAndReturnToWallet("用户主动停止流程")
            return
        }

        if (currentStep == AutomationStep.IDLE) {
            if (WalletAutomationRequestStore.consumePending(this)) {
                activateWorkflow("收到执行请求，开始自动流程")
            } else {
                return
            }
        }

        if (ocrInProgress) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastEventHandledAtMs < EVENT_HANDLE_MIN_INTERVAL_MS) {
                    return
                }
                lastEventHandledAtMs = now
                runCurrentStep("event")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        WalletAutomationRuntime.info("无障碍服务被系统中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        val wasRunning = currentStep != AutomationStep.IDLE
        stopWorkflowInternal()
        if (wasRunning) {
            WalletAutomationRuntime.markIdle("无障碍服务销毁，流程中断")
        }
        removeFloatingReminder()
        runCatching { textRecognizer.close() }
    }

    private fun activateWorkflow(message: String) {
        if (currentStep != AutomationStep.IDLE) {
            WalletAutomationRuntime.info("流程正在执行，忽略重复启动")
            return
        }

        roundCount = 1
        externalPackageName = null
        externalWaitDeadlineMs = 0L
        nextExternalNotifyAtMs = 0L
        completionReason = null
        stopReason = null
        walletCloseAttemptedForFinalReturn = false

        WalletAutomationRuntime.step(AutomationStep.WAIT_WALLET_HOME.label, message)
        transitTo(AutomationStep.WAIT_WALLET_HOME, triggerAfterMs = 1000, logStep = false)
    }

    private fun runCurrentStep(trigger: String) {
        if (WalletAutomationRuntime.consumeStopRequest()) {
            requestStopAndReturnToWallet("用户主动停止流程")
            return
        }

        if (shouldWaitWalletSettle(currentStep)) {
            return
        }

        when (currentStep) {
            AutomationStep.IDLE -> Unit
            AutomationStep.WAIT_WALLET_HOME -> handleWalletHomeStep(trigger)
            AutomationStep.WAIT_WATCH_BUTTON -> handleWatchButtonStep(trigger)
            AutomationStep.WAIT_JUMP_CONFIRM -> handleJumpConfirmStep(trigger)
            AutomationStep.WAIT_EXTERNAL_SWITCH -> handleExternalSwitchStep(trigger)
            AutomationStep.WAIT_EXTERNAL_COUNTDOWN -> handleExternalCountdownStep()
            AutomationStep.RETURN_TO_WALLET -> handleReturnToWalletStep()
            AutomationStep.WAIT_REWARD_ENTRY -> handleRewardEntryStep(trigger)
            AutomationStep.WAIT_REWARD_RESULT -> handleRewardResultStep(trigger)
            AutomationStep.RETURN_TO_SELF_APP_FINAL -> handleReturnToSelfAppFinalStep()
            AutomationStep.RETURN_TO_WALLET_FINAL -> handleReturnToWalletFinalStep()
        }
    }

    private fun handleWalletHomeStep(trigger: String) {
        if (!isWalletOnTop()) {
            retryOrFail(
                maxRetry = 20,
                nextDelayMs = 700,
                failMessage = "未检测到小米钱包首页，流程已停止",
                retryHint = "等待进入小米钱包首页"
            )
            return
        }

        if (containsKeywordsByNode(FINISH_REWARD_KEYWORDS, preferredPackage = WALLET_PACKAGE)) {
            completeAndReturnToSelf("检测到“开提醒/会员不漏领”提示，任务处理完成")
            return
        }

        if (clickByKeywords(FIRST_STEP_KEYWORDS, preferredPackage = WALLET_PACKAGE)) {
            announceHit("已点击“领视频会员”（节点）")
            WalletAutomationRuntime.info("已点击“领视频会员”($trigger)")
            transitTo(AutomationStep.WAIT_WATCH_BUTTON, triggerAfterMs = 1200)
            return
        }

        if (tryClickByOcr(
                keywords = FIRST_STEP_KEYWORDS,
                stepLabel = "领视频会员",
                onSuccess = {
                    announceHit("已点击“领视频会员”（OCR）")
                    WalletAutomationRuntime.info("OCR识别成功，已点击“领视频会员”")
                    transitTo(AutomationStep.WAIT_WATCH_BUTTON, triggerAfterMs = 1200)
                },
                onFailure = {
                    retryOrFail(
                        maxRetry = 20,
                        nextDelayMs = 700,
                        failMessage = "未找到“领视频会员”，流程已停止",
                        retryHint = "识别“领视频会员”按钮中"
                    )
                }
            )
        ) {
            return
        }

        retryOrFail(
            maxRetry = 8,
            nextDelayMs = 900,
            failMessage = "系统不支持OCR，流程已停止",
            retryHint = "设备不支持截图识别"
        )
    }

    private fun handleWatchButtonStep(trigger: String) {
        if (!isWalletOnTop()) {
            retryOrFail(
                maxRetry = 12,
                nextDelayMs = 800,
                failMessage = "当前不在小米钱包，流程已停止",
                retryHint = "等待回到领取页"
            )
            return
        }

        val targetKeywords = watchStageKeywordsForCurrentRetry()
        val useLooseMatch = currentRetry >= WATCH_BUTTON_LOOSE_MATCH_RETRY_THRESHOLD

        if (clickByKeywords(CLICK_CLAIM_MEMBER_KEYWORDS, preferredPackage = WALLET_PACKAGE)) {
            announceHit("已点击“点击领会员”（节点）")
            WalletAutomationRuntime.info("已点击“点击领会员”($trigger)，等待奖励弹窗")
            transitTo(AutomationStep.WAIT_REWARD_ENTRY, triggerAfterMs = 1000)
            return
        }

        val nodeHit = detectWatchStageNodeHit(targetKeywords)
        if (nodeHit != null) {
            when (nodeHit.type) {
                WatchStageHitType.FINISH -> {
                    announceHit("识别到完成态：开提醒/会员不漏领（节点）")
                    completeAndReturnToSelf("检测到“开提醒/会员不漏领”提示，任务处理完成")
                }

                WatchStageHitType.WATCH -> {
                    if (clickNode(nodeHit.node)) {
                        announceHit("已点击“看10秒领会员”（节点）")
                        WalletAutomationRuntime.info("已点击“看10秒领会员”($trigger)")
                        transitTo(AutomationStep.WAIT_JUMP_CONFIRM, triggerAfterMs = 900)
                    } else {
                        retryOrFail(
                            maxRetry = 20,
                            nextDelayMs = 700,
                            failMessage = "命中“看10秒”但点击失败，流程已停止",
                            retryHint = "命中后点击失败，准备重试"
                        )
                    }
                }
            }
            return
        }

        val stepLabel = if (useLooseMatch) {
            "领取页综合识别（含看10秒宽松）"
        } else {
            "领取页综合识别"
        }

        if (runOcrSnapshot(
                stepLabel = stepLabel,
                onRecognized = { text, width, height ->
                    val finishHit = findBestOcrTarget(
                        text = text,
                        keywords = FINISH_REWARD_KEYWORDS,
                        width = width,
                        height = height
                    )
                    val watchHit = findBestOcrTarget(
                        text = text,
                        keywords = targetKeywords,
                        width = width,
                        height = height
                    )
                    val claimHit = findBestOcrTarget(
                        text = text,
                        keywords = CLICK_CLAIM_MEMBER_KEYWORDS,
                        width = width,
                        height = height
                    )

                    if (finishHit != null &&
                        (watchHit == null || finishHit.score + FINISH_HIT_PRIORITY_BONUS >= watchHit.score)
                    ) {
                        announceHit("识别到完成态：开提醒/会员不漏领（OCR）")
                        completeAndReturnToSelf("检测到“开提醒/会员不漏领”提示，任务处理完成")
                        return@runOcrSnapshot
                    }

                    if (watchHit != null && performGestureClick(watchHit.rect)) {
                        announceHit("已点击“看10秒领会员”（OCR）")
                        WalletAutomationRuntime.info("OCR识别成功，已点击“看10秒领会员”")
                        transitTo(AutomationStep.WAIT_JUMP_CONFIRM, triggerAfterMs = 900)
                        return@runOcrSnapshot
                    }

                    if (claimHit != null &&
                        (watchHit == null || claimHit.score >= watchHit.score) &&
                        performGestureClick(claimHit.rect)
                    ) {
                        announceHit("已点击“点击领会员”（OCR）")
                        WalletAutomationRuntime.info("OCR识别成功，已点击“点击领会员”")
                        transitTo(AutomationStep.WAIT_REWARD_ENTRY, triggerAfterMs = 1000)
                        return@runOcrSnapshot
                    }

                    val retryHint = if (useLooseMatch) {
                        "综合识别中（看10秒宽松/点击领会员）"
                    } else {
                        "综合识别中（看10秒/点击领会员/开提醒）"
                    }
                    retryOrFail(
                        maxRetry = 20,
                        nextDelayMs = 700,
                        failMessage = "未识别到“看10秒/点击领会员/开提醒”关键文案，流程已停止",
                        retryHint = retryHint
                    )
                },
                onFailure = {
                    val retryHint = if (useLooseMatch) {
                        "综合OCR失败（宽松模式）"
                    } else {
                        "综合OCR失败"
                    }
                    retryOrFail(
                        maxRetry = 20,
                        nextDelayMs = 700,
                        failMessage = "领取页OCR识别失败，流程已停止",
                        retryHint = retryHint
                    )
                }
            )
        ) {
            return
        }

        retryOrFail(
            maxRetry = 8,
            nextDelayMs = 900,
            failMessage = "系统不支持OCR，流程已停止",
            retryHint = "设备不支持截图识别"
        )
    }

    private fun handleJumpConfirmStep(trigger: String) {
        if (clickByKeywords(THIRD_STEP_KEYWORDS, preferredPackage = null)) {
            announceHit("已点击“点击立即跳转”（节点）")
            WalletAutomationRuntime.info("已点击“点击立即跳转”($trigger)")
            transitTo(AutomationStep.WAIT_EXTERNAL_SWITCH, triggerAfterMs = 1000)
            return
        }

        if (tryClickByOcr(
                keywords = THIRD_STEP_KEYWORDS,
                stepLabel = "点击立即跳转",
                onSuccess = {
                    announceHit("已点击“点击立即跳转”（OCR）")
                    WalletAutomationRuntime.info("OCR识别成功，已点击“点击立即跳转”")
                    transitTo(AutomationStep.WAIT_EXTERNAL_SWITCH, triggerAfterMs = 1000)
                },
                onFailure = {
                    retryOrFail(
                        maxRetry = 20,
                        nextDelayMs = 700,
                        failMessage = "未找到“点击立即跳转”，流程已停止",
                        retryHint = "识别“点击立即跳转”按钮中"
                    )
                }
            )
        ) {
            return
        }

        retryOrFail(
            maxRetry = 8,
            nextDelayMs = 900,
            failMessage = "系统不支持OCR，流程已停止",
            retryHint = "设备不支持截图识别"
        )
    }

    private fun handleExternalSwitchStep(trigger: String) {
        val foregroundPkg = currentForegroundPackage(forceRefresh = true)
        if (isClosableExternalPackage(foregroundPkg)) {
            startExternalCountdown(foregroundPkg, trigger)
            return
        }

        if (foregroundPkg == WALLET_PACKAGE || foregroundPkg == packageName) {
            val retryHint = if (foregroundPkg == WALLET_PACKAGE) {
                "仍在小米钱包，等待外部应用切换"
            } else {
                "仍在本应用，等待外部应用切换"
            }
            retryOrFail(
                maxRetry = 16,
                nextDelayMs = 1200,
                failMessage = "点击跳转后未切换到外部应用，流程已停止",
                retryHint = retryHint
            )
            return
        }

        val jumpStillVisible = containsKeywordsByNode(
            keywords = THIRD_STEP_KEYWORDS,
            preferredPackage = null
        )
        if (jumpStillVisible) {
            retryOrFail(
                maxRetry = 16,
                nextDelayMs = 1200,
                failMessage = "点击跳转后页面未变化，流程已停止",
                retryHint = "等待“点击立即跳转”文案消失"
            )
            return
        }

        retryOrFail(
            maxRetry = 16,
            nextDelayMs = 1200,
            failMessage = "点击跳转后无法确认外部应用，流程已停止",
            retryHint = "等待外部应用切换"
        )
    }

    private fun startExternalCountdown(foregroundPkg: String?, trigger: String) {
        externalPackageName = foregroundPkg
        val waitMs = Random.nextLong(EXTERNAL_WAIT_MIN_MS, EXTERNAL_WAIT_MAX_MS + 1)
        externalWaitDeadlineMs = SystemClock.elapsedRealtime() + waitMs
        nextExternalNotifyAtMs = SystemClock.elapsedRealtime()

        val waitSec = waitMs / 1000
        WalletAutomationRuntime.step(
            step = AutomationStep.WAIT_EXTERNAL_COUNTDOWN.label,
            message = "已确认跳转到外部应用($foregroundPkg)，等待${waitSec}秒"
        )
        showToast("已跳转外部应用，等待${waitSec}秒")
        showQuickActionOverlay("外跳中，可手动回跳")

        Log.i(TAG, "external switched by $trigger, package=$foregroundPkg")
        transitTo(
            step = AutomationStep.WAIT_EXTERNAL_COUNTDOWN,
            triggerAfterMs = 500L,
            logStep = false
        )
    }

    private fun handleExternalCountdownStep() {
        val now = SystemClock.elapsedRealtime()
        val remainingMs = externalWaitDeadlineMs - now
        if (remainingMs <= 0) {
            handleExternalCountdownFinished()
            transitTo(AutomationStep.RETURN_TO_WALLET, triggerAfterMs = 120)
            return
        }

        if (now >= nextExternalNotifyAtMs) {
            val sec = ((remainingMs + 999L) / 1000L).toInt()
            val message = "任务执行中，剩余${sec}秒"
            WalletAutomationRuntime.info(message)
            showToast(message)
            nextExternalNotifyAtMs = now + EXTERNAL_PROGRESS_INTERVAL_MS
        }

        scheduleStep(minOf(EXTERNAL_PROGRESS_INTERVAL_MS, remainingMs))
    }

    private fun handleExternalCountdownFinished() {
        val externalPackage = resolveExternalPackageForClose()
        if (!externalPackage.isNullOrBlank()) {
            val forceStopResult = ShizukuBridge.forceStopPackage(this, externalPackage)
            when {
                forceStopResult.success -> {
                    WalletAutomationRuntime.info("外部停留结束，已通过Shizuku关闭应用：$externalPackage")
                }

                forceStopResult.executed -> {
                    WalletAutomationRuntime.info("Shizuku关闭外部应用失败：${forceStopResult.message}")
                }

                else -> {
                    WalletAutomationRuntime.info("Shizuku未就绪，跳过关闭外部应用（${forceStopResult.message}）")
                }
            }
        }

        dispatchReturnToWalletRequest(
            sceneLabel = "外部停留结束回跳",
            recoverMessage = "回钱包失败，尝试返回+桌面后重试",
            attempt = 0
        )
    }

    private fun handleReturnToWalletStep() {
        if (isWalletOnTop()) {
            removeQuickActionOverlay()
            WalletAutomationRuntime.info("已确认回到小米钱包，等待奖励弹窗")
            transitTo(AutomationStep.WAIT_REWARD_ENTRY, triggerAfterMs = 2200)
            return
        }

        if (currentRetry == 0 || currentRetry % 2 == 0) {
            val foreground = currentForegroundPackage().orEmpty()
            WalletAutomationRuntime.info("发起返回钱包请求，当前前台：$foreground")
            dispatchReturnToWalletRequest(
                sceneLabel = "流程中回跳",
                recoverMessage = "无障碍拉起失败，尝试全局返回后重试",
                attempt = currentRetry
            )
        }

        retryOrFail(
            maxRetry = 12,
            nextDelayMs = 1100,
            failMessage = "多次尝试后仍未回到小米钱包，流程已停止",
            retryHint = "等待返回小米钱包"
        )
    }

    private fun handleRewardEntryStep(trigger: String) {
        if (!isWalletOnTop()) {
            WalletLauncher.launchMiWallet(this)
            retryOrFail(
                maxRetry = 12,
                nextDelayMs = 900,
                failMessage = "返回钱包后未进入目标页面，流程已停止",
                retryHint = "等待小米钱包页面恢复"
            )
            return
        }

        removeQuickActionOverlay()

        if (clickByKeywords(CLICK_CLAIM_MEMBER_KEYWORDS, preferredPackage = WALLET_PACKAGE)) {
            announceHit("已点击“点击领会员”（节点）")
            WalletAutomationRuntime.info("已点击“点击领会员”($trigger)，等待奖励弹窗“开”")
            currentRetry = 0
            scheduleStep(1000)
            return
        }

        if (clickByKeywords(REWARD_ENTRY_KEYWORDS, preferredPackage = WALLET_PACKAGE)) {
            WalletAutomationRuntime.info("已点击奖励弹窗“开”按钮($trigger)")
            transitTo(AutomationStep.WAIT_REWARD_RESULT, triggerAfterMs = 1400)
            return
        }

        if (tryClickByOcr(
                keywords = REWARD_ENTRY_KEYWORDS,
                stepLabel = "奖励弹窗“开”按钮",
                onSuccess = {
                    WalletAutomationRuntime.info("OCR识别成功，已点击奖励弹窗“开”按钮")
                    transitTo(AutomationStep.WAIT_REWARD_RESULT, triggerAfterMs = 1400)
                },
                onFailure = {
                    if (currentRetry >= 3 && clickRewardCenterFallback()) {
                        WalletAutomationRuntime.info("OCR未命中，使用中部兜底点位点击“开”")
                        transitTo(AutomationStep.WAIT_REWARD_RESULT, triggerAfterMs = 1400)
                        return@tryClickByOcr
                    }
                    retryOrFail(
                        maxRetry = 16,
                        nextDelayMs = 900,
                        failMessage = "未找到奖励弹窗“开”按钮，流程已停止",
                        retryHint = "识别奖励弹窗“开”按钮中"
                    )
                }
            )
        ) {
            return
        }

        retryOrFail(
            maxRetry = 8,
            nextDelayMs = 900,
            failMessage = "系统不支持OCR，流程已停止",
            retryHint = "设备不支持截图识别"
        )
    }

    private fun handleRewardResultStep(trigger: String) {
        val foregroundPkg = currentForegroundPackage(forceRefresh = true)
        if (foregroundPkg != WALLET_PACKAGE) {
            finishHintFirstSeenAtMs = 0L
            rewardResultForegroundConfirmRetry += 1
            if (rewardResultForegroundConfirmRetry <= REWARD_RESULT_FOREGROUND_CONFIRM_MAX_RETRY) {
                val current = foregroundPkg.orEmpty().ifBlank { "未知" }
                WalletAutomationRuntime.info(
                    "奖励结果页前台确认中(${rewardResultForegroundConfirmRetry}/" +
                        "$REWARD_RESULT_FOREGROUND_CONFIRM_MAX_RETRY)：$current"
                )
                scheduleStep(REWARD_RESULT_FOREGROUND_CONFIRM_INTERVAL_MS)
                return
            }

            val finalForeground = foregroundPkg.orEmpty().ifBlank { "未知" }
            WalletAutomationRuntime.info("奖励结果页确认已离开钱包（$finalForeground），回到跳转检测")
            rewardResultForegroundConfirmRetry = 0
            transitTo(AutomationStep.WAIT_EXTERNAL_SWITCH, triggerAfterMs = 1100)
            return
        }
        rewardResultForegroundConfirmRetry = 0

        if (clickByKeywords(CONTINUE_REWARD_KEYWORDS, preferredPackage = WALLET_PACKAGE)) {
            finishHintFirstSeenAtMs = 0L
            roundCount += 1
            WalletAutomationRuntime.info("已点击继续领取按钮($trigger)，进入第${roundCount}轮")
            transitTo(AutomationStep.WAIT_JUMP_CONFIRM, triggerAfterMs = 1100)
            return
        }

        if (clickByKeywords(THIRD_STEP_KEYWORDS, preferredPackage = null)) {
            finishHintFirstSeenAtMs = 0L
            WalletAutomationRuntime.info("奖励结果页检测到“点击立即跳转”，继续外部流程")
            transitTo(AutomationStep.WAIT_EXTERNAL_SWITCH, triggerAfterMs = 1000)
            return
        }

        if (containsKeywordsByNode(FINISH_REWARD_KEYWORDS, preferredPackage = WALLET_PACKAGE)) {
            handleFinishHintDetected("节点")
            return
        }

        if (analyzeRewardResultByOcr()) {
            return
        }

        retryOrFail(
            maxRetry = 10,
            nextDelayMs = 900,
            failMessage = "系统不支持OCR，流程已停止",
            retryHint = "设备不支持截图识别"
        )
    }

    private fun analyzeRewardResultByOcr(): Boolean {
        return runOcrSnapshot(
            stepLabel = "奖励结果弹窗识别",
            onRecognized = { text, width, height ->
                val continueRect = findBestOcrTargetRect(
                    text = text,
                    keywords = CONTINUE_REWARD_KEYWORDS,
                    width = width,
                    height = height
                )
                if (continueRect != null && performGestureClick(continueRect)) {
                    finishHintFirstSeenAtMs = 0L
                    roundCount += 1
                    WalletAutomationRuntime.info("OCR识别成功，已点击继续领取，进入第${roundCount}轮")
                    transitTo(AutomationStep.WAIT_JUMP_CONFIRM, triggerAfterMs = 1100)
                    return@runOcrSnapshot
                }

                val jumpRect = findBestOcrTargetRect(
                    text = text,
                    keywords = THIRD_STEP_KEYWORDS,
                    width = width,
                    height = height
                )
                if (jumpRect != null && performGestureClick(jumpRect)) {
                    finishHintFirstSeenAtMs = 0L
                    WalletAutomationRuntime.info("OCR识别成功，已点击“点击立即跳转”")
                    transitTo(AutomationStep.WAIT_EXTERNAL_SWITCH, triggerAfterMs = 1000)
                    return@runOcrSnapshot
                }

                val finishFound = findBestOcrTargetRect(
                    text = text,
                    keywords = FINISH_REWARD_KEYWORDS,
                    width = width,
                    height = height
                ) != null
                if (finishFound) {
                    handleFinishHintDetected("OCR")
                    return@runOcrSnapshot
                }

                retryOrFail(
                    maxRetry = 16,
                    nextDelayMs = 900,
                    failMessage = "未识别到结果弹窗按钮，流程已停止",
                    retryHint = "识别奖励结果弹窗中"
                )
            },
            onFailure = {
                retryOrFail(
                    maxRetry = 16,
                    nextDelayMs = 900,
                    failMessage = "奖励结果OCR识别失败，流程已停止",
                    retryHint = "OCR识别奖励结果失败"
                )
            }
        )
    }

    private fun completeAndReturnToSelf(message: String) {
        finishHintFirstSeenAtMs = 0L
        completionReason = message
        stopReason = null
        walletCloseAttemptedForFinalReturn = false
        WalletAutomationRuntime.info("检测到完成态，准备返回本应用并在后台关闭小米钱包")
        transitTo(AutomationStep.RETURN_TO_SELF_APP_FINAL, triggerAfterMs = 220)
    }

    private fun handleFinishHintDetected(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (finishHintFirstSeenAtMs == 0L) {
            finishHintFirstSeenAtMs = now
            WalletAutomationRuntime.info("识别到结束提示($source)，等待3秒确认是否还会出现10秒按钮")
            scheduleStep(FINISH_CONFIRM_WAIT_MS)
            return
        }

        val elapsedMs = now - finishHintFirstSeenAtMs
        if (elapsedMs < FINISH_CONFIRM_WAIT_MS) {
            scheduleStep(FINISH_CONFIRM_WAIT_MS - elapsedMs)
            return
        }

        completeAndReturnToSelf("检测到“开提醒/会员不漏领”提示，流程结束")
    }

    private fun handleReturnToSelfAppFinalStep() {
        val reason = completionReason ?: "任务处理完成"

        if (isSelfAppOnTop()) {
            if (!walletCloseAttemptedForFinalReturn) {
                walletCloseAttemptedForFinalReturn = true
                val closeWalletResult = ShizukuBridge.forceStopPackage(this, WALLET_PACKAGE)
                when {
                    closeWalletResult.success -> {
                        WalletAutomationRuntime.info("已切回本应用，后台关闭小米钱包成功")
                    }

                    closeWalletResult.executed -> {
                        WalletAutomationRuntime.info("已切回本应用，但后台关闭小米钱包失败：${closeWalletResult.message}")
                    }

                    else -> {
                        WalletAutomationRuntime.info("已切回本应用，Shizuku未就绪，跳过关闭小米钱包（${closeWalletResult.message}）")
                    }
                }
            }
            finishAsCompleted("$reason，已返回本应用")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now < nextSelfReturnActionAtMs) {
            scheduleStep((nextSelfReturnActionAtMs - now).coerceAtLeast(120L))
            return
        }

        if (currentRetry == 0 || currentRetry % 2 == 0) {
            val foreground = currentForegroundPackage().orEmpty()
            WalletAutomationRuntime.info("收尾返回本应用，当前前台：$foreground")

            val launched = launchSelfApp(aggressive = currentRetry >= 2)
            if (launched) {
                WalletAutomationRuntime.info("已通过包名/组件拉起本应用，等待切回")
            } else {
                WalletAutomationRuntime.info("拉起本应用失败，尝试全局返回后重试")
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }

        nextSelfReturnActionAtMs = now + RETURN_SELF_ACTION_INTERVAL_MS

        if (currentRetry >= RETURN_SELF_MAX_RETRY) {
            WalletAutomationRuntime.info("多次拉起仍未切回，可能被系统拦截（请开启自启动/后台弹出界面）")
            finishAsCompleted("$reason，但未自动切回本应用，请手动返回")
            return
        }

        currentRetry += 1
        scheduleStep(RETURN_SELF_RETRY_MS)
    }

    private fun launchSelfApp(aggressive: Boolean = false): Boolean {
        val shizukuLaunchResult = ShizukuBridge.startPackage(this, packageName)
        if (shizukuLaunchResult.success) {
            return true
        }

        val commonFlags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP

        val packageLaunchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val packageLaunched = runCatching {
            packageLaunchIntent?.addFlags(commonFlags)
            if (packageLaunchIntent != null) {
                startActivity(packageLaunchIntent)
            }
        }.isSuccess
        if (packageLaunched && packageLaunchIntent != null) {
            return true
        }

        val explicitIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = commonFlags
        }
        val explicitLaunched = runCatching {
            startActivity(explicitIntent)
        }.isSuccess
        if (explicitLaunched) {
            return true
        }

        if (!aggressive) {
            return false
        }

        val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
            `package` = packageName
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = commonFlags
        }
        return runCatching {
            startActivity(fallbackIntent)
        }.isSuccess
    }

    private fun isSelfAppOnTop(): Boolean {
        return currentForegroundPackage() == packageName
    }

    private fun requestStopAndReturnToWallet(message: String) {
        if (currentStep == AutomationStep.IDLE) {
            stopWorkflowByUser(message)
            return
        }
        if (isSelfAppOnTop()) {
            WalletAutomationRuntime.info("检测到已回到首页，直接停止自动流程")
            stopWorkflowByUser(message)
            return
        }

        stopReason = message
        completionReason = null
        WalletAutomationRuntime.info("收到手动停止指令，开始收尾返回小米钱包")
        transitTo(
            step = AutomationStep.RETURN_TO_WALLET_FINAL,
            triggerAfterMs = 220L
        )
    }

    private fun handleReturnToWalletFinalStep() {
        val isStopFlow = stopReason != null
        val reason = if (isStopFlow) {
            stopReason ?: "已停止自动流程"
        } else {
            completionReason ?: "任务处理完成"
        }

        if (isWalletOnTop()) {
            removeQuickActionOverlay()
            if (isStopFlow) {
                finishAsStopped("$reason，已返回小米钱包")
            } else {
                finishAsCompleted("$reason，已返回小米钱包")
            }
            return
        }

        if (currentRetry == 0 || currentRetry % 2 == 0) {
            val foreground = currentForegroundPackage().orEmpty()
            WalletAutomationRuntime.info("收尾返回小米钱包，当前前台：$foreground")
            dispatchReturnToWalletRequest(
                sceneLabel = "收尾回跳",
                recoverMessage = "拉起小米钱包失败，尝试全局返回后重试",
                attempt = currentRetry
            )
        }

        if (currentRetry >= RETURN_FINAL_MAX_RETRY) {
            if (isStopFlow) {
                finishAsStopped("$reason，但未自动切回小米钱包，请手动确认")
            } else {
                finishAsCompleted("$reason，但未自动切回小米钱包，请手动确认")
            }
            return
        }

        currentRetry += 1
        scheduleStep(RETURN_FINAL_RETRY_MS)
    }

    private fun dispatchReturnToWalletRequest(
        sceneLabel: String,
        recoverMessage: String,
        attempt: Int
    ) {
        val shizukuLaunchResult = ShizukuBridge.startPackage(this, WALLET_PACKAGE)
        when {
            shizukuLaunchResult.success -> {
                WalletAutomationRuntime.info("$sceneLabel 已通过Shizuku发起回钱包")
                if (attempt <= RETURN_WALLET_SHIZUKU_ONLY_MAX_ATTEMPT) {
                    WalletAutomationRuntime.info("$sceneLabel Shizuku优先模式：先等待前台应用切换结果")
                    return
                }
                WalletAutomationRuntime.info("$sceneLabel Shizuku拉起后仍未确认回钱包，继续执行兜底")
            }

            shizukuLaunchResult.executed -> {
                WalletAutomationRuntime.info("$sceneLabel Shizuku拉起失败：${shizukuLaunchResult.message}")
            }

            attempt == 0 -> {
                WalletAutomationRuntime.info("$sceneLabel Shizuku未就绪：${shizukuLaunchResult.message}")
            }
        }

        val shouldTryGesture = attempt <= RETURN_WALLET_GESTURE_TRY_MAX_ATTEMPT
        if (shouldTryGesture) {
            val preferLeftSwipe = shouldPreferLeftTaskSwitchGesture(attempt)
            val gestureSwitched = dispatchTaskSwitchGesture(preferLeft = preferLeftSwipe)
            if (gestureSwitched) {
                val direction = if (preferLeftSwipe) "左滑" else "右滑"
                WalletAutomationRuntime.info("$sceneLabel 已模拟全面屏${direction}切换任务")
                if (attempt <= RETURN_WALLET_GESTURE_ONLY_MAX_ATTEMPT) {
                    WalletAutomationRuntime.info("$sceneLabel 手势优先模式：先等待前台应用切换结果")
                    return
                }
                WalletAutomationRuntime.info("$sceneLabel 手势后仍未确认回钱包，继续执行拉起兜底")
            } else if (attempt <= RETURN_WALLET_GESTURE_ONLY_MAX_ATTEMPT) {
                WalletAutomationRuntime.info("$sceneLabel 模拟全面屏切换失败，提前降级拉起兜底")
            }
        }

        val lightweightLaunchSent = launchWalletByPackageIntentOnly()
        if (lightweightLaunchSent) {
            WalletAutomationRuntime.info("$sceneLabel 已通过包名标准拉起发起回钱包（无任务栈重建）")
            if (attempt <= RETURN_WALLET_LIGHT_LAUNCH_ONLY_MAX_ATTEMPT) {
                WalletAutomationRuntime.info("$sceneLabel 轻量拉起优先模式：先等待前台切换结果")
                return
            }
            WalletAutomationRuntime.info("$sceneLabel 轻量拉起后仍未确认回钱包，继续执行增强兜底")
        } else if (attempt <= RETURN_WALLET_LIGHT_LAUNCH_ONLY_MAX_ATTEMPT) {
            WalletAutomationRuntime.info("$sceneLabel 包名标准拉起失败，降级到增强拉起兜底")
        }

        if (attempt >= RETURN_WALLET_BACK_HOME_RETRY_THRESHOLD) {
            WalletAutomationRuntime.info("$sceneLabel 重试次数较多，先执行返回+桌面再拉起")
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else if (attempt >= RETURN_WALLET_FORCE_HOME_RETRY_THRESHOLD) {
            WalletAutomationRuntime.info("$sceneLabel 多次未切回钱包，先返回桌面再拉起")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        val mode = ReturnModeStore.get(this)
        val result = ReturnEngineRouter.returnToWallet(
            context = this,
            mode = mode,
            onFallbackRecoverAction = {
                WalletAutomationRuntime.info(recoverMessage)
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        )

        val channel = when (result.channel) {
            ReturnChannel.AGGRESSIVE -> "激进拉起"
            ReturnChannel.ACCESSIBILITY -> "无障碍"
        }
        val modeLabel = mode.name

        if (result.success) {
            WalletAutomationRuntime.info("$sceneLabel 已通过${channel}发起回钱包请求（模式：$modeLabel）")
        } else {
            WalletAutomationRuntime.info("$sceneLabel 回跳请求失败（通道：$channel，模式：$modeLabel）")
        }

        if (!result.reason.isNullOrBlank()) {
            WalletAutomationRuntime.info("$sceneLabel 详情：${result.reason}")
        }
    }

    private fun dispatchTaskSwitchGesture(preferLeft: Boolean): Boolean {
        val frame = rootInActiveWindow?.let { resolveScreenFrame(it) } ?: run {
            val dm = resources.displayMetrics
            ScreenFrame(width = dm.widthPixels, height = dm.heightPixels)
        }
        if (frame.width <= 0 || frame.height <= 0) {
            return false
        }

        val y = (frame.height * TASK_SWITCH_GESTURE_Y_RATIO).coerceIn(
            1f,
            (frame.height - 1).toFloat()
        )

        val startRatio = if (preferLeft) {
            TASK_SWITCH_GESTURE_START_RIGHT_RATIO
        } else {
            TASK_SWITCH_GESTURE_START_LEFT_RATIO
        }
        val endRatio = if (preferLeft) {
            TASK_SWITCH_GESTURE_END_LEFT_RATIO
        } else {
            TASK_SWITCH_GESTURE_END_RIGHT_RATIO
        }

        val startX = frame.width * startRatio
        val endX = frame.width * endRatio

        if (kotlin.math.abs(startX - endX) < frame.width * 0.2f) {
            return false
        }

        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    TASK_SWITCH_GESTURE_DURATION_MS
                )
            )
            .build()

        return dispatchGesture(gesture, null, null)
    }

    private fun shouldPreferLeftTaskSwitchGesture(attempt: Int): Boolean {
        // Xiaomi全面屏通常左滑即可切回前一个应用，周期性补一次反向手势兼容不同ROM。
        return ((attempt / 2) % 3) != 1
    }

    private fun launchWalletByPackageIntentOnly(): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(WALLET_PACKAGE) ?: return false
        return runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }.isSuccess
    }

    private fun finishAsCompleted(message: String) {
        stopWorkflowInternal()
        WalletAutomationRuntime.complete(message)
        showToast("任务处理完成")
        showScreenReminder("任务处理完成")
    }

    private fun finishAsStopped(message: String) {
        stopWorkflowInternal()
        WalletAutomationRuntime.stopByUser(message)
        showToast("已停止自动流程")
        showScreenReminder("已停止自动流程")
    }

    private fun isWalletOnTop(forceRefresh: Boolean = false): Boolean {
        return currentForegroundPackage(forceRefresh = forceRefresh) == WALLET_PACKAGE
    }

    private fun resolveExternalPackageForClose(): String? {
        val preferred = externalPackageName
        if (isClosableExternalPackage(preferred)) {
            return preferred
        }

        val current = currentForegroundPackage()
        if (isClosableExternalPackage(current)) {
            return current
        }

        return null
    }

    private fun isClosableExternalPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) {
            return false
        }
        if (packageName == WALLET_PACKAGE) {
            return false
        }
        if (packageName == this.packageName) {
            return false
        }
        return true
    }

    private fun currentForegroundPackage(forceRefresh: Boolean = false): String? {
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        if (!rootPkg.isNullOrBlank()) {
            return rootPkg
        }

        val shizukuPkg = queryForegroundByShizuku(forceRefresh = forceRefresh)
        if (!shizukuPkg.isNullOrBlank()) {
            return shizukuPkg
        }

        val usagePkg = queryForegroundByUsageAccess(forceRefresh = forceRefresh)
        if (!usagePkg.isNullOrBlank()) {
            return usagePkg
        }

        return lastEventPackageName
    }

    private fun armWalletSettleWindow() {
        val now = SystemClock.elapsedRealtime()
        val remaining = walletSettleUntilMs - now
        if (remaining > 0L) {
            return
        }

        walletSettleUntilMs = now + WALLET_SETTLE_WAIT_MS
        nextWalletSettleNotifyAtMs = 0L
        WalletAutomationRuntime.info("已进入小米钱包，先等待3-4秒再开始识别")
        showStageReminder("等待钱包页面稳定")
    }

    private fun shouldArmWalletSettleOnWalletEnter(): Boolean {
        return currentStep == AutomationStep.WAIT_WALLET_HOME ||
            currentStep == AutomationStep.RETURN_TO_WALLET ||
            currentStep == AutomationStep.RETURN_TO_WALLET_FINAL
    }

    private fun shouldWaitWalletSettle(step: AutomationStep): Boolean {
        if (step != AutomationStep.WAIT_WALLET_HOME &&
            step != AutomationStep.WAIT_WATCH_BUTTON &&
            step != AutomationStep.WAIT_REWARD_ENTRY &&
            step != AutomationStep.WAIT_REWARD_RESULT
        ) {
            return false
        }

        if (!isWalletOnTop()) {
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val remainingMs = walletSettleUntilMs - now
        if (remainingMs <= 0L) {
            return false
        }

        if (now >= nextWalletSettleNotifyAtMs) {
            val sec = ((remainingMs + 999L) / 1000L).toInt()
            WalletAutomationRuntime.info("钱包加载缓冲中，剩余${sec}秒")
            nextWalletSettleNotifyAtMs = now + WALLET_SETTLE_PROGRESS_INTERVAL_MS
        }
        scheduleStep(minOf(WALLET_SETTLE_PROGRESS_INTERVAL_MS, remainingMs))
        return true
    }

    private fun queryForegroundByShizuku(forceRefresh: Boolean = false): String? {
        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh && now - lastShizukuQueryAtMs < SHIZUKU_TOP_QUERY_INTERVAL_MS) {
            return lastShizukuForegroundPackage
        }

        lastShizukuQueryAtMs = now
        val pkg = ShizukuBridge.resolveForegroundPackage(this)
        if (pkg.isNullOrBlank()) {
            if (forceRefresh) {
                lastShizukuForegroundPackage = null
                return null
            }
            return lastShizukuForegroundPackage
        }
        lastShizukuForegroundPackage = pkg
        return pkg
    }

    private fun queryForegroundByUsageAccess(forceRefresh: Boolean = false): String? {
        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh && now - lastUsageQueryAtMs < USAGE_TOP_QUERY_INTERVAL_MS) {
            return lastUsageForegroundPackage
        }

        lastUsageQueryAtMs = now
        val usagePkg = UsageAccessStatusChecker.queryForegroundPackage(this)
        if (usagePkg.isNullOrBlank()) {
            if (forceRefresh) {
                lastUsageForegroundPackage = null
                return null
            }
            return lastUsageForegroundPackage
        }
        lastUsageForegroundPackage = usagePkg
        return usagePkg
    }

    private fun clickByKeywords(
        keywords: List<String>,
        preferredPackage: String?,
        preferCenter: Boolean = true
    ): Boolean {
        val candidate = findBestNodeCandidate(keywords, preferredPackage, preferCenter) ?: return false
        return clickNode(candidate.node)
    }

    private fun containsKeywordsByNode(
        keywords: List<String>,
        preferredPackage: String?,
        preferCenter: Boolean = true
    ): Boolean {
        return findBestNodeCandidate(keywords, preferredPackage, preferCenter) != null
    }

    private fun watchStageKeywordsForCurrentRetry(): List<String> {
        return if (currentRetry >= WATCH_BUTTON_LOOSE_MATCH_RETRY_THRESHOLD) {
            SECOND_STEP_STRICT_KEYWORDS + SECOND_STEP_LOOSE_KEYWORDS
        } else {
            SECOND_STEP_STRICT_KEYWORDS
        }
    }

    private fun detectWatchStageNodeHit(targetKeywords: List<String>): WatchStageNodeHit? {
        val combinedKeywords = targetKeywords + FINISH_REWARD_KEYWORDS
        val combinedCandidate = findBestNodeCandidate(
            keywords = combinedKeywords,
            preferredPackage = WALLET_PACKAGE,
            preferCenter = true
        ) ?: return null

        if (combinedCandidate.score < NODE_MATCH_MIN_SCORE) {
            return null
        }

        val label = normalizeForMatch(buildNodeLabel(combinedCandidate.node))
        val isFinish = FINISH_REWARD_KEYWORDS.any { keyword ->
            val normalizedKeyword = normalizeForMatch(keyword)
            normalizedKeyword.isNotEmpty() && label.contains(normalizedKeyword)
        }

        val type = if (isFinish) WatchStageHitType.FINISH else WatchStageHitType.WATCH
        return WatchStageNodeHit(
            type = type,
            node = combinedCandidate.node
        )
    }

    private fun findBestNodeCandidate(
        keywords: List<String>,
        preferredPackage: String?,
        preferCenter: Boolean
    ): NodeCandidate? {
        val root = rootInActiveWindow ?: return null
        val screen = resolveScreenFrame(root)
        val candidates = mutableListOf<NodeCandidate>()

        keywords.forEach { keyword ->
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            nodes.forEach { node ->
                if (!node.isVisibleToUser) {
                    return@forEach
                }

                if (preferredPackage != null && node.packageName?.toString() != preferredPackage) {
                    return@forEach
                }

                val label = buildNodeLabel(node)
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.isEmpty) {
                    return@forEach
                }

                val score = scoreNode(
                    label = label,
                    keyword = keyword,
                    node = node,
                    bounds = bounds,
                    screen = screen,
                    preferCenter = preferCenter
                )
                if (score > 0) {
                    candidates += NodeCandidate(node = node, score = score, bounds = bounds)
                }
            }
        }

        return candidates.maxByOrNull { it.score }
    }

    private fun scoreNode(
        label: String,
        keyword: String,
        node: AccessibilityNodeInfo,
        bounds: Rect,
        screen: ScreenFrame,
        preferCenter: Boolean
    ): Int {
        if (label.isEmpty()) {
            return 0
        }

        val normalizedLabel = normalizeForMatch(label)
        val normalizedKeyword = normalizeForMatch(keyword)
        if (normalizedKeyword.isEmpty()) {
            return 0
        }

        var score = 0
        if (normalizedLabel == normalizedKeyword) {
            score += 180
        }
        if (normalizedLabel.contains(normalizedKeyword)) {
            score += 120
        }

        if (normalizedKeyword.length == 1 && normalizedLabel.length > 4 &&
            normalizedLabel != normalizedKeyword
        ) {
            score -= 70
        }

        if (node.isClickable) {
            score += 18
        }
        if (node.isEnabled) {
            score += 10
        }

        if (preferCenter) {
            score += centerBonus(bounds, screen)
        }

        return score
    }

    private fun tryClickByOcr(
        keywords: List<String>,
        stepLabel: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ): Boolean {
        return runOcrSnapshot(
            stepLabel = stepLabel,
            onRecognized = { text, width, height ->
                val target = findBestOcrTargetRect(
                    text = text,
                    keywords = keywords,
                    width = width,
                    height = height
                )

                if (target != null && performGestureClick(target)) {
                    onSuccess()
                } else {
                    onFailure()
                }
            },
            onFailure = onFailure
        )
    }

    private fun runOcrSnapshot(
        stepLabel: String,
        onRecognized: (Text, Int, Int) -> Unit,
        onFailure: () -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            WalletAutomationRuntime.info("系统版本不支持截图OCR")
            return false
        }
        if (ocrInProgress) {
            return true
        }

        val now = SystemClock.elapsedRealtime()
        if (now < nextAllowedScreenshotAtMs) {
            val waitMs = (nextAllowedScreenshotAtMs - now).coerceAtLeast(OCR_COOLDOWN_RETRY_MIN_MS)
            if (now - lastOcrCooldownLogAtMs >= OCR_COOLDOWN_LOG_INTERVAL_MS) {
                WalletAutomationRuntime.info("OCR节流中，${waitMs}ms后重试")
                lastOcrCooldownLogAtMs = now
            }
            scheduleStep(waitMs)
            return true
        }

        if (!screenshotNoticeLogged) {
            WalletAutomationRuntime.info("截图仅在内存中用于识别，不会保存到本地")
            screenshotNoticeLogged = true
        }

        WalletAutomationRuntime.info("节点识别失败，尝试OCR：$stepLabel")
        ocrInProgress = true
        nextAllowedScreenshotAtMs = now + OCR_SCREENSHOT_MIN_INTERVAL_MS

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = screenshotResultToBitmap(screenshot)
                    if (bitmap == null) {
                        ocrInProgress = false
                        onFailure()
                        return
                    }

                    val width = bitmap.width
                    val height = bitmap.height

                    textRecognizer
                        .process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { text ->
                            onRecognized(text, width, height)
                        }
                        .addOnFailureListener { error ->
                            WalletAutomationRuntime.info("OCR识别失败：${error.message.orEmpty()}")
                            onFailure()
                        }
                        .addOnCompleteListener {
                            if (!bitmap.isRecycled) {
                                bitmap.recycle()
                            }
                            ocrInProgress = false
                        }
                }

                override fun onFailure(errorCode: Int) {
                    ocrInProgress = false
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        nextAllowedScreenshotAtMs =
                            SystemClock.elapsedRealtime() + OCR_SCREENSHOT_MIN_INTERVAL_MS
                        WalletAutomationRuntime.info("截图过快，系统限流，稍后自动重试")
                        scheduleStep(OCR_SCREENSHOT_MIN_INTERVAL_MS)
                        return
                    }

                    WalletAutomationRuntime.info("截图失败：${formatScreenshotError(errorCode)}")
                    onFailure()
                }
            }
        )

        return true
    }

    private fun formatScreenshotError(errorCode: Int): String {
        return when (errorCode) {
            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统内部错误($errorCode)"
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                "无截图访问权限($errorCode)，请重开无障碍权限"

            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图调用太频繁($errorCode)"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "无效显示器($errorCode)"
            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "无效窗口($errorCode)"
            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面不允许截图($errorCode)"
            else -> "未知错误($errorCode)"
        }
    }

    private fun screenshotResultToBitmap(result: ScreenshotResult): Bitmap? {
        val hardwareBuffer = result.hardwareBuffer
        return try {
            val colorSpace = result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace) ?: return null
            wrapped.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        } finally {
            hardwareBuffer.close()
        }
    }

    private fun findBestOcrTargetRect(
        text: Text,
        keywords: List<String>,
        width: Int,
        height: Int
    ): Rect? {
        return findBestOcrTarget(
            text = text,
            keywords = keywords,
            width = width,
            height = height
        )?.rect
    }

    private fun findBestOcrTarget(
        text: Text,
        keywords: List<String>,
        width: Int,
        height: Int
    ): OcrMatch? {
        val screen = ScreenFrame(width = width, height = height)
        val normalizedKeywords = keywords.map { normalizeForMatch(it) }
        var bestMatch: OcrMatch? = null

        fun evaluate(content: String, rect: Rect?) {
            if (rect == null || rect.isEmpty || content.isBlank()) {
                return
            }

            if (isIgnoredOcrSystemText(content)) {
                return
            }

            val normalizedContent = normalizeForMatch(content)
            if (normalizedContent.isEmpty()) {
                return
            }

            var score = 0
            normalizedKeywords.forEach { keyword ->
                if (keyword.isEmpty()) {
                    return@forEach
                }

                if (normalizedContent == keyword) {
                    score = maxOf(score, 200 + keyword.length)
                }
                if (normalizedContent.contains(keyword)) {
                    score = maxOf(score, 130 + minOf(keyword.length, normalizedContent.length))
                }

                if (keyword.length == 1 && normalizedContent.length > 4 &&
                    normalizedContent != keyword
                ) {
                    score -= 60
                }
            }

            if (score <= 0) {
                return
            }

            score += centerBonus(rect, screen)

            if (bestMatch == null || score > bestMatch!!.score) {
                bestMatch = OcrMatch(rect = rect, score = score)
            }
        }

        text.textBlocks.forEach { block ->
            evaluate(block.text, block.boundingBox)
            block.lines.forEach { line ->
                evaluate(line.text, line.boundingBox)
                line.elements.forEach { element ->
                    evaluate(element.text, element.boundingBox)
                }
            }
        }

        return bestMatch?.takeIf { it.score >= OCR_MATCH_MIN_SCORE }
    }

    private fun isIgnoredOcrSystemText(content: String): Boolean {
        val normalized = normalizeForMatch(content)
        if (normalized.isEmpty()) {
            return true
        }

        return normalized.contains("识别") ||
            normalized.contains("执行计划") ||
            normalized.contains("自动流程已启动") ||
            normalized.contains("当前步骤")
    }

    private fun centerBonus(bounds: Rect, screen: ScreenFrame): Int {
        val centerX = screen.width / 2f
        val centerY = screen.height / 2f

        val dxRatio = abs(bounds.centerX() - centerX) / centerX.coerceAtLeast(1f)
        val dyRatio = abs(bounds.centerY() - centerY) / centerY.coerceAtLeast(1f)
        val distance = dxRatio + dyRatio

        var score = ((1.4f - distance).coerceIn(-1f, 1.4f) * 45f).toInt()

        val yRatio = bounds.centerY().toFloat() / screen.height.coerceAtLeast(1)
        if (yRatio in 0.28f..0.75f) {
            score += 30
        }
        if (yRatio < 0.12f || yRatio > 0.88f) {
            score -= 70
        }

        return score
    }

    private fun resolveScreenFrame(root: AccessibilityNodeInfo): ScreenFrame {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            return ScreenFrame(
                width = bounds.width(),
                height = bounds.height()
            )
        }

        val dm = resources.displayMetrics
        return ScreenFrame(width = dm.widthPixels, height = dm.heightPixels)
    }

    private fun normalizeForMatch(value: String): String {
        return value
            .lowercase()
            .replace("\n", "")
            .replace(" ", "")
            .replace("：", "")
            .replace(":", "")
            .replace("，", "")
            .replace(",", "")
            .replace("。", "")
            .replace(".", "")
            .replace("-", "")
    }

    private fun buildNodeLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        return (text + desc).replace("\n", "")
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return performGestureClick(bounds)
    }

    private fun clickRewardCenterFallback(): Boolean {
        return performGestureClickNormalized(xPercent = 0.5f, yPercent = 0.56f)
    }

    private fun performGestureClickNormalized(xPercent: Float, yPercent: Float): Boolean {
        val dm = resources.displayMetrics
        val x = (dm.widthPixels * xPercent).toInt()
        val y = (dm.heightPixels * yPercent).toInt()
        return performGestureClick(Rect(x - 4, y - 4, x + 4, y + 4))
    }

    private fun performGestureClick(bounds: Rect): Boolean {
        if (bounds.isEmpty) {
            return false
        }

        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 90)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return dispatchGesture(gesture, null, null)
    }

    private fun retryOrFail(
        maxRetry: Int,
        nextDelayMs: Long,
        failMessage: String,
        retryHint: String
    ) {
        if (currentRetry >= maxRetry) {
            failWorkflow(failMessage)
            return
        }

        currentRetry += 1
        if (currentRetry == 1 || currentRetry % 3 == 0) {
            WalletAutomationRuntime.info("$retryHint（第${currentRetry}次重试）")
        }
        scheduleStep(nextDelayMs)
    }

    private fun transitTo(step: AutomationStep, triggerAfterMs: Long, logStep: Boolean = true) {
        currentStep = step
        currentRetry = 0
        if (logStep) {
            WalletAutomationRuntime.step(step.label)
        }
        showStageReminder(step.label)
        scheduleStep(triggerAfterMs)
    }

    private fun scheduleStep(delayMs: Long) {
        val safeDelayMs = delayMs.coerceAtLeast(MIN_STEP_DELAY_MS)
        val now = SystemClock.elapsedRealtime()
        val targetAtMs = now + safeDelayMs

        if (scheduledStepAtMs != 0L &&
            now < scheduledStepAtMs &&
            targetAtMs >= scheduledStepAtMs - 120L
        ) {
            return
        }

        mainHandler.removeCallbacks(stepRunnable)
        scheduledStepAtMs = targetAtMs
        mainHandler.postDelayed(stepRunnable, safeDelayMs)
    }

    private fun showStageReminder(stepLabel: String) {
        val now = SystemClock.elapsedRealtime()
        val sameStep = stepLabel == lastStageReminderText
        if (sameStep && now - lastStageReminderAtMs < STAGE_REMINDER_MIN_INTERVAL_MS) {
            return
        }

        lastStageReminderText = stepLabel
        lastStageReminderAtMs = now
        showScreenReminder(stepLabel)
    }

    private fun failWorkflow(reason: String) {
        stopWorkflowInternal()
        WalletAutomationRuntime.fail(reason)
        showToast(reason)
        showScreenReminder("流程执行失败")
    }

    private fun stopWorkflowByUser(message: String) {
        stopReason = message
        completionReason = null
        finishAsStopped(message)
    }

    private fun announceHit(message: String) {
        WalletAutomationRuntime.info(message)
        showToast(message)
    }

    private fun stopWorkflowInternal() {
        currentStep = AutomationStep.IDLE
        currentRetry = 0
        roundCount = 1

        externalPackageName = null
        externalWaitDeadlineMs = 0L
        nextExternalNotifyAtMs = 0L
        completionReason = null
        stopReason = null
        nextSelfReturnActionAtMs = 0L
        walletCloseAttemptedForFinalReturn = false
        lastEventPackageName = null
        lastShizukuForegroundPackage = null
        lastShizukuQueryAtMs = 0L
        lastUsageForegroundPackage = null
        lastUsageQueryAtMs = 0L
        lastStageReminderText = null
        lastStageReminderAtMs = 0L
        finishHintFirstSeenAtMs = 0L
        walletSettleUntilMs = 0L
        nextWalletSettleNotifyAtMs = 0L
        rewardResultForegroundConfirmRetry = 0

        ocrInProgress = false
        nextAllowedScreenshotAtMs = 0L
        lastOcrCooldownLogAtMs = 0L
        lastEventHandledAtMs = 0L
        scheduledStepAtMs = 0L

        mainHandler.removeCallbacks(stepRunnable)
        mainHandler.removeCallbacks(removeFloatingReminderRunnable)
        removeFloatingReminderInternal()
        removeQuickActionOverlayInternal()
        WalletAutomationRequestStore.clearPending(this)
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showScreenReminder(message: String) {
        mainHandler.post {
            removeFloatingReminderInternal()

            val textView = TextView(this).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setBackgroundColor(Color.parseColor("#CC1F2937"))
            }

            val container = FrameLayout(this).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(textView)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(96)
            }

            val added = runCatching {
                windowManager.addView(container, params)
            }.isSuccess
            if (!added) {
                return@post
            }

            floatingReminderView = container
            mainHandler.removeCallbacks(removeFloatingReminderRunnable)
            mainHandler.postDelayed(removeFloatingReminderRunnable, 2600L)
        }
    }

    private fun removeFloatingReminder() {
        mainHandler.post { removeFloatingReminderInternal() }
    }

    private fun removeFloatingReminderInternal() {
        val view = floatingReminderView ?: return
        floatingReminderView = null
        runCatching {
            windowManager.removeView(view)
        }
    }

    private fun showQuickActionOverlay(message: String) {
        mainHandler.post {
            if (quickActionOverlayView != null) {
                return@post
            }

            val titleView = TextView(this).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(8), dp(0), dp(8), dp(8))
            }

            val walletButton = Button(this).apply {
                text = "回钱包"
                setOnClickListener {
                    WalletAutomationRuntime.info("悬浮按钮：手动触发回小米钱包")
                    dispatchReturnToWalletRequest(
                        sceneLabel = "悬浮按钮回跳",
                        recoverMessage = "悬浮按钮回跳失败，执行返回+桌面再重试",
                        attempt = RETURN_WALLET_BACK_HOME_RETRY_THRESHOLD
                    )
                }
            }

            val selfButton = Button(this).apply {
                text = "回本应用"
                setOnClickListener {
                    WalletAutomationRuntime.info("悬浮按钮：手动触发回本应用")
                    val launched = launchSelfApp(aggressive = true)
                    if (!launched) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                }
            }

            val closeButton = Button(this).apply {
                text = "收起"
                setOnClickListener { removeQuickActionOverlayInternal() }
            }

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(walletButton)
                addView(selfButton)
                addView(closeButton)
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CC111827"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(titleView)
                addView(buttonRow)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(96)
            }

            val added = runCatching {
                windowManager.addView(container, params)
            }.isSuccess
            if (!added) {
                return@post
            }
            quickActionOverlayView = container
        }
    }

    private fun removeQuickActionOverlay() {
        mainHandler.post { removeQuickActionOverlayInternal() }
    }

    private fun removeQuickActionOverlayInternal() {
        val view = quickActionOverlayView ?: return
        quickActionOverlayView = null
        runCatching {
            windowManager.removeView(view)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private val stepRunnable = Runnable {
        scheduledStepAtMs = 0L

        if (WalletAutomationRuntime.consumeStopRequest()) {
            requestStopAndReturnToWallet("用户主动停止流程")
            return@Runnable
        }

        if (currentStep != AutomationStep.IDLE && !ocrInProgress) {
            runCurrentStep("timer")
        }
    }

    private data class ScreenFrame(
        val width: Int,
        val height: Int
    )

    private data class NodeCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val bounds: Rect
    )

    private data class OcrMatch(
        val rect: Rect,
        val score: Int
    )

    private data class WatchStageNodeHit(
        val type: WatchStageHitType,
        val node: AccessibilityNodeInfo
    )

    private enum class WatchStageHitType {
        WATCH,
        FINISH
    }

    private enum class AutomationStep(val label: String) {
        IDLE("空闲"),
        WAIT_WALLET_HOME("钱包首页阶段"),
        WAIT_WATCH_BUTTON("任务入口阶段"),
        WAIT_JUMP_CONFIRM("跳转确认阶段"),
        WAIT_EXTERNAL_SWITCH("等待外部应用切换"),
        WAIT_EXTERNAL_COUNTDOWN("外部应用停留中"),
        RETURN_TO_WALLET("返回小米钱包"),
        WAIT_REWARD_ENTRY("奖励弹窗阶段"),
        WAIT_REWARD_RESULT("结果判断阶段"),
        RETURN_TO_SELF_APP_FINAL("收尾返回本应用"),
        RETURN_TO_WALLET_FINAL("收尾返回小米钱包")
    }

    companion object {
        private const val TAG = "WalletA11yService"
        private const val WALLET_PACKAGE = "com.mipay.wallet"

        private const val EXTERNAL_WAIT_MIN_MS = 11_000L
        private const val EXTERNAL_WAIT_MAX_MS = 15_000L
        private const val EXTERNAL_PROGRESS_INTERVAL_MS = 2_000L
        private const val WALLET_SETTLE_WAIT_MS = 3_500L
        private const val WALLET_SETTLE_PROGRESS_INTERVAL_MS = 1_200L
        private const val REWARD_RESULT_FOREGROUND_CONFIRM_MAX_RETRY = 4
        private const val REWARD_RESULT_FOREGROUND_CONFIRM_INTERVAL_MS = 1_100L

        private const val OCR_SCREENSHOT_MIN_INTERVAL_MS = 1_000L
        private const val OCR_COOLDOWN_RETRY_MIN_MS = 900L
        private const val OCR_COOLDOWN_LOG_INTERVAL_MS = 1_500L
        private const val EVENT_HANDLE_MIN_INTERVAL_MS = 1200L
        private const val MIN_STEP_DELAY_MS = 900L
        private const val STAGE_REMINDER_MIN_INTERVAL_MS = 1500L
        private const val WATCH_BUTTON_LOOSE_MATCH_RETRY_THRESHOLD = 4
        private const val NODE_MATCH_MIN_SCORE = 145
        private const val OCR_MATCH_MIN_SCORE = 150
        private const val FINISH_HIT_PRIORITY_BONUS = 20
        private const val RETURN_SELF_MAX_RETRY = 8
        private const val RETURN_SELF_RETRY_MS = 900L
        private const val RETURN_SELF_ACTION_INTERVAL_MS = 1_100L
        private const val FINISH_CONFIRM_WAIT_MS = 3_000L
        private const val RETURN_WALLET_GESTURE_ONLY_MAX_ATTEMPT = 2
        private const val RETURN_WALLET_GESTURE_TRY_MAX_ATTEMPT = 6
        private const val RETURN_WALLET_SHIZUKU_ONLY_MAX_ATTEMPT = 2
        private const val RETURN_WALLET_LIGHT_LAUNCH_ONLY_MAX_ATTEMPT = 4
        private const val RETURN_WALLET_FORCE_HOME_RETRY_THRESHOLD = 4
        private const val RETURN_WALLET_BACK_HOME_RETRY_THRESHOLD = 6
        private const val RETURN_FINAL_MAX_RETRY = 8
        private const val RETURN_FINAL_RETRY_MS = 900L
        private const val SHIZUKU_TOP_QUERY_INTERVAL_MS = 500L
        private const val USAGE_TOP_QUERY_INTERVAL_MS = 650L
        private const val TASK_SWITCH_GESTURE_Y_RATIO = 0.95f
        private const val TASK_SWITCH_GESTURE_START_RIGHT_RATIO = 0.86f
        private const val TASK_SWITCH_GESTURE_END_LEFT_RATIO = 0.14f
        private const val TASK_SWITCH_GESTURE_START_LEFT_RATIO = 0.14f
        private const val TASK_SWITCH_GESTURE_END_RIGHT_RATIO = 0.86f
        private const val TASK_SWITCH_GESTURE_DURATION_MS = 220L

        private val FIRST_STEP_KEYWORDS = listOf(
            "领视频会员",
            "领取视频会员"
        )

        private val SECOND_STEP_STRICT_KEYWORDS = listOf(
            "看10秒领会员",
            "看10s领会员",
            "看10秒 领会员",
            "看10s 领会员",
            "再看10秒 继续领会员",
            "再看10秒继续领会员",
            "再看10s 继续领会员",
            "再看10s继续领会员"
        )

        private val SECOND_STEP_LOOSE_KEYWORDS = listOf(
            "看10秒",
            "看10s",
            "再看10秒",
            "再看10s"
        )

        private val THIRD_STEP_KEYWORDS = listOf(
            "点击立即跳转",
            "立即跳转"
        )

        private val CLICK_CLAIM_MEMBER_KEYWORDS = listOf(
            "点击领会员",
            "点击 领会员"
        )

        private val REWARD_ENTRY_KEYWORDS = listOf(
            "開",
            "开",
            "领取奖励"
        )

        private val CONTINUE_REWARD_KEYWORDS = listOf(
            "再看10秒",
            "再看10s",
            "继续领会员",
            "继续领取会员",
            "继续领取"
        )

        private val FINISH_REWARD_KEYWORDS = listOf(
            "开提醒",
            "會員不漏領",
            "会员不漏领"
        )
    }
}
