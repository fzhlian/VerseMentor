package com.versementor.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import com.versementor.android.session.SessionUiState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    hasPermission: Boolean,
    uiState: SessionUiState,
    asrLogCount: Int,
    canReplayAudio: Boolean,
    onControlTap: () -> Unit,
    onControlLongPress: () -> Unit,
    onReplayAudio: () -> Unit,
    onLogs: () -> Unit,
    onSettings: () -> Unit
) {
    val startInteractionSource = remember { MutableInteractionSource() }
    val isStartPressed by startInteractionSource.collectIsPressedAsState()
    val recognizedLines = uiState.recognizedLines.takeLast(6)
    val showStopPreview = uiState.sessionActive && !uiState.sessionPaused && isStartPressed
    val buttonLabel = when {
        !uiState.sessionActive -> stringResource(id = R.string.button_start)
        uiState.sessionPaused -> stringResource(id = R.string.button_resume)
        showStopPreview -> stringResource(id = R.string.button_stop)
        else -> stringResource(id = R.string.button_pause)
    }
    val statusLine = when {
        !hasPermission -> stringResource(id = R.string.permission_mic)
        uiState.awaitingSpeech -> stringResource(id = R.string.waiting_input)
        uiState.liveHeard.isNotBlank() -> uiState.liveHeard
        else -> uiState.statusText
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.home_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onLogs) {
                    Text(
                        text = stringResource(id = R.string.home_asr_logs_entry, asrLogCount),
                        color = Color.White
                    )
                }
                TextButton(
                    enabled = canReplayAudio,
                    onClick = onReplayAudio
                ) {
                    Text(
                        text = stringResource(id = R.string.home_replay_audio),
                        color = if (canReplayAudio) Color.White else Color(0xA0FFFFFF)
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource(id = R.string.settings),
                        tint = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xF2F8FAFC))
                        .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    if (recognizedLines.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.home_poem_placeholder),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF1F2937),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            recognizedLines.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color(0xFF1F2937),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x7A000000))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = statusLine,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 20.dp)
                        .size(width = 220.dp, height = 88.dp)
                        .clip(RoundedCornerShape(44.dp))
                        .combinedClickable(
                            enabled = hasPermission,
                            interactionSource = startInteractionSource,
                            indication = null,
                            onClick = onControlTap,
                            onLongClick = onControlLongPress
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 132.dp, height = 52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(Color(0xC8F39A38))
                    )
                    Text(
                        text = buttonLabel,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun AsrLogsScreen(viewModel: SessionViewModel, onBack: () -> Unit) {
    val asrLogs = viewModel.getAsrLogs()
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(id = R.string.asr_logs_title), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onBack) { Text(text = stringResource(id = R.string.back)) }
        }
        Text(text = stringResource(id = R.string.asr_logs_count, asrLogs.size))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = asrLogs.isNotEmpty(),
                onClick = { clipboard.setText(AnnotatedString(viewModel.getAsrLogText())) }
            ) {
                Text(text = stringResource(id = R.string.asr_logs_copy))
            }
            Button(
                enabled = asrLogs.isNotEmpty(),
                onClick = { viewModel.clearAsrLogs() }
            ) {
                Text(text = stringResource(id = R.string.asr_logs_clear))
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(asrLogs.takeLast(80)) { line ->
                Text(text = line)
            }
        }
    }
}

@Composable
fun SessionScreen(viewModel: SessionViewModel, onBack: () -> Unit) {
    val ui = viewModel.uiState
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(id = R.string.session), style = MaterialTheme.typography.headlineSmall)
        Text(text = ui.statusText)
        Text(text = ui.lastSpoken)
        Text(text = ui.lastHeard)
        Button(onClick = onBack) { Text(text = stringResource(id = R.string.back)) }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.logs) { line ->
                Text(text = line)
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SessionViewModel, onBack: () -> Unit) {
    val settings = viewModel.settings
    val scrollState = rememberScrollState()
    var providerExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var bargeModeExpanded by remember { mutableStateOf(false) }
    var ttlText by remember { mutableStateOf(settings.variantTtlDays.toString()) }
    var ttlError by remember { mutableStateOf(false) }
    var transientPromptText by remember { mutableStateOf(settings.transientAsrPromptThreshold.toString()) }
    var transientPromptError by remember { mutableStateOf(false) }
    var transientDelayText by remember { mutableStateOf(settings.transientAsrRetryDelayMs.toString()) }
    var transientDelayError by remember { mutableStateOf(false) }
    var stopStartCooldownText by remember { mutableStateOf(settings.asrStopToStartCooldownMs.toString()) }
    var stopStartCooldownError by remember { mutableStateOf(false) }
    var aliasText by remember { mutableStateOf("") }
    var canonicalText by remember { mutableStateOf("") }
    var groupAlias by remember { mutableStateOf("") }
    var groupIds by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf("") }
    var authorAlias by remember { mutableStateOf("") }
    val providerOptions = if (settings.speechProviders.isNotEmpty()) {
        settings.speechProviders
    } else {
        listOf(
            VoiceOption(id = "iflytek", displayName = "iFlytek"),
            VoiceOption(id = "volcengine", displayName = "Volcengine")
        )
    }
    val selectedProviderName =
        providerOptions.firstOrNull { it.id == settings.speechProviderId }?.displayName ?: settings.speechProviderId
    val bargeInOptions = listOf(
        "none" to stringResource(id = R.string.speech_barge_none),
        "duck_tts" to stringResource(id = R.string.speech_barge_duck),
        "stop_tts_on_speech" to stringResource(id = R.string.speech_barge_stop)
    )
    val selectedBargeInName =
        bargeInOptions.firstOrNull { it.first == settings.bargeInMode }?.second ?: settings.bargeInMode

    LaunchedEffect(settings.variantTtlDays) {
        ttlText = settings.variantTtlDays.toString()
        ttlError = false
    }
    LaunchedEffect(settings.transientAsrPromptThreshold) {
        transientPromptText = settings.transientAsrPromptThreshold.toString()
        transientPromptError = false
    }
    LaunchedEffect(settings.transientAsrRetryDelayMs) {
        transientDelayText = settings.transientAsrRetryDelayMs.toString()
        transientDelayError = false
    }
    LaunchedEffect(settings.asrStopToStartCooldownMs) {
        stopStartCooldownText = settings.asrStopToStartCooldownMs.toString()
        stopStartCooldownError = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(id = R.string.settings), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onBack) { Text(text = stringResource(id = R.string.back)) }
        }

        Text(text = stringResource(id = R.string.language))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.setFollowSystem(true) }) {
                Text(text = stringResource(id = R.string.language_system))
            }
            Button(onClick = { viewModel.setFollowSystem(false) }) {
                Text(text = stringResource(id = R.string.language_zh))
            }
        }

        Text(text = stringResource(id = R.string.speech_provider))
        Button(onClick = { providerExpanded = true }) {
            Text(text = selectedProviderName)
        }
        DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
            providerOptions.forEach { provider ->
                DropdownMenuItem(text = { Text(provider.displayName) }, onClick = {
                    viewModel.setSpeechProvider(provider.id)
                    providerExpanded = false
                })
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(id = R.string.speech_allow_listen_while_speaking))
            Switch(
                checked = settings.allowListeningDuringSpeaking,
                onCheckedChange = { viewModel.setAllowListeningDuringSpeaking(it) }
            )
        }
        Text(text = stringResource(id = R.string.speech_barge_mode))
        Button(onClick = { bargeModeExpanded = true }) {
            Text(text = selectedBargeInName)
        }
        DropdownMenu(expanded = bargeModeExpanded, onDismissRequest = { bargeModeExpanded = false }) {
            bargeInOptions.forEach { (mode, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    viewModel.setBargeInMode(mode)
                    bargeModeExpanded = false
                })
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(id = R.string.speech_echo_cancellation))
            Switch(
                checked = settings.enableEchoCancellation,
                onCheckedChange = { viewModel.setEchoCancellationEnabled(it) }
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(id = R.string.speech_noise_suppression))
            Switch(
                checked = settings.enableNoiseSuppression,
                onCheckedChange = { viewModel.setNoiseSuppressionEnabled(it) }
            )
        }

        Text(text = stringResource(id = R.string.tts_voice))
        Button(onClick = { voiceExpanded = true }) {
            Text(text = settings.ttsVoiceName.ifEmpty { stringResource(id = R.string.select) })
        }
        DropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
            settings.ttsVoices.forEach { voice ->
                DropdownMenuItem(text = { Text(voice.displayName) }, onClick = {
                    viewModel.setTtsVoice(voice.id, voice.displayName)
                    voiceExpanded = false
                })
            }
        }

        Text(text = stringResource(id = R.string.accent_tolerance))
        ToggleRow(label = "an/ang", value = settings.accentTolerance.anAng, onToggle = { viewModel.setAccentTolerance(anAng = it) })
        ToggleRow(label = "en/eng", value = settings.accentTolerance.enEng, onToggle = { viewModel.setAccentTolerance(enEng = it) })
        ToggleRow(label = "in/ing", value = settings.accentTolerance.inIng, onToggle = { viewModel.setAccentTolerance(inIng = it) })
        ToggleRow(label = "ian/iang", value = settings.accentTolerance.ianIang, onToggle = { viewModel.setAccentTolerance(ianIang = it) })

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(id = R.string.tone_policy))
            Switch(checked = settings.toneRemind, onCheckedChange = { viewModel.setToneRemind(it) })
        }

        Text(text = stringResource(id = R.string.variants))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(id = R.string.variants_enable))
            Switch(checked = settings.variantsEnable, onCheckedChange = { viewModel.setVariantsEnable(it) })
        }
        OutlinedTextField(
            value = ttlText,
            onValueChange = {
                ttlText = it
                val value = it.toIntOrNull()
                val isValid = value != null && value in SessionViewModel.MIN_VARIANT_TTL_DAYS..SessionViewModel.MAX_VARIANT_TTL_DAYS
                ttlError = it.isNotEmpty() && !isValid
                if (isValid) {
                    value?.let(viewModel::setVariantTtl)
                }
            },
            label = { Text(text = stringResource(id = R.string.variants_ttl)) },
            isError = ttlError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                if (ttlError) {
                    Text(
                        text = stringResource(
                            id = R.string.variants_ttl_invalid,
                            SessionViewModel.MIN_VARIANT_TTL_DAYS,
                            SessionViewModel.MAX_VARIANT_TTL_DAYS
                        )
                    )
                } else {
                    Text(
                        text = stringResource(
                            id = R.string.variants_ttl_hint,
                            SessionViewModel.MIN_VARIANT_TTL_DAYS,
                            SessionViewModel.MAX_VARIANT_TTL_DAYS
                        )
                    )
                }
            }
        )
        Button(onClick = { viewModel.clearVariantCache() }) {
            Text(text = stringResource(id = R.string.variants_clear))
        }
        OutlinedTextField(
            value = transientPromptText,
            onValueChange = {
                transientPromptText = it
                val value = it.toIntOrNull()
                val isValid =
                    value != null &&
                        value in SessionViewModel.MIN_TRANSIENT_ASR_PROMPT_THRESHOLD..SessionViewModel.MAX_TRANSIENT_ASR_PROMPT_THRESHOLD
                transientPromptError = it.isNotEmpty() && !isValid
                if (isValid) {
                    value?.let(viewModel::setTransientAsrPromptThreshold)
                }
            },
            label = { Text(text = stringResource(id = R.string.asr_transient_prompt_threshold)) },
            isError = transientPromptError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                if (transientPromptError) {
                    Text(
                        text = stringResource(
                            id = R.string.asr_transient_prompt_threshold_invalid,
                            SessionViewModel.MIN_TRANSIENT_ASR_PROMPT_THRESHOLD,
                            SessionViewModel.MAX_TRANSIENT_ASR_PROMPT_THRESHOLD
                        )
                    )
                } else {
                    Text(
                        text = stringResource(
                            id = R.string.asr_transient_prompt_threshold_hint,
                            SessionViewModel.MIN_TRANSIENT_ASR_PROMPT_THRESHOLD,
                            SessionViewModel.MAX_TRANSIENT_ASR_PROMPT_THRESHOLD
                        )
                    )
                }
            }
        )
        OutlinedTextField(
            value = transientDelayText,
            onValueChange = {
                transientDelayText = it
                val value = it.toIntOrNull()
                val isValid =
                    value != null &&
                        value in SessionViewModel.MIN_TRANSIENT_ASR_RETRY_DELAY_MS..SessionViewModel.MAX_TRANSIENT_ASR_RETRY_DELAY_MS
                transientDelayError = it.isNotEmpty() && !isValid
                if (isValid) {
                    value?.let(viewModel::setTransientAsrRetryDelayMs)
                }
            },
            label = { Text(text = stringResource(id = R.string.asr_transient_retry_delay_ms)) },
            isError = transientDelayError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                if (transientDelayError) {
                    Text(
                        text = stringResource(
                            id = R.string.asr_transient_retry_delay_ms_invalid,
                            SessionViewModel.MIN_TRANSIENT_ASR_RETRY_DELAY_MS,
                            SessionViewModel.MAX_TRANSIENT_ASR_RETRY_DELAY_MS
                        )
                    )
                } else {
                    Text(
                        text = stringResource(
                            id = R.string.asr_transient_retry_delay_ms_hint,
                            SessionViewModel.MIN_TRANSIENT_ASR_RETRY_DELAY_MS,
                            SessionViewModel.MAX_TRANSIENT_ASR_RETRY_DELAY_MS
                        )
                    )
                }
            }
        )
        OutlinedTextField(
            value = stopStartCooldownText,
            onValueChange = {
                stopStartCooldownText = it
                val value = it.toIntOrNull()
                val isValid =
                    value != null &&
                        value in SessionViewModel.MIN_ASR_STOP_TO_START_COOLDOWN_MS..SessionViewModel.MAX_ASR_STOP_TO_START_COOLDOWN_MS
                stopStartCooldownError = it.isNotEmpty() && !isValid
                if (isValid) {
                    value?.let(viewModel::setAsrStopToStartCooldownMs)
                }
            },
            label = { Text(text = stringResource(id = R.string.asr_stop_start_cooldown_ms)) },
            isError = stopStartCooldownError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                if (stopStartCooldownError) {
                    Text(
                        text = stringResource(
                            id = R.string.asr_stop_start_cooldown_ms_invalid,
                            SessionViewModel.MIN_ASR_STOP_TO_START_COOLDOWN_MS,
                            SessionViewModel.MAX_ASR_STOP_TO_START_COOLDOWN_MS
                        )
                    )
                } else {
                    Text(
                        text = stringResource(
                            id = R.string.asr_stop_start_cooldown_ms_hint,
                            SessionViewModel.MIN_ASR_STOP_TO_START_COOLDOWN_MS,
                            SessionViewModel.MAX_ASR_STOP_TO_START_COOLDOWN_MS
                        )
                    )
                }
            }
        )

        Text(text = stringResource(id = R.string.dynasty_mapping))
        LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            items(settings.dynastyMappings) { mapping ->
                Text(text = mapping)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(modifier = Modifier.weight(1f), value = aliasText, onValueChange = { aliasText = it }, label = { Text(text = stringResource(id = R.string.alias)) })
            OutlinedTextField(modifier = Modifier.weight(1f), value = canonicalText, onValueChange = { canonicalText = it }, label = { Text(text = stringResource(id = R.string.canonical)) })
            Button(onClick = { viewModel.addDynastyAlias(aliasText, canonicalText); aliasText = ""; canonicalText = "" }) {
                Text(text = stringResource(id = R.string.add))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(modifier = Modifier.weight(1f), value = groupAlias, onValueChange = { groupAlias = it }, label = { Text(text = stringResource(id = R.string.group)) })
            OutlinedTextField(modifier = Modifier.weight(1f), value = groupIds, onValueChange = { groupIds = it }, label = { Text(text = stringResource(id = R.string.ids_names)) })
            Button(onClick = { viewModel.addDynastyGroup(groupAlias, groupIds); groupAlias = ""; groupIds = "" }) {
                Text(text = stringResource(id = R.string.add))
            }
        }

        Text(text = stringResource(id = R.string.author_library))
        LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            items(settings.authors) { author ->
                Text(text = author)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(modifier = Modifier.weight(1f), value = authorName, onValueChange = { authorName = it }, label = { Text(text = stringResource(id = R.string.author)) })
            OutlinedTextField(modifier = Modifier.weight(1f), value = authorAlias, onValueChange = { authorAlias = it }, label = { Text(text = stringResource(id = R.string.alias)) })
            Button(onClick = { viewModel.addAuthor(authorName, authorAlias); authorName = ""; authorAlias = "" }) {
                Text(text = stringResource(id = R.string.add))
            }
        }

        HorizontalDivider()
        Text(text = stringResource(id = R.string.debug_title))
        Text(
            text = stringResource(
                id = R.string.debug_asr_prompt_threshold,
                settings.transientAsrPromptThreshold
            )
        )
        Text(
            text = stringResource(
                id = R.string.debug_asr_retry_delay_ms,
                settings.transientAsrRetryDelayMs
            )
        )
        Text(
            text = stringResource(
                id = R.string.debug_asr_stop_start_cooldown_ms,
                settings.asrStopToStartCooldownMs
            )
        )
        Button(onClick = { viewModel.resetTransientAsrTuning() }) {
            Text(text = stringResource(id = R.string.debug_reset_asr_tuning))
        }
        Text(text = stringResource(id = R.string.debug_all_bridge_checks, viewModel.allBridgeCheckResult))
        Button(onClick = { viewModel.runAllBridgeChecks() }) {
            Text(text = stringResource(id = R.string.debug_check_all_bridge))
        }
        Text(text = stringResource(id = R.string.debug_bridge_event_check, viewModel.eventCheckResult))
        Button(onClick = { viewModel.runBridgeEventRoundTripCheck() }) {
            Text(text = stringResource(id = R.string.debug_check_bridge_event))
        }
        Text(text = stringResource(id = R.string.debug_bridge_codec_check, viewModel.codecCheckResult))
        Button(onClick = { viewModel.runBridgeCodecCheck() }) {
            Text(text = stringResource(id = R.string.debug_check_bridge_codec))
        }
        Text(text = stringResource(id = R.string.debug_runtime_path_check, viewModel.runtimeCheckResult))
        Button(onClick = { viewModel.runRuntimePathCheck() }) {
            Text(text = stringResource(id = R.string.debug_check_runtime_path))
        }
        Text(text = stringResource(id = R.string.debug_asr_error_check, viewModel.debugCheckResult))
        Button(onClick = { viewModel.runAsrErrorFlowCheck() }) {
            Text(text = stringResource(id = R.string.debug_check_asr_error))
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = label)
        Checkbox(checked = value, onCheckedChange = onToggle)
    }
}
