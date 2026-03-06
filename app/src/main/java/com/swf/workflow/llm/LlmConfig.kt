package com.swf.workflow.llm

data class LlmConfig(
    val id: String = DEFAULT_ID,
    val name: String = DEFAULT_NAME,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val enableThinking: Boolean = false
) {
    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "默认配置"
        const val DEFAULT_BASE_URL = "https://api.openai.com"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}
