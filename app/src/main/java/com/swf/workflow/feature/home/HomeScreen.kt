package com.swf.workflow.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.swf.workflow.R
import com.swf.workflow.ui.theme.WorkflowTheme

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    onStartAutomation: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HeaderCard()
            }

            item {
                StatusCard(
                    accessibilityEnabled = uiState.accessibilityEnabled,
                    usageAccessEnabled = uiState.usageAccessEnabled,
                    shizukuReady = uiState.shizukuReady,
                    shizukuStatusLabel = uiState.shizukuStatusLabel,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenUsageAccessSettings = onOpenUsageAccessSettings
                )
            }

            item {
                WalletActionCard(
                    onStartAutomation = onStartAutomation
                )
            }
        }
    }
}

@Composable
private fun HeaderCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(id = R.string.home_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1D2433)
            )
            Text(
                text = stringResource(id = R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF59627A)
            )
        }
    }
}

@Composable
private fun StatusCard(
    accessibilityEnabled: Boolean,
    usageAccessEnabled: Boolean,
    shizukuReady: Boolean,
    shizukuStatusLabel: String,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit
) {
    val allPermissionsGranted = accessibilityEnabled && usageAccessEnabled
    var expanded by rememberSaveable { mutableStateOf(!allPermissionsGranted) }
    LaunchedEffect(allPermissionsGranted) {
        expanded = !allPermissionsGranted
    }

    val iconTint = if (accessibilityEnabled) Color(0xFF0B8A56) else Color(0xFFBC5D07)
    val badgeColor = if (accessibilityEnabled) Color(0xFFE4F8EE) else Color(0xFFFFF0E1)
    val statusLabel = if (accessibilityEnabled) {
        stringResource(id = R.string.a11y_enabled)
    } else {
        stringResource(id = R.string.a11y_disabled)
    }
    val usageIconTint = if (usageAccessEnabled) Color(0xFF0B8A56) else Color(0xFFBC5D07)
    val usageBadgeColor = if (usageAccessEnabled) Color(0xFFE4F8EE) else Color(0xFFFFF0E1)
    val usageStatusLabel = if (usageAccessEnabled) {
        stringResource(id = R.string.usage_access_enabled)
    } else {
        stringResource(id = R.string.usage_access_disabled)
    }
    val shizukuIconTint = if (shizukuReady) Color(0xFF0B8A56) else Color(0xFFBC5D07)
    val shizukuBadgeColor = if (shizukuReady) Color(0xFFE4F8EE) else Color(0xFFFFF0E1)
    val shizukuStatusText = stringResource(
        id = R.string.shizuku_status_format,
        shizukuStatusLabel
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (allPermissionsGranted) Color(0xFFE4F8EE) else Color(0xFFFFF0E1)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (allPermissionsGranted) {
                                Icons.Rounded.Verified
                            } else {
                                Icons.Rounded.WarningAmber
                            },
                            contentDescription = null,
                            tint = if (allPermissionsGranted) Color(0xFF0B8A56) else Color(0xFFBC5D07)
                        )
                        Text(
                            text = if (allPermissionsGranted) "所有权限已授权" else "部分权限未授权",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (allPermissionsGranted) Color(0xFF0B8A56) else Color(0xFFBC5D07),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(text = if (expanded) "收起" else "展开")
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null
                    )
                }
            }

            if (expanded) {
                StatusSection(
                    title = stringResource(id = R.string.a11y_card_title),
                    subtitle = stringResource(id = R.string.a11y_card_subtitle),
                    statusText = statusLabel,
                    enabled = accessibilityEnabled,
                    badgeColor = badgeColor,
                    iconTint = iconTint,
                    buttonText = stringResource(id = R.string.a11y_action_open_settings),
                    buttonContainerColor = Color(0xFF1F2A44),
                    onClickAction = onOpenAccessibilitySettings
                )

                StatusSection(
                    title = stringResource(id = R.string.usage_access_card_title),
                    subtitle = stringResource(id = R.string.usage_access_card_subtitle),
                    statusText = usageStatusLabel,
                    enabled = usageAccessEnabled,
                    badgeColor = usageBadgeColor,
                    iconTint = usageIconTint,
                    buttonText = stringResource(id = R.string.usage_access_action_open_settings),
                    buttonContainerColor = Color(0xFF394867),
                    onClickAction = onOpenUsageAccessSettings
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(id = R.string.shizuku_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF1D2433)
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = shizukuBadgeColor
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (shizukuReady) {
                                    Icons.Rounded.Verified
                                } else {
                                    Icons.Rounded.WarningAmber
                                },
                                contentDescription = null,
                                tint = shizukuIconTint
                            )
                            Text(
                                text = shizukuStatusText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = shizukuIconTint,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSection(
    title: String,
    subtitle: String,
    statusText: String,
    enabled: Boolean,
    badgeColor: Color,
    iconTint: Color,
    buttonText: String,
    buttonContainerColor: Color,
    onClickAction: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF1D2433)
        )

        if (!enabled) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF59627A)
            )
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = badgeColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (enabled) {
                        Icons.Rounded.Verified
                    } else {
                        Icons.Rounded.WarningAmber
                    },
                    contentDescription = null,
                    tint = iconTint
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = iconTint,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (!enabled) {
            Button(
                onClick = onClickAction,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainerColor
                )
            ) {
                Text(text = buttonText)
            }
        }
    }
}

@Composable
private fun WalletActionCard(onStartAutomation: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFFFF3E8))
                        .padding(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFF97316)
                    )
                }
                Column {
                    Text(
                        text = stringResource(id = R.string.wallet_action_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1D2433)
                    )
                    Text(
                        text = stringResource(id = R.string.wallet_action_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF59627A)
                    )
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                onClick = onStartAutomation,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF97316)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null
                    )
                    Text(text = stringResource(id = R.string.wallet_auto_action_title))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    WorkflowTheme {
        HomeScreen(
            uiState = HomeUiState(
                accessibilityEnabled = false,
                usageAccessEnabled = false,
                shizukuReady = false,
                shizukuStatusLabel = "未安装"
            ),
            onOpenAccessibilitySettings = {},
            onOpenUsageAccessSettings = {},
            onStartAutomation = {}
        )
    }
}
