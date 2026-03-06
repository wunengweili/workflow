package com.swf.workflow.feature.chat

import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.swf.workflow.R
import com.swf.workflow.feature.home.ChatRole
import com.swf.workflow.feature.home.ChatUiMessage
import com.swf.workflow.feature.home.HomeChatState
import com.swf.workflow.llm.LlmConfig
import com.swf.workflow.ui.theme.WorkflowTheme

@Composable
fun ChatScreen(
    chatState: HomeChatState,
    configs: List<LlmConfig>,
    activeConfigId: String,
    onSwitchConfig: (String) -> Unit,
    onChatInputChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onClearSelectedImage: () -> Unit,
    onSendChatMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeConfig = configs.firstOrNull { it.id == activeConfigId }
        ?: configs.firstOrNull()
    var configMenuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF4F6FB),
                        Color(0xFFE7ECF6)
                    )
                )
            )
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Chat,
                        contentDescription = null,
                        tint = Color(0xFF3D5AFE)
                    )
                    Text(
                        text = stringResource(id = R.string.chat_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1D2433),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { configMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    id = R.string.chat_using_config,
                                    activeConfig?.name ?: stringResource(id = R.string.chat_no_config)
                                )
                            )
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = configMenuExpanded,
                        onDismissRequest = { configMenuExpanded = false }
                    ) {
                        configs.forEach { config ->
                            DropdownMenuItem(
                                text = { Text(text = config.name) },
                                onClick = {
                                    configMenuExpanded = false
                                    onSwitchConfig(config.id)
                                }
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF8FAFF)
                ) {
                    if (chatState.messages.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(id = R.string.chat_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF98A2B3)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = chatState.messages,
                                key = { it.id }
                            ) { message ->
                                ChatMessageBubble(message = message)
                            }
                        }
                    }
                }

                if (chatState.selectedImageUri != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF2F4F7)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = Color(0xFF667085)
                                )
                                Text(
                                    text = stringResource(id = R.string.chat_image_selected),
                                    color = Color(0xFF344054),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = onClearSelectedImage) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = Color(0xFF667085)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = chatState.inputText,
                    onValueChange = onChatInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(id = R.string.chat_input_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onPickImage,
                        shape = RoundedCornerShape(14.dp),
                        enabled = !chatState.isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = null
                        )
                        Text(
                            text = stringResource(id = R.string.chat_pick_image),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onSendChatMessage,
                        shape = RoundedCornerShape(14.dp),
                        enabled = !chatState.isLoading && (
                            chatState.inputText.isNotBlank() || chatState.selectedImageUri != null
                        )
                    ) {
                        if (chatState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null
                            )
                            Text(
                                text = stringResource(id = R.string.chat_send),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                if (!chatState.errorMessage.isNullOrBlank()) {
                    Text(
                        text = chatState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB42318)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(message: ChatUiMessage) {
    val isUser = message.role == ChatRole.USER
    val bubbleColor = when {
        message.isError -> Color(0xFFFEE4E2)
        isUser -> Color(0xFFE8F0FF)
        else -> Color(0xFFEEF4FF)
    }
    val textColor = when {
        message.isError -> Color(0xFFB42318)
        else -> Color(0xFF1F2937)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(14.dp),
            color = bubbleColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.imagePreviewUri != null) {
                    ChatImagePreview(uri = message.imagePreviewUri)
                }
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatImagePreview(uri: Uri) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(10.dp)),
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        update = { imageView ->
            imageView.setImageURI(uri)
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview() {
    WorkflowTheme {
        ChatScreen(
            chatState = HomeChatState(
                messages = listOf(
                    ChatUiMessage(
                        id = "u-1",
                        role = ChatRole.USER,
                        text = "帮我总结这张图的内容"
                    ),
                    ChatUiMessage(
                        id = "a-1",
                        role = ChatRole.ASSISTANT,
                        text = "可以，请发送图片后我会做结构化总结。"
                    )
                )
            ),
            configs = listOf(
                LlmConfig(id = "1", name = "默认配置")
            ),
            activeConfigId = "1",
            onSwitchConfig = {},
            onChatInputChange = {},
            onPickImage = {},
            onClearSelectedImage = {},
            onSendChatMessage = {}
        )
    }
}
