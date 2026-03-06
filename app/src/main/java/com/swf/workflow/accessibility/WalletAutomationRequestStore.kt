package com.swf.workflow.accessibility

import android.content.Context

object WalletAutomationRequestStore {

    private const val PREF_NAME = "wallet_automation_pref"
    private const val KEY_PENDING_MEMBERSHIP_FLOW = "key_pending_membership_flow"

    fun markPending(context: Context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_PENDING_MEMBERSHIP_FLOW, true)
            .apply()
    }

    fun clearPending(context: Context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_PENDING_MEMBERSHIP_FLOW, false)
            .apply()
    }

    fun consumePending(context: Context): Boolean {
        val sharedPreferences = prefs(context)
        val pending = sharedPreferences.getBoolean(KEY_PENDING_MEMBERSHIP_FLOW, false)
        if (pending) {
            sharedPreferences
                .edit()
                .putBoolean(KEY_PENDING_MEMBERSHIP_FLOW, false)
                .apply()
        }
        return pending
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
