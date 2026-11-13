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
import com.jarvis.ai.core.net.ModelProvider
import com.jarvis.ai.R
import com.jarvis.ai.core.agent.JarvisAccessibilityService
import com.jarvis.ai.ui.theme.BgPanel
import com.jarvis.ai.ui.theme.ErrorRed
import com.jarvis.ai.ui.theme.SuccessGreen
import com.jarvis.ai.ui.theme.TextDim
import com.jarvis.ai.ui.theme.WarnAmber
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.settings.collectAsState()

    LaunchedEffect(settings.voiceProvider) { viewModel.loadVoices() }
    LaunchedEffect(Unit) { viewModel.loadSttLanguages() }

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
            val provider = settings.modelProvider

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in ModelProvider.entries) {
                    FilterChip(
                        selected = provider == option,
                        onClick = {
                            viewModel.setProvider(option)
                            keyInput = ""
                        },
                        label = {
                            Text(
                                if (option == ModelProvider.OPENROUTER) "${option.label} (المجاني فقط)" else option.label,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                }
            }

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
                label = { Text("${stringRes(R.string.settings_api_key)} ${provider.label}") },
                placeholder = { Text("${provider.keyPrefix}...", color = TextDim) },
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
                if (settings.modelProvider == ModelProvider.OPENROUTER) {
                    "تُعرَض نماذج OpenRouter المجانية فقط؛ النماذج المدفوعة مرفوضة ولا تُرسَل."
                } else {
                    "استدعاء الأدوات يحتاج نموذجًا يدعمه. اضغط لتحميل نماذج Groq المتاحة لمفتاحك."
                },
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

            val choices = ui.toolCapableModels.map { it.id }.ifEmpty { settings.modelProvider.suggestedModels }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (id in choices.take(24)) {
                    FilterChip(
                        selected = settings.model == id,
                        onClick = { viewModel.setModel(id) },
                        label = { Text(id, style = MaterialTheme.typography.bodyMedium) },
                    )
                }
            }
            if (ui.toolCapableModels.isEmpty()) {
                Text(
                    "القائمة أعلاه مقترحات لم تُتحقَّق من هذا الجهاز بعد.",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // --------------------------------------- speech recognition
        Section(title = "محرّك الصوت") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.sttEngine == "deepgram",
                    onClick = { viewModel.setSttEngine("deepgram") },
                    label = { Text("ديبڨرام (مباشر)") },
                )
                FilterChip(
                    selected = settings.sttEngine == "off",
                    onClick = { viewModel.setSttEngine("off") },
                    label = { Text("كتابة فقط") },
                )
            }

            Text(
                "ديبڨرام يتعرّف على العربية مباشرة أثناء الكلام، ويكشف بداية ونهاية الجملة، " +
                    "ويسمح بمقاطعة جارفيس وهو يتحدّث. النطق يبقى بمحرّكاتنا لأن ديبڨرام " +
                    "لا يوفّر أي صوت عربي.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            var deepgramInput by rememberSaveable { mutableStateOf("") }

            Text(
                if (ui.backendPaired) {
                    "الجهاز مقترن بخادم، فالمفتاح يبقى عليه ولا ينزل إلى الهاتف."
                } else {
                    "بلا خادم: المفتاح يُحفَظ على هذا الهاتف مشفّرًا بـ Keystore. " +
                        "لا تشارك الـ APK بعد إدخاله."
                },
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!ui.backendPaired) {
                if (ui.hasDeepgramKey) {
                    Text("مفتاح ديبڨرام محفوظ ✓", color = SuccessGreen, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = deepgramInput,
                    onValueChange = { deepgramInput = it },
                    label = { Text("مفتاح ديبڨرام") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveDeepgramKey(deepgramInput)
                            deepgramInput = ""
                        },
                        enabled = deepgramInput.isNotBlank(),
                    ) { Text("حفظ") }
                    if (ui.hasDeepgramKey) {
                        TextButton(onClick = viewModel::clearDeepgramKey) { Text("حذف", color = ErrorRed) }
                    }
                }
            }

            if (ui.sttLanguages.isNotEmpty()) {
                Text("اللغة", style = MaterialTheme.typography.bodyLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (language in ui.sttLanguages) {
                        FilterChip(
                            selected = settings.sttLanguage == language.code,
                            onClick = { viewModel.setSttLanguage(language.code) },
                            label = {
                                Text(
                                    listOf(language.label, language.note)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" — "),
                                )
                            },
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = viewModel::testRecognition,
                enabled = ui.backendPaired || ui.hasDeepgramKey,
            ) {
                Text("اختبار الاتصال بالتعرّف")
            }

            ui.sttCheck?.let { message ->
                Text(
                    message,
                    color = if (ui.sttCheckOk == true) SuccessGreen else ErrorRed,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ------------------------------------------------------- voice
        Section(title = stringRes(R.string.settings_voice)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.voiceProvider == "groq",
                    onClick = {
                        viewModel.setVoiceProvider("groq")
                        if (!settings.voice.startsWith("groq-")) viewModel.setVoice("groq-ar-fahad")
                    },
                    label = { Text("Groq (عربي وإنجليزي)") },
                )
                FilterChip(
                    selected = settings.voiceProvider == "azure",
                    onClick = {
                        viewModel.setVoiceProvider("azure")
                        if (settings.voice.startsWith("groq-")) viewModel.setVoice("ar-DZ-IsmaelNeural")
                    },
                    label = { Text("إسماعيل (أزور)") },
                )
                FilterChip(
                    selected = settings.voiceProvider == "relay",
                    onClick = { viewModel.setVoiceProvider("relay") },
                    label = { Text("عبر الخادم") },
                )
                FilterChip(
                    selected = settings.voiceProvider == "system",
                    onClick = { viewModel.setVoiceProvider("system") },
                    label = { Text("محرّك الهاتف") },
                )
            }

            Text(
                if (settings.voiceProvider == "groq") {
                    "أصوات Groq بنفس مفتاح Groq: ستة عربية (بلهجة سعودية) وستة إنجليزية. " +
                        "إن اخترت صوتًا إنجليزيًا سيردّ جارفيس بالإنجليزية، لأن صوتًا إنجليزيًا يقرأ نصًّا عربيًا يخرج كلامًا مشوّهًا. " +
                        "درجة الصوت وسرعته تُطبَّقان عند التشغيل."
                } else if (settings.voiceProvider == "azure") {
                    "يتّصل بأزور مباشرة بمفتاحك. إن تعذّر، ينطق محرّك الهاتف تلقائيًا."
                } else if (settings.voiceProvider == "relay") {
                    "يحتاج إقران الجهاز بالخادم. إن تعذّر، ينطق محرّك الهاتف تلقائيًا."
                } else {
                    "يعمل بلا إنترنت، وجودته تتبع الأصوات العربية المثبّتة على هاتفك."
                },
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (settings.voiceProvider == "azure" && !ui.backendPaired) {
                var azureKeyInput by rememberSaveable { mutableStateOf("") }
                var azureRegionInput by rememberSaveable { mutableStateOf(ui.azureRegion) }

                if (ui.hasAzureKey) {
                    Text(
                        "مفتاح أزور محفوظ ✓ (${ui.azureRegion})",
                        color = SuccessGreen,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = azureKeyInput,
                    onValueChange = { azureKeyInput = it },
                    label = { Text("مفتاح أزور للكلام") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = azureRegionInput,
                    onValueChange = { azureRegionInput = it },
                    label = { Text("المنطقة") },
                    placeholder = { Text("francecentral", color = TextDim) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveAzureCredentials(azureKeyInput, azureRegionInput)
                            azureKeyInput = ""
                        },
                        enabled = azureKeyInput.isNotBlank() && azureRegionInput.isNotBlank(),
                    ) { Text("حفظ") }
                    if (ui.hasAzureKey) {
                        TextButton(onClick = viewModel::clearAzureCredentials) { Text("حذف", color = ErrorRed) }
                    }
                }
            }

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

            // Right under the button: the result used to appear only at the
            // top of this long screen, where nobody pressing the button sees it.
            ui.voiceResult?.let { result ->
                Text(
                    result,
                    color = when (ui.voiceOk) {
                        true -> SuccessGreen
                        false -> WarnAmber
                        null -> TextDim
                    },
                    style = MaterialTheme.typography.bodyMedium,
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
                // Screen automation spends calls on looking before acting,
                // so the ceiling has to sit above what a UI chain needs.
                range = 1f..40f,
                suffix = "",
            ) { viewModel.setMaxToolCalls(it.roundToInt()) }

            SliderRow(
                label = "أقصى عدد لرموز الإخراج",
                value = settings.maxOutputTokens.toFloat(),
                range = 128f..4096f,
                suffix = "",
            ) { viewModel.setMaxOutputTokens(it.roundToInt()) }
        }

        // -------------------------------------------------- screen control
        Section(title = "التحكّم بالشاشة") {
            val context = androidx.compose.ui.platform.LocalContext.current
            // Read on each recomposition rather than cached: the user
            // leaves the app to toggle this and comes straight back.
            val enabled = JarvisAccessibilityService.isEnabled

            Text(
                if (enabled) "مفعّلة — يمكنني النقر والكتابة والتمرير على الشاشة" else "غير مفعّلة",
                color = if (enabled) SuccessGreen else WarnAmber,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "بدونها أستطيع فتح التطبيقات فقط، أمّا النقر داخلها والكتابة فيها " +
                    "فيحتاج هذا الإذن. التفعيل يدويّ من إعدادات النظام — لا يمكن للتطبيق تفعيله بنفسه.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!enabled) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        )
                    }
                }) { Text("افتح إعدادات إمكانية الوصول") }
            }

            ToggleRow(
                label = "جارفيس العائم فوق التطبيقات",
                checked = settings.floatingAvatar,
                onChange = viewModel::setFloatingAvatar,
            )
            Text(
                "رأس جارفيس يظهر فوق الشاشة الرئيسية وباقي التطبيقات. نقرة: يسمعك. " +
                    "ضغطة مطوّلة: يحلّل الشاشة. اسحبه لتحريكه.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            ToggleRow(
                label = "يرى الشاشة مع كل طلب",
                checked = settings.screenAware,
                onChange = viewModel::setScreenAware,
            )
            Text(
                "يُرفَق نصّ الشاشة الحالية بكل طلب، فلا تحتاج أن تقول له «شوف». حقول كلمات السر لا تُقرأ.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            ToggleRow(
                label = "يراقب ويعلّق من نفسه",
                checked = settings.watchScreen,
                onChange = viewModel::setWatchScreen,
            )
            Text(
                "عند فتح تطبيق جديد ينظر إلى الشاشة دون أن تطلب، ويتكلّم فقط إن وجد ما يستحق. " +
                    "تنبيه: يرسل نصّ شاشتك إلى مزوّد النموذج تلقائيًا (مرّة في الدقيقة على الأكثر) ويستهلك من حصّتك.",
                color = WarnAmber,
                style = MaterialTheme.typography.bodyMedium,
            )
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
