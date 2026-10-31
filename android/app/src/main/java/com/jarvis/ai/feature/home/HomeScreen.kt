package com.jarvis.ai.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.jarvis.ai.R
import com.jarvis.ai.core.agent.AssistantState
import com.jarvis.ai.ui.theme.BgPanel
import com.jarvis.ai.ui.theme.BorderSubtle
import com.jarvis.ai.ui.theme.ErrorRed
import com.jarvis.ai.ui.theme.SuccessGreen
import com.jarvis.ai.ui.theme.TextDim
import com.jarvis.ai.ui.theme.colorFor

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }

    val stateColor by animateColorAsState(
        targetValue = colorFor(ui.state),
        animationSpec = tween(durationMillis = 420),
        label = "stateColor",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- header -------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "J.A.R.V.I.S",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.action_settings))
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (ui.state == AssistantState.DISCONNECTED) ErrorRed else SuccessGreen,
                    ),
            )
        }

        // --- visualiser ---------------------------------------------------
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = BgPanel),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                VoiceVisualizer(
                    state = ui.state,
                    amplitude = ui.amplitude,
                    reducedMotion = settings.reducedMotion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                )
                Text(
                    text = ui.activity ?: stringResource(ui.state.statusRes),
                    color = stateColor,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                if (ui.tokensThisSession > 0) {
                    Text(
                        text = "رموز هذه الجلسة: ${ui.tokensThisSession}",
                        color = TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        ui.lastError?.let { error ->
            Text(text = error, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
        }

        if (!ui.hasApiKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "لم يُضبَط مفتاح OpenRouter — الأوامر المباشرة تعمل، والأسئلة تحتاج المفتاح.",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSettings) { Text("إضافة") }
            }
        }

        if (!ui.secureStorageAvailable) {
            Text(
                text = "تنبيه: التخزين المشفّر غير متاح على هذا الجهاز، لن تُحفَظ المفاتيح.",
                color = ErrorRed,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // --- response -----------------------------------------------------
        Text(
            text = ui.transcript,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // --- input --------------------------------------------------------
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(stringResource(R.string.hint_input), color = TextDim) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    viewModel.submit(input)
                    input = ""
                },
                enabled = input.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_send))
            }

            OutlinedButton(
                onClick = { viewModel.toggleMic(!ui.micOn) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (ui.micOn) R.string.action_stop_listening else R.string.action_talk,
                    ),
                )
            }

            if (ui.canCancel) {
                OutlinedButton(onClick = viewModel::cancel) {
                    Text(stringResource(R.string.action_cancel), color = ErrorRed)
                }
            }
        }
    }

    // --- confirmation for consequential actions (spec s11) ----------------
    ui.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::rejectPending,
            title = { Text("تأكيد الإجراء") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("سيُنفَّذ الإجراء التالي:")
                    Text(pending.description, color = TextDim)
                    Text("الأداة: ${pending.toolName}", color = TextDim)
                    if (pending.args.isNotEmpty()) {
                        Text("الوسائط: ${pending.args}", color = TextDim)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPending) { Text("تنفيذ") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::rejectPending) { Text("إلغاء") }
            },
            containerColor = BgPanel,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = MaterialTheme.colorScheme.onBackground,
            iconContentColor = BorderSubtle,
        )
    }
}
