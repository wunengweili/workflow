package com.swf.workflow.feature.execution

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swf.workflow.R
import com.swf.workflow.accessibility.AutomationRuntimeState

@Composable
fun ExecutionScreen(
    runtimeState: AutomationRuntimeState,
    onStopWorkflow: () -> Unit,
    onClearLogs: () -> Unit,
    onBackToHome: () -> Unit,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                runtimeState = runtimeState,
                onStopWorkflow = onStopWorkflow,
                onClearLogs = onClearLogs,
                onBackToHome = onBackToHome
            )
            LogCard(runtimeState = runtimeState)
        }
    }
}

@Composable
private fun StatusCard(
    runtimeState: AutomationRuntimeState,
    onStopWorkflow: () -> Unit,
    onClearLogs: () -> Unit,
    onBackToHome: () -> Unit
) {
    val runningColor = if (runtimeState.isRunning) Color(0xFF0B8A56) else Color(0xFF667085)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(id = R.string.execution_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF1D2433),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(id = R.string.execution_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF59627A)
            )
            Text(
                text = if (runtimeState.isRunning) {
                    stringResource(id = R.string.execution_running)
                } else {
                    stringResource(id = R.string.execution_idle)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = runningColor,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(id = R.string.execution_current_step, runtimeState.currentStep),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF344054)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    onClick = onStopWorkflow,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626)
                    ),
                    enabled = runtimeState.isRunning
                ) {
                    Text(text = stringResource(id = R.string.execution_stop_button))
                }

                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    onClick = onClearLogs,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6B7280)
                    ),
                    enabled = runtimeState.logs.isNotEmpty()
                ) {
                    Text(text = stringResource(id = R.string.execution_clear_logs_button))
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                onClick = onBackToHome,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1D4ED8)
                )
            ) {
                Text(text = stringResource(id = R.string.execution_back_home_button))
            }
        }
    }
}

@Composable
private fun LogCard(runtimeState: AutomationRuntimeState) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        if (runtimeState.logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(id = R.string.execution_no_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF98A2B3)
                )
            }
        } else {
            val items = runtimeState.logs.asReversed()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items) { logLine ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = logLine.time,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF667085)
                        )
                        Text(
                            text = logLine.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF1F2937)
                        )
                    }
                }
            }
        }
    }
}
