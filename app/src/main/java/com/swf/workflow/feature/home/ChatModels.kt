package com.swf.workflow.feature.home

import android.net.Uri

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatUiMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val imagePreviewUri: Uri? = null,
    val imageDataUrl: String? = null,
    val isError: Boolean = false
)

data class HomeChatState(
    val messages: List<ChatUiMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val selectedImageUri: Uri? = null,
    val errorMessage: String? = null
)
