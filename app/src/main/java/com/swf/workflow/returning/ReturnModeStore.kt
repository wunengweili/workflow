package com.swf.workflow.returning

import android.content.Context

enum class ReturnMode {
    AUTO,
    ACCESSIBILITY_ONLY;

    companion object {
        fun fromRaw(raw: String?): ReturnMode {
            return entries.firstOrNull { it.name == raw } ?: AUTO
        }
    }
}

object ReturnModeStore {

    private const val PREF_NAME = "wallet_return_pref"
    private const val KEY_RETURN_MODE = "key_return_mode"

    fun get(context: Context): ReturnMode {
        val raw = prefs(context).getString(KEY_RETURN_MODE, ReturnMode.AUTO.name)
        return ReturnMode.fromRaw(raw)
    }

    fun set(context: Context, mode: ReturnMode) {
        prefs(context)
            .edit()
            .putString(KEY_RETURN_MODE, mode.name)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
