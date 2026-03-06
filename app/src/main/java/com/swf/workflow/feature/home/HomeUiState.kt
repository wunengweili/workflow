package com.swf.workflow.feature.home

data class HomeUiState(
    val accessibilityEnabled: Boolean,
    val usageAccessEnabled: Boolean,
    val shizukuReady: Boolean,
    val shizukuStatusLabel: String
)
