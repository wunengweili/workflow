package com.swf.workflow.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OpenAiChatMessage(
    val role: String,
    val text: String,
    val imageDataUrl: String? = null
)

object OpenAiCompatClient {

    private const val REQUEST_TIMEOUT_SECONDS = 5L * 60L

    private enum class ThinkingControlStrategy {
        ENABLE_THINKING_ONLY,
        REASONING_ONLY,
        BOTH
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun chat(
        config: LlmConfig,
        messages: List<OpenAiChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val requestUrl = buildChatCompletionsUrl(config.baseUrl)
        if (config.enableThinking) {
            return@withContext requestWithThinkingOn(
                requestUrl = requestUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = messages
            )
        }
        return@withContext requestWithThinkingOff(
            requestUrl = requestUrl,
            apiKey = config.apiKey,
            model = config.model,
            messages = messages
        )
    }

    private fun buildChatCompletionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.endsWith("/chat/completions")) {
            return normalized
        }
        if (normalized.endsWith("/v1")) {
            return "$normalized/chat/completions"
        }
        return "$normalized/v1/chat/completions"
    }

    private fun buildRequestPayload(
        model: String,
        messages: List<OpenAiChatMessage>,
        enableThinking: Boolean,
        strategy: ThinkingControlStrategy
    ): String {
        val requestJson = buildJsonObject {
            put("model", model)
            put("stream", false)
            when (strategy) {
                ThinkingControlStrategy.ENABLE_THINKING_ONLY -> {
                    put("enable_thinking", enableThinking)
                }

                ThinkingControlStrategy.REASONING_ONLY -> {
                    put("reasoning_effort", if (enableThinking) "low" else "none")
                    put(
                        "reasoning",
                        buildJsonObject {
                            put("effort", if (enableThinking) "low" else "none")
                        }
                    )
                }

                ThinkingControlStrategy.BOTH -> {
                    put("enable_thinking", enableThinking)
                    put("reasoning_effort", if (enableThinking) "low" else "none")
                    put(
                        "reasoning",
                        buildJsonObject {
                            put("effort", if (enableThinking) "low" else "none")
                        }
                    )
                }
            }
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { message ->
                        add(
                            buildJsonObject {
                                put("role", message.role)
                                if (!message.imageDataUrl.isNullOrBlank()) {
                                    put(
                                        "content",
                                        buildJsonArray {
                                            if (message.text.isNotBlank()) {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "text")
                                                        put("text", message.text)
                                                    }
                                                )
                                            }
                                            add(
                                                buildJsonObject {
                                                    put("type", "image_url")
                                                    put(
                                                        "image_url",
                                                        buildJsonObject {
                                                            put("url", message.imageDataUrl)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    )
                                } else {
                                    put("content", message.text)
                                }
                            }
                        )
                    }
                }
            )
        }
        return requestJson.toString()
    }

    private fun requestWithThinkingOn(
        requestUrl: String,
        apiKey: String,
        model: String,
        messages: List<OpenAiChatMessage>
    ): String {
        return runCatching {
            requestChatCompletions(
                requestUrl = requestUrl,
                apiKey = apiKey,
                payload = buildRequestPayload(
                    model = model,
                    messages = messages,
                    enableThinking = true,
                    strategy = ThinkingControlStrategy.BOTH
                )
            )
        }.recoverCatching { throwable ->
            if (!isThinkingControlUnsupported(throwable)) {
                throw throwable
            }
            requestChatCompletions(
                requestUrl = requestUrl,
                apiKey = apiKey,
                payload = buildRequestPayload(
                    model = model,
                    messages = messages,
                    enableThinking = true,
                    strategy = ThinkingControlStrategy.ENABLE_THINKING_ONLY
                )
            )
        }.recoverCatching { throwable ->
            if (!isThinkingControlUnsupported(throwable)) {
                throw throwable
            }
            requestChatCompletions(
                requestUrl = requestUrl,
                apiKey = apiKey,
                payload = buildRequestPayload(
                    model = model,
                    messages = messages,
                    enableThinking = true,
                    strategy = ThinkingControlStrategy.REASONING_ONLY
                )
            )
        }.getOrThrow()
    }

    private fun requestWithThinkingOff(
        requestUrl: String,
        apiKey: String,
        model: String,
        messages: List<OpenAiChatMessage>
    ): String {
        var lastUnsupported: Throwable? = null

        val strategies = listOf(
            ThinkingControlStrategy.BOTH,
            ThinkingControlStrategy.ENABLE_THINKING_ONLY,
            ThinkingControlStrategy.REASONING_ONLY
        )

        strategies.forEach { strategy ->
            val result = runCatching {
                requestChatCompletions(
                    requestUrl = requestUrl,
                    apiKey = apiKey,
                    payload = buildRequestPayload(
                        model = model,
                        messages = messages,
                        enableThinking = false,
                        strategy = strategy
                    )
                )
            }
            if (result.isSuccess) {
                return result.getOrThrow()
            }
            val throwable = result.exceptionOrNull()
            if (throwable != null && isThinkingControlUnsupported(throwable)) {
                lastUnsupported = throwable
                return@forEach
            }
            throw result.exceptionOrNull() ?: IOException("模型请求失败")
        }

        throw IOException(
            "当前服务端不支持关闭 Thinking；请改用非 reasoning 模型，或配置服务端参数映射。"
        ).apply {
            lastUnsupported?.let { initCause(it) }
        }
    }

    private fun requestChatCompletions(
        requestUrl: String,
        apiKey: String,
        payload: String
    ): String {
        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "请求失败 (${response.code}): ${extractServiceError(rawBody)}"
                )
            }

            val root = runCatching { json.parseToJsonElement(rawBody).jsonObject }
                .getOrElse { throw IOException("响应解析失败") }
            val choices = root["choices"]?.jsonArray
                ?: throw IOException("模型响应缺少 choices")
            if (choices.isEmpty()) {
                throw IOException("模型响应 choices 为空")
            }

            val firstMessage = choices.first().jsonObject["message"]?.jsonObject
                ?: throw IOException("模型响应缺少 message")
            val content = extractContent(firstMessage["content"])
            if (content.isBlank()) {
                throw IOException("模型返回内容为空")
            }
            return content
        }
    }

    private fun extractContent(contentElement: JsonElement?): String {
        return when (contentElement) {
            is JsonPrimitive -> contentElement.contentOrNull.orEmpty()
            is JsonArray -> contentElement.mapNotNull { extractContentTextPart(it) }
                .joinToString(separator = "\n")
                .trim()
            else -> ""
        }
    }

    private fun extractContentTextPart(element: JsonElement): String? {
        if (element !is JsonObject) {
            return null
        }
        val type = element["type"]?.jsonPrimitive?.contentOrNull
        if (type == null || type == "text") {
            return element["text"]?.jsonPrimitive?.contentOrNull
        }
        if (type == "output_text") {
            return element["text"]?.jsonPrimitive?.contentOrNull
        }
        return null
    }

    private fun extractServiceError(body: String): String {
        if (body.isBlank()) {
            return "空响应"
        }
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: body.take(160)
        }.getOrElse { body.take(160) }
    }

    private fun isThinkingControlUnsupported(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase().orEmpty()
        val mentionsThinkingFlag = message.contains("enable_thinking") ||
            message.contains("reasoning_effort") ||
            message.contains("reasoning") ||
            message.contains("effort")
        val unsupportedFlag = message.contains("unknown") ||
            message.contains("unrecognized") ||
            message.contains("unsupported") ||
            message.contains("invalid") ||
            message.contains("not permitted") ||
            message.contains("unexpected") ||
            message.contains("不支持") ||
            message.contains("无效") ||
            message.contains("未知")
        return mentionsThinkingFlag && unsupportedFlag
    }
}
