package com.swf.workflow.llm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LlmConfigBundle(
    val configs: List<LlmConfig>,
    val activeConfigId: String
)

object LlmConfigStore {

    private const val PREF_NAME = "llm_config_pref"
    private const val KEY_CONFIGS_JSON = "key_configs_json"
    private const val KEY_ACTIVE_CONFIG_ID = "key_active_config_id"

    // Legacy keys kept for one-time migration from single-config mode.
    private const val KEY_BASE_URL = "key_base_url"
    private const val KEY_API_KEY = "key_api_key"
    private const val KEY_MODEL = "key_model"
    private const val KEY_ENABLE_THINKING = "key_enable_thinking"

    fun getBundle(context: Context): LlmConfigBundle {
        val sharedPreferences = prefs(context)
        val storedConfigs = readConfigs(sharedPreferences)
        val requestedActiveId = sharedPreferences.getString(KEY_ACTIVE_CONFIG_ID, null)
        return normalizeBundle(
            configs = storedConfigs,
            requestedActiveId = requestedActiveId
        )
    }

    fun saveBundle(context: Context, bundle: LlmConfigBundle) {
        val normalized = normalizeBundle(
            configs = bundle.configs,
            requestedActiveId = bundle.activeConfigId
        )

        prefs(context)
            .edit()
            .putString(KEY_CONFIGS_JSON, encodeConfigs(normalized.configs))
            .putString(KEY_ACTIVE_CONFIG_ID, normalized.activeConfigId)
            .remove(KEY_BASE_URL)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL)
            .remove(KEY_ENABLE_THINKING)
            .apply()
    }

    fun setActiveConfigId(context: Context, configId: String) {
        val bundle = getBundle(context)
        val activeId = bundle.configs
            .firstOrNull { it.id == configId }
            ?.id
            ?: bundle.activeConfigId

        prefs(context)
            .edit()
            .putString(KEY_ACTIVE_CONFIG_ID, activeId)
            .apply()
    }

    private fun readConfigs(sharedPreferences: SharedPreferences): List<LlmConfig> {
        val raw = sharedPreferences.getString(KEY_CONFIGS_JSON, null)
        if (!raw.isNullOrBlank()) {
            return parseConfigs(raw)
        }
        return readLegacyConfig(sharedPreferences)
    }

    private fun readLegacyConfig(sharedPreferences: SharedPreferences): List<LlmConfig> {
        val baseUrl = sharedPreferences
            .getString(KEY_BASE_URL, LlmConfig.DEFAULT_BASE_URL)
            .orEmpty()
            .trim()
        val apiKey = sharedPreferences
            .getString(KEY_API_KEY, "")
            .orEmpty()
            .trim()
        val model = sharedPreferences
            .getString(KEY_MODEL, LlmConfig.DEFAULT_MODEL)
            .orEmpty()
            .trim()
        val enableThinking = sharedPreferences.getBoolean(KEY_ENABLE_THINKING, false)

        return listOf(
            LlmConfig(
                id = LlmConfig.DEFAULT_ID,
                name = LlmConfig.DEFAULT_NAME,
                baseUrl = if (baseUrl.isBlank()) LlmConfig.DEFAULT_BASE_URL else baseUrl,
                apiKey = apiKey,
                model = if (model.isBlank()) LlmConfig.DEFAULT_MODEL else model,
                enableThinking = enableThinking
            )
        )
    }

    private fun parseConfigs(raw: String): List<LlmConfig> {
        return runCatching {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    add(
                        LlmConfig(
                            id = item.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name", "").ifBlank { "配置${index + 1}" },
                            baseUrl = item.optString("baseUrl", LlmConfig.DEFAULT_BASE_URL),
                            apiKey = item.optString("apiKey", ""),
                            model = item.optString("model", LlmConfig.DEFAULT_MODEL),
                            enableThinking = item.optBoolean("enableThinking", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeConfigs(configs: List<LlmConfig>): String {
        val jsonArray = JSONArray()
        configs.forEach { config ->
            jsonArray.put(
                JSONObject()
                    .put("id", config.id)
                    .put("name", config.name)
                    .put("baseUrl", config.baseUrl)
                    .put("apiKey", config.apiKey)
                    .put("model", config.model)
                    .put("enableThinking", config.enableThinking)
            )
        }
        return jsonArray.toString()
    }

    private fun normalizeBundle(
        configs: List<LlmConfig>,
        requestedActiveId: String?
    ): LlmConfigBundle {
        val normalizedConfigs = normalizeConfigs(configs)
        val activeConfigId = normalizedConfigs
            .firstOrNull { it.id == requestedActiveId }
            ?.id
            ?: normalizedConfigs.first().id

        return LlmConfigBundle(
            configs = normalizedConfigs,
            activeConfigId = activeConfigId
        )
    }

    private fun normalizeConfigs(configs: List<LlmConfig>): List<LlmConfig> {
        if (configs.isEmpty()) {
            return listOf(
                LlmConfig(
                    id = LlmConfig.DEFAULT_ID,
                    name = LlmConfig.DEFAULT_NAME,
                    baseUrl = LlmConfig.DEFAULT_BASE_URL,
                    model = LlmConfig.DEFAULT_MODEL
                )
            )
        }

        val idSet = mutableSetOf<String>()
        val normalized = mutableListOf<LlmConfig>()

        configs.forEachIndexed { index, config ->
            var candidateId = config.id.trim()
            if (candidateId.isBlank() || !idSet.add(candidateId)) {
                do {
                    candidateId = UUID.randomUUID().toString()
                } while (!idSet.add(candidateId))
            }

            normalized += config.copy(
                id = candidateId,
                name = config.name.trim().ifBlank { "配置${index + 1}" },
                baseUrl = config.baseUrl.trim().ifBlank { LlmConfig.DEFAULT_BASE_URL },
                apiKey = config.apiKey.trim(),
                model = config.model.trim().ifBlank { LlmConfig.DEFAULT_MODEL },
                enableThinking = config.enableThinking
            )
        }

        return normalized
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
