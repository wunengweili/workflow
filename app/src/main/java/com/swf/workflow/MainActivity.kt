package com.swf.workflow

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.swf.workflow.accessibility.AccessibilityNavigator
import com.swf.workflow.accessibility.AccessibilityStatusChecker
import com.swf.workflow.accessibility.UsageAccessStatusChecker
import com.swf.workflow.accessibility.WalletAutomationRequestStore
import com.swf.workflow.accessibility.WalletAutomationRuntime
import com.swf.workflow.feature.chat.ChatScreen
import com.swf.workflow.feature.execution.ExecutionScreen
import com.swf.workflow.feature.home.ChatRole
import com.swf.workflow.feature.home.ChatUiMessage
import com.swf.workflow.feature.home.HomeChatState
import com.swf.workflow.feature.home.HomeScreen
import com.swf.workflow.feature.home.HomeUiState
import com.swf.workflow.feature.settings.SettingsScreen
import com.swf.workflow.llm.LlmConfig
import com.swf.workflow.llm.LlmConfigBundle
import com.swf.workflow.llm.LlmConfigStore
import com.swf.workflow.llm.OpenAiChatMessage
import com.swf.workflow.llm.OpenAiCompatClient
import com.swf.workflow.shizuku.ShizukuBridge
import com.swf.workflow.ui.theme.WorkflowTheme
import com.swf.workflow.wallet.LaunchResult
import com.swf.workflow.wallet.WalletLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val homeUiState = mutableStateOf(
        HomeUiState(
            accessibilityEnabled = false,
            usageAccessEnabled = false,
            shizukuReady = false,
            shizukuStatusLabel = "未检测"
        )
    )

    private val chatUiState = mutableStateOf(HomeChatState())
    private val llmConfigsState = mutableStateOf<List<LlmConfig>>(emptyList())
    private val activeLlmConfigIdState = mutableStateOf("")
    private val settingsSelectedConfigIdState = mutableStateOf("")

    private var chatMessageId = 0L

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshAccessibilityState()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        refreshAccessibilityState()
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) {
                return@OnRequestPermissionResultListener
            }

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                showToast(getString(R.string.shizuku_permission_granted))
                WalletAutomationRuntime.info(getString(R.string.shizuku_permission_granted))
            } else {
                showToast(getString(R.string.shizuku_permission_denied_hint))
                WalletAutomationRuntime.info(getString(R.string.shizuku_permission_denied_hint))
            }
            refreshAccessibilityState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        refreshAccessibilityState()
        loadLlmBundle()

        setContent {
            var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
            val runtimeState by WalletAutomationRuntime.state.collectAsState()
            val mediaPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                if (uri != null) {
                    updateChatState {
                        it.copy(
                            selectedImageUri = uri,
                            errorMessage = null
                        )
                    }
                }
            }

            val currentChatState = chatUiState.value
            val currentConfigs = llmConfigsState.value
            val currentActiveConfigId = activeLlmConfigIdState.value
            val currentSettingsConfigId = settingsSelectedConfigIdState.value

            WorkflowTheme {
                Scaffold(
                    bottomBar = {
                        if (selectedTab != AppTab.EXECUTION) {
                            NavigationBar {
                                AppTab.entries
                                    .filter { it.showInBottomBar }
                                    .forEach { tab ->
                                        NavigationBarItem(
                                            selected = selectedTab == tab,
                                            onClick = { selectedTab = tab },
                                            icon = {
                                                Icon(
                                                    imageVector = tab.icon,
                                                    contentDescription = getString(tab.titleRes)
                                                )
                                            },
                                            label = { Text(text = getString(tab.titleRes)) }
                                        )
                                    }
                            }
                        }
                    }
                ) { innerPadding ->
                    when (selectedTab) {
                        AppTab.HOME -> {
                            HomeScreen(
                                uiState = homeUiState.value,
                                onOpenAccessibilitySettings = {
                                    val opened = AccessibilityNavigator.openAccessibilitySettings(this)
                                    if (!opened) {
                                        showToast(getString(R.string.a11y_settings_failed))
                                    }
                                },
                                onOpenUsageAccessSettings = {
                                    val opened = AccessibilityNavigator.openUsageAccessSettings(this)
                                    if (!opened) {
                                        showToast(getString(R.string.usage_access_settings_failed))
                                    }
                                },
                                onStartAutomation = {
                                    if (!homeUiState.value.accessibilityEnabled) {
                                        showToast(getString(R.string.automation_requires_a11y))
                                        WalletAutomationRuntime.info(getString(R.string.automation_requires_a11y))
                                    } else {
                                        if (!homeUiState.value.usageAccessEnabled) {
                                            showToast(getString(R.string.usage_access_recommended))
                                            WalletAutomationRuntime.info(getString(R.string.usage_access_recommended))
                                        }
                                        notifyShizukuBeforeStart()
                                        WalletAutomationRuntime.startFlow()
                                        WalletAutomationRuntime.info("准备打开小米钱包")
                                        WalletAutomationRequestStore.markPending(this)
                                        when (WalletLauncher.launchMiWallet(this)) {
                                            LaunchResult.Success -> {
                                                showToast(getString(R.string.automation_started))
                                                WalletAutomationRuntime.step(
                                                    step = "等待钱包页面",
                                                    message = "已打开小米钱包，等待页面加载"
                                                )
                                                selectedTab = AppTab.EXECUTION
                                            }

                                            LaunchResult.FailedNotFound,
                                            is LaunchResult.FailedException -> {
                                                WalletAutomationRequestStore.clearPending(this)
                                                showToast(getString(R.string.wallet_launch_failed))
                                                WalletAutomationRuntime.fail(getString(R.string.wallet_launch_failed))
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        AppTab.EXECUTION -> {
                            ExecutionScreen(
                                runtimeState = runtimeState,
                                onStopWorkflow = {
                                    WalletAutomationRuntime.requestStop(this)
                                    showToast(getString(R.string.execution_stop_sent))
                                },
                                onClearLogs = {
                                    WalletAutomationRuntime.clearLogs()
                                    showToast(getString(R.string.execution_logs_cleared))
                                },
                                onBackToHome = {
                                    if (runtimeState.isRunning) {
                                        WalletAutomationRuntime.requestStop(this)
                                        showToast(getString(R.string.automation_stop_on_return_home))
                                    }
                                    selectedTab = AppTab.HOME
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        AppTab.CHAT -> {
                            ChatScreen(
                                chatState = currentChatState,
                                configs = currentConfigs,
                                activeConfigId = currentActiveConfigId,
                                onSwitchConfig = { configId ->
                                    switchActiveLlmConfig(configId)
                                },
                                onChatInputChange = { input ->
                                    updateChatState {
                                        it.copy(
                                            inputText = input,
                                            errorMessage = null
                                        )
                                    }
                                },
                                onPickImage = {
                                    mediaPickerLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                onClearSelectedImage = {
                                    updateChatState {
                                        it.copy(selectedImageUri = null)
                                    }
                                },
                                onSendChatMessage = {
                                    sendChatMessage()
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        AppTab.SETTINGS -> {
                            SettingsScreen(
                                configs = currentConfigs,
                                selectedConfigId = currentSettingsConfigId,
                                activeConfigId = currentActiveConfigId,
                                onSelectConfig = { configId ->
                                    if (currentConfigs.any { it.id == configId }) {
                                        settingsSelectedConfigIdState.value = configId
                                    }
                                },
                                onSetActiveConfig = { configId ->
                                    switchActiveLlmConfig(configId)
                                },
                                onAddConfig = {
                                    addLlmConfig()
                                },
                                onDeleteConfig = {
                                    deleteSelectedLlmConfig()
                                },
                                onNameChange = { value ->
                                    updateSelectedLlmConfig { config -> config.copy(name = value) }
                                },
                                onBaseUrlChange = { value ->
                                    updateSelectedLlmConfig { config -> config.copy(baseUrl = value) }
                                },
                                onApiKeyChange = { value ->
                                    updateSelectedLlmConfig { config -> config.copy(apiKey = value) }
                                },
                                onModelChange = { value ->
                                    updateSelectedLlmConfig { config -> config.copy(model = value) }
                                },
                                onThinkingChange = { enabled ->
                                    updateSelectedLlmConfig { config ->
                                        config.copy(enableThinking = enabled)
                                    }
                                },
                                onSave = {
                                    saveLlmConfigs()
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityState()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    private fun loadLlmBundle() {
        val bundle = LlmConfigStore.getBundle(this)
        llmConfigsState.value = bundle.configs
        activeLlmConfigIdState.value = bundle.activeConfigId
        settingsSelectedConfigIdState.value = bundle.activeConfigId
    }

    private fun refreshAccessibilityState() {
        val shizukuState = ShizukuBridge.queryState(this)
        homeUiState.value = HomeUiState(
            accessibilityEnabled = AccessibilityStatusChecker.isServiceEnabled(this),
            usageAccessEnabled = UsageAccessStatusChecker.isEnabled(this),
            shizukuReady = shizukuState.ready,
            shizukuStatusLabel = shizukuState.statusLabel
        )
    }

    private fun notifyShizukuBeforeStart() {
        val shizukuState = ShizukuBridge.queryState(this)
        when {
            shizukuState.ready -> {
                WalletAutomationRuntime.info(getString(R.string.shizuku_ready_hint))
            }

            !shizukuState.installed -> {
                showToast(getString(R.string.shizuku_not_installed_hint))
                WalletAutomationRuntime.info(getString(R.string.shizuku_not_installed_hint))
            }

            !shizukuState.binderAlive -> {
                showToast(getString(R.string.shizuku_not_running_hint))
                WalletAutomationRuntime.info(getString(R.string.shizuku_not_running_hint))
            }

            !shizukuState.permissionGranted -> {
                val requested =
                    ShizukuBridge.requestPermissionIfNeeded(SHIZUKU_PERMISSION_REQUEST_CODE)
                if (requested) {
                    showToast(getString(R.string.shizuku_permission_request_hint))
                    WalletAutomationRuntime.info(getString(R.string.shizuku_permission_request_hint))
                } else {
                    showToast(getString(R.string.shizuku_permission_denied_hint))
                    WalletAutomationRuntime.info(getString(R.string.shizuku_permission_denied_hint))
                }
            }
        }
    }

    private fun updateSelectedLlmConfig(transform: (LlmConfig) -> LlmConfig) {
        val selectedId = resolveSelectedConfigId()
        if (selectedId.isBlank()) {
            return
        }

        llmConfigsState.value = llmConfigsState.value.map { config ->
            if (config.id == selectedId) {
                transform(config)
            } else {
                config
            }
        }
    }

    private fun addLlmConfig() {
        val nextIndex = llmConfigsState.value.size + 1
        val newConfig = LlmConfig(
            id = UUID.randomUUID().toString(),
            name = getString(R.string.settings_profile_default_name, nextIndex),
            baseUrl = LlmConfig.DEFAULT_BASE_URL,
            apiKey = "",
            model = LlmConfig.DEFAULT_MODEL
        )

        llmConfigsState.value = llmConfigsState.value + newConfig
        settingsSelectedConfigIdState.value = newConfig.id
        persistLlmBundle()
        showToast(getString(R.string.settings_config_added))
    }

    private fun deleteSelectedLlmConfig() {
        val configs = llmConfigsState.value
        if (configs.size <= 1) {
            showToast(getString(R.string.settings_delete_config_blocked))
            return
        }

        val selectedId = resolveSelectedConfigId()
        val nextConfigs = configs.filterNot { it.id == selectedId }
        if (nextConfigs.isEmpty()) {
            showToast(getString(R.string.settings_delete_config_blocked))
            return
        }

        llmConfigsState.value = nextConfigs

        val nextActiveId = if (activeLlmConfigIdState.value == selectedId) {
            nextConfigs.first().id
        } else {
            activeLlmConfigIdState.value
        }
        activeLlmConfigIdState.value = nextActiveId

        settingsSelectedConfigIdState.value = if (nextConfigs.any { it.id == selectedId }) {
            selectedId
        } else {
            nextConfigs.first().id
        }

        persistLlmBundle()
        showToast(getString(R.string.settings_config_deleted))
    }

    private fun switchActiveLlmConfig(configId: String) {
        val target = llmConfigsState.value.firstOrNull { it.id == configId } ?: return
        activeLlmConfigIdState.value = target.id
        if (settingsSelectedConfigIdState.value.isBlank()) {
            settingsSelectedConfigIdState.value = target.id
        }
        LlmConfigStore.setActiveConfigId(this, target.id)
    }

    private fun saveLlmConfigs() {
        val normalizedConfigs = llmConfigsState.value.mapIndexed { index, config ->
            config.copy(
                name = config.name.trim().ifBlank {
                    getString(R.string.settings_profile_default_name, index + 1)
                },
                baseUrl = config.baseUrl.trim(),
                apiKey = config.apiKey.trim(),
                model = config.model.trim()
            )
        }

        val invalidBaseConfig = normalizedConfigs.firstOrNull { !isValidBaseUrl(it.baseUrl) }
        if (invalidBaseConfig != null) {
            showToast(getString(R.string.settings_invalid_base_url_with_name, invalidBaseConfig.name))
            return
        }

        val missingModelConfig = normalizedConfigs.firstOrNull { it.model.isBlank() }
        if (missingModelConfig != null) {
            showToast(getString(R.string.settings_missing_model_with_name, missingModelConfig.name))
            return
        }

        llmConfigsState.value = normalizedConfigs
        persistLlmBundle()
        showToast(getString(R.string.settings_saved))
    }

    private fun persistLlmBundle() {
        if (llmConfigsState.value.isEmpty()) {
            val fallbackConfig = LlmConfig(
                id = LlmConfig.DEFAULT_ID,
                name = LlmConfig.DEFAULT_NAME,
                baseUrl = LlmConfig.DEFAULT_BASE_URL,
                apiKey = "",
                model = LlmConfig.DEFAULT_MODEL
            )
            llmConfigsState.value = listOf(fallbackConfig)
        }

        val configs = llmConfigsState.value
        val activeId = configs
            .firstOrNull { it.id == activeLlmConfigIdState.value }
            ?.id
            ?: configs.first().id
        val selectedId = configs
            .firstOrNull { it.id == settingsSelectedConfigIdState.value }
            ?.id
            ?: activeId

        activeLlmConfigIdState.value = activeId
        settingsSelectedConfigIdState.value = selectedId

        LlmConfigStore.saveBundle(
            context = this,
            bundle = LlmConfigBundle(
                configs = configs,
                activeConfigId = activeId
            )
        )
    }

    private fun resolveSelectedConfigId(): String {
        val configs = llmConfigsState.value
        if (configs.isEmpty()) {
            return ""
        }

        val selected = settingsSelectedConfigIdState.value
        if (configs.any { it.id == selected }) {
            return selected
        }

        val fallback = configs.first().id
        settingsSelectedConfigIdState.value = fallback
        return fallback
    }

    private fun sendChatMessage() {
        val state = chatUiState.value
        if (state.isLoading) {
            return
        }

        val inputText = state.inputText.trim()
        val selectedImageUri = state.selectedImageUri
        if (inputText.isBlank() && selectedImageUri == null) {
            return
        }

        val config = llmConfigsState.value
            .firstOrNull { it.id == activeLlmConfigIdState.value }
            ?: llmConfigsState.value.firstOrNull()

        if (config == null) {
            val error = getString(R.string.chat_no_config)
            updateChatState { it.copy(errorMessage = error) }
            showToast(error)
            return
        }

        if (!isValidBaseUrl(config.baseUrl)) {
            updateChatState {
                it.copy(errorMessage = getString(R.string.chat_invalid_base_url))
            }
            showToast(getString(R.string.chat_invalid_base_url))
            return
        }
        if (config.apiKey.isBlank()) {
            updateChatState {
                it.copy(errorMessage = getString(R.string.chat_missing_api_key))
            }
            showToast(getString(R.string.chat_missing_api_key))
            return
        }
        if (config.model.isBlank()) {
            updateChatState {
                it.copy(errorMessage = getString(R.string.chat_missing_model))
            }
            showToast(getString(R.string.chat_missing_model))
            return
        }

        updateChatState {
            it.copy(
                isLoading = true,
                errorMessage = null
            )
        }

        lifecycleScope.launch {
            val imageDataUrl = try {
                if (selectedImageUri == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        imageUriToDataUrl(selectedImageUri)
                    }
                }
            } catch (e: Exception) {
                val errorMessage = e.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.chat_image_read_failed)
                appendAssistantError(errorMessage)
                updateChatState {
                    it.copy(
                        isLoading = false,
                        errorMessage = errorMessage
                    )
                }
                return@launch
            }

            val userMessage = ChatUiMessage(
                id = nextChatMessageId(),
                role = ChatRole.USER,
                text = inputText,
                imagePreviewUri = selectedImageUri,
                imageDataUrl = imageDataUrl
            )

            updateChatState {
                it.copy(
                    messages = it.messages + userMessage,
                    inputText = "",
                    selectedImageUri = null,
                    isLoading = true,
                    errorMessage = null
                )
            }

            val requestMessages = chatUiState.value.messages
                .filter { !it.isError }
                .map { message ->
                    OpenAiChatMessage(
                        role = message.role.toApiRole(),
                        text = message.text,
                        imageDataUrl = message.imageDataUrl
                    )
                }

            runCatching {
                OpenAiCompatClient.chat(
                    config = config,
                    messages = requestMessages
                )
            }.onSuccess { content ->
                updateChatState {
                    it.copy(
                        isLoading = false,
                        errorMessage = null,
                        messages = it.messages + ChatUiMessage(
                            id = nextChatMessageId(),
                            role = ChatRole.ASSISTANT,
                            text = content
                        )
                    )
                }
            }.onFailure { throwable ->
                val message = throwable.message ?: getString(R.string.chat_request_failed)
                appendAssistantError(message)
                updateChatState {
                    it.copy(
                        isLoading = false,
                        errorMessage = message
                    )
                }
            }
        }
    }

    private fun appendAssistantError(message: String) {
        updateChatState {
            it.copy(
                messages = it.messages + ChatUiMessage(
                    id = nextChatMessageId(),
                    role = ChatRole.ASSISTANT,
                    text = message,
                    isError = true
                )
            )
        }
    }

    private fun isValidBaseUrl(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return false
        }
        val uri = Uri.parse(trimmed)
        val scheme = uri.scheme?.lowercase()
        return !uri.host.isNullOrBlank() && (scheme == "http" || scheme == "https")
    }

    @Throws(IOException::class)
    private fun imageUriToDataUrl(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
            ?: throw IOException("image read failed")
        if (bytes.isEmpty()) {
            throw IOException("image empty")
        }
        if (bytes.size > MAX_IMAGE_BYTES) {
            throw IOException(getString(R.string.chat_image_too_large))
        }

        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mimeType;base64,$encoded"
    }

    private fun updateChatState(transform: (HomeChatState) -> HomeChatState) {
        chatUiState.value = transform(chatUiState.value)
    }

    private fun nextChatMessageId(): String {
        chatMessageId += 1
        return chatMessageId.toString()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun ChatRole.toApiRole(): String {
        return when (this) {
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "assistant"
            ChatRole.SYSTEM -> "system"
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 7001
    }
}

private enum class AppTab(
    val titleRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val showInBottomBar: Boolean
) {
    HOME(
        titleRes = R.string.tab_home,
        icon = Icons.Rounded.Home,
        showInBottomBar = true
    ),
    EXECUTION(
        titleRes = R.string.tab_execution,
        icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
        showInBottomBar = false
    ),
    CHAT(
        titleRes = R.string.tab_chat,
        icon = Icons.AutoMirrored.Rounded.Chat,
        showInBottomBar = true
    ),
    SETTINGS(
        titleRes = R.string.tab_settings,
        icon = Icons.Rounded.Settings,
        showInBottomBar = true
    )
}
