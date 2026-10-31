package com.jarvis.ai.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.jarvis.ai.R
import com.jarvis.ai.ui.theme.BgPanel
import com.jarvis.ai.ui.theme.ErrorRed
import com.jarvis.ai.ui.theme.SuccessGreen
import com.jarvis.ai.ui.theme.TextDim
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.settings.collectAsState()

    LaunchedEffect(settings.voiceProvider) { viewModel.loadVoices() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringRes(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("رجوع") }
        }

        ui.notice?.let { notice ->
            Section(title = "تنبيه") {
                Text(notice, color = TextDim, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::dismissNotice) { Text("حسنًا") }
            }
        }

        if (!ui.secureStorageAvailable) {
            Section(title = "التخزين المشفّر") {
                Text(
                    "غير متاح على هذا الجهاز. لن يُحفَظ المفتاح ولا رمز الجهاز بين الجلسات.",
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ------------------------------------------------- AI provider
        Section(title = stringRes(R.string.settings_provider)) {
            var keyInput by rememberSaveable { mutableStateOf("") }

            Text(
                stringRes(R.string.settings_api_key_help),
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (ui.hasApiKey) {
                Text("مفتاح محفوظ ✓", color = SuccessGreen, style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(stringRes(R.string.settings_api_key)) },
                placeholder = { Text("sk-or-...", color = TextDim) },
                // The key is never rendered back in clear text.
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.saveApiKey(keyInput)
                        keyInput = ""
                    },
                    enabled = keyInput.isNotBlank(),
                ) { Text("حفظ") }

                OutlinedButton(onClick = viewModel::testConnection, enabled = ui.hasApiKey && !ui.testing) {
                    if (ui.testing) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringRes(R.string.settings_test_connection))
                    }
                }

                if (ui.hasApiKey) {
                    TextButton(onClick = viewModel::clearApiKey) { Text("حذف", color = ErrorRed) }
                }
            }

            ui.testResult?.let { result ->
                Text(
                    result,
                    color = if (ui.testOk == true) SuccessGreen else ErrorRed,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ------------------------------------------------------- model
        Section(title = stringRes(R.string.settings_model)) {
            Text(settings.model, style = MaterialTheme.typography.bodyMedium)
            Text(
                "استدعاء الأدوات يحتاج نموذجًا يدعمه. اضغط لتحميل النماذج التي تدعمه فعلًا.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::loadModels,
                    enabled = ui.hasApiKey && !ui.loadingModels,
                ) {
                    Text(if (ui.loadingModels) "جارٍ التحميل..." else "النماذج المدعومة")
                }
            }

            val choices = ui.toolCapableModels.map { it.id }.ifEmpty { viewModel.suggestedModels }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (id in choices.take(12)) {
                    FilterChip(
                        selected = settings.model == id,
                        onClick = { viewModel.setModel(id) },
                        label = { Text(id, style = MaterialTheme.typography.bodyMedium) },
                    )
                }
            }
            if (ui.toolCapableModels.isEmpty()) {
                Text(
                    "القائمة أعلاه مقترحات مجانية لم تُتحقَّق من هذا الجهاز بعد.",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ------------------------------------------------------- voice
        Section(title = stringRes(R.string.settings_voice)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.voiceProvider == "relay",
                    onClick = { viewModel.setVoiceProvider("relay") },
                    label = { Text("صوت إسماعيل (سحابي)") },
                )
                FilterChip(
                    selected = settings.voiceProvider == "system",
                    onClick = { viewModel.setVoiceProvider("system") },
                    label = { Text("محرّك الهاتف") },
                )
            }

            Text(
                if (settings.voiceProvider == "relay") {
                    "يحتاج إقران الجهاز بالخادم. إن تعذّر، ينطق محرّك الهاتف تلقائيًا."
                } else {
                    "يعمل بلا إنترنت، وجودته تتبع الأصوات العربية المثبّتة على هاتفك."
                },
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (ui.voices.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in ui.voices.take(12)) {
                        FilterChip(
                            selected = settings.voice == option.id,
                            onClick = { viewModel.setVoice(option.id) },
                            label = {
                                Text(
                                    listOf(option.name, option.localeName).filter { it.isNotBlank() }
                                        .joinToString(" — "),
                                )
                            },
                        )
                    }
                }
            }

            SliderRow(
                label = stringRes(R.string.settings_pitch),
                value = settings.voicePitch.removeSuffix("Hz").toFloatOrNull() ?: -15f,
                range = -50f..50f,
                suffix = "Hz",
            ) { viewModel.setPitch("${it.roundToInt()}Hz") }

            SliderRow(
                label = stringRes(R.string.settings_speech_rate),
                value = settings.voiceRate.removeSuffix("%").toFloatOrNull() ?: -8f,
                range = -50f..100f,
                suffix = "%",
            ) { viewModel.setRate("${it.roundToInt()}%") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::previewVoice) {
                    Text(if (ui.speaking) "إيقاف" else "سماع عيّنة")
                }
                AssistChip(
                    onClick = {},
                    label = { Text("الأنماط تُطبَّق بالنبرة فقط", style = MaterialTheme.typography.bodyMedium) },
                )
            }

            ToggleRow(
                label = stringRes(R.string.settings_auto_speak),
                checked = settings.autoSpeak,
                onChange = viewModel::setAutoSpeak,
            )
        }

        // -------------------------------------------------- automation
        Section(title = stringRes(R.string.settings_automation)) {
            ToggleRow(
                label = stringRes(R.string.settings_confirm_sensitive),
                checked = settings.confirmSensitiveActions,
                onChange = viewModel::setConfirmSensitive,
            )
            Text(
                "عند التشغيل، لا يُنفَّذ أي إجراء حسّاس قبل موافقتك الصريحة.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            SliderRow(
                label = "أقصى عدد لاستدعاءات الأدوات",
                value = settings.maxToolCalls.toFloat(),
                range = 1f..20f,
                suffix = "",
            ) { viewModel.setMaxToolCalls(it.roundToInt()) }

            SliderRow(
                label = "أقصى عدد لرموز الإخراج",
                value = settings.maxOutputTokens.toFloat(),
                range = 128f..4096f,
                suffix = "",
            ) { viewModel.setMaxOutputTokens(it.roundToInt()) }
        }

        // -------------------------------------------------- appearance
        Section(title = stringRes(R.string.settings_appearance)) {
            ToggleRow(
                label = stringRes(R.string.settings_reduced_motion),
                checked = settings.reducedMotion,
                onChange = viewModel::setReducedMotion,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgPanel),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text("${local.roundToInt()}$suffix", color = TextDim)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
        )
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
