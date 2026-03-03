package com.versementor.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(
                            id = R.string.home_app_meta,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.APP_RELEASE_DATE
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xE0FFFFFF),
                        textAlign = TextAlign.Center
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 20.dp)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(viewModel: SessionViewModel, onBack: () -> Unit) {
    val settings = viewModel.settings

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Header
        stickyHeader {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(id = R.string.settings), style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onBack) { Text(text = stringResource(id = R.string.back)) }
            }
        }

        // --- Speech Service Category ---
        item {
            SettingsCategory(title = stringResource(R.string.settings_category_speech))
        }
        item {
            val providerOptions = if (settings.speechProviders.isNotEmpty()) {
                settings.speechProviders
            } else {
                listOf(
                    VoiceOption(id = "iflytek", displayName = "iFlytek"),
                    VoiceOption(id = "volc_asr", displayName = "Volc ASR")
                )
            }
            val selectedProviderName =
                providerOptions.firstOrNull { it.id == settings.speechProviderId }?.displayName
                    ?: settings.speechProviderId
            var providerExpanded by remember { mutableStateOf(false) }

            DropdownSetting(
                title = stringResource(id = R.string.speech_provider),
                selectedValue = selectedProviderName,
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = it },
                options = providerOptions.map { it.id to it.displayName },
                onOptionSelected = { providerId ->
                    viewModel.setSpeechProvider(providerId)
                }
            )
        }
        item {
            var voiceExpanded by remember { mutableStateOf(false) }
            DropdownSetting(
                title = stringResource(id = R.string.tts_voice),
                selectedValue = settings.ttsVoiceName.ifEmpty { stringResource(id = R.string.select) },
                expanded = voiceExpanded,
                onExpandedChange = { voiceExpanded = it },
                options = settings.ttsVoices.map { it.id to it.displayName },
                onOptionSelected = { voiceId ->
                    val voice = settings.ttsVoices.first { it.id == voiceId }
                    viewModel.setTtsVoice(voice.id, voice.displayName)
                }
            )
        }
        item {
            val bargeInOptions = listOf(
                "none" to stringResource(id = R.string.speech_barge_none),
                "duck_tts" to stringResource(id = R.string.speech_barge_duck),
                "stop_tts_on_speech" to stringResource(id = R.string.speech_barge_stop)
            )
            val selectedBargeInName =
                bargeInOptions.firstOrNull { it.first == settings.bargeInMode }?.second ?: settings.bargeInMode
            var bargeModeExpanded by remember { mutableStateOf(false) }
            DropdownSetting(
                title = stringResource(id = R.string.speech_barge_mode),
                selectedValue = selectedBargeInName,
                expanded = bargeModeExpanded,
                onExpandedChange = { bargeModeExpanded = it },
                options = bargeInOptions,
                onOptionSelected = { mode ->
                    viewModel.setBargeInMode(mode)
                }
            )
        }
        item {
            SwitchSetting(
                title = stringResource(id = R.string.speech_allow_listen_while_speaking),
                subtitle = stringResource(id = R.string.speech_allow_listen_while_speaking_hint),
                checked = settings.allowListeningDuringSpeaking,
                onCheckedChange = { viewModel.setAllowListeningDuringSpeaking(it) }
            )
        }
        item {
            SliderSetting(
                title = stringResource(
                    id = R.string.speech_duck_volume,
                    settings.duckVolume * 100f
                ),
                value = settings.duckVolume,
                onValueChange = { viewModel.setDuckVolume(it) },
                enabled = settings.bargeInMode == "duck_tts"
            )
        }
        item {
            SwitchSetting(
                title = stringResource(id = R.string.speech_echo_cancellation),
                checked = settings.enableEchoCancellation,
                onCheckedChange = { viewModel.setEchoCancellationEnabled(it) }
            )
        }
        item {
            SwitchSetting(
                title = stringResource(id = R.string.speech_noise_suppression),
                checked = settings.enableNoiseSuppression,
                onCheckedChange = { viewModel.setNoiseSuppressionEnabled(it) }
            )
        }


        // --- Recognition Tuning Category ---
        item {
            SettingsCategory(title = stringResource(R.string.settings_category_recognition))
        }
        item {
            SwitchSetting(
                title = stringResource(id = R.string.tone_policy),
                subtitle = stringResource(id = R.string.tone_policy_hint),
                checked = settings.toneRemind,
                onCheckedChange = { viewModel.setToneRemind(it) }
            )
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = stringResource(id = R.string.accent_tolerance), style = MaterialTheme.typography.bodyLarge)
                Text(text = stringResource(id = R.string.accent_tolerance_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    CheckboxSetting("an/ang", settings.accentTolerance.anAng) { viewModel.setAccentTolerance(anAng = it) }
                    CheckboxSetting("en/eng", settings.accentTolerance.enEng) { viewModel.setAccentTolerance(enEng = it) }
                    CheckboxSetting("in/ing", settings.accentTolerance.inIng) { viewModel.setAccentTolerance(inIng = it) }
                    CheckboxSetting("ian/iang", settings.accentTolerance.ianIang) { viewModel.setAccentTolerance(ianIang = it) }
                }
            }
            HorizontalDivider()
        }

        // --- Content Management Category ---
        item {
            SettingsCategory(title = stringResource(R.string.settings_category_content))
        }
        item {
            SwitchSetting(
                title = stringResource(id = R.string.variants_enable),
                checked = settings.variantsEnable,
                onCheckedChange = { viewModel.setVariantsEnable(it) }
            )
        }
        item {
            var ttlText by remember { mutableStateOf(settings.variantTtlDays.toString()) }
            var ttlError by remember { mutableStateOf(false) }
            LaunchedEffect(settings.variantTtlDays) {
                ttlText = settings.variantTtlDays.toString()
                ttlError = false
            }
            TextFieldSetting(
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
                label = stringResource(id = R.string.variants_ttl),
                isError = ttlError,
                supportingText = if (ttlError) {
                    stringResource(id = R.string.variants_ttl_invalid, SessionViewModel.MIN_VARIANT_TTL_DAYS, SessionViewModel.MAX_VARIANT_TTL_DAYS)
                } else {
                    stringResource(id = R.string.variants_ttl_hint, SessionViewModel.MIN_VARIANT_TTL_DAYS, SessionViewModel.MAX_VARIANT_TTL_DAYS)
                }
            )
        }
        item {
            ClickableSetting(
                title = stringResource(id = R.string.variants_clear),
                subtitle = stringResource(id = R.string.variants_clear_hint),
                onClick = { viewModel.clearVariantCache() }
            )
        }
        item {
             // TODO: Add UI for Dynasty and Author management
        }


        // --- Advanced ASR Tuning Category ---
        item {
            val shortSpeechFramesFloor = if (
                settings.asrMinAcceptedSpeechFrames > SessionViewModel.MIN_ASR_SHORT_SPEECH_ACCEPT_FRAMES
            ) {
                settings.asrMinAcceptedSpeechFrames
            } else {
                SessionViewModel.MIN_ASR_SHORT_SPEECH_ACCEPT_FRAMES
            }
            ExpandableSettingsCategory(title = stringResource(R.string.settings_category_advanced)) {
                var transientPromptText by remember { mutableStateOf(settings.transientAsrPromptThreshold.toString()) }
                var transientPromptError by remember { mutableStateOf(false) }
                var transientDelayText by remember { mutableStateOf(settings.transientAsrRetryDelayMs.toString()) }
                var transientDelayError by remember { mutableStateOf(false) }
                var stopStartCooldownText by remember { mutableStateOf(settings.asrStopToStartCooldownMs.toString()) }
                var stopStartCooldownError by remember { mutableStateOf(false) }
                var minAcceptedSpeechMsText by remember { mutableStateOf(settings.asrMinAcceptedSpeechMs.toString()) }
                var minAcceptedSpeechMsError by remember { mutableStateOf(false) }
                var minAcceptedSpeechFramesText by remember { mutableStateOf(settings.asrMinAcceptedSpeechFrames.toString()) }
                var minAcceptedSpeechFramesError by remember { mutableStateOf(false) }
                var shortSpeechAcceptFramesText by remember { mutableStateOf(settings.asrShortSpeechAcceptFrames.toString()) }
                var shortSpeechAcceptFramesError by remember { mutableStateOf(false) }

                LaunchedEffect(settings.transientAsrPromptThreshold) { transientPromptText = settings.transientAsrPromptThreshold.toString(); transientPromptError = false }
                LaunchedEffect(settings.transientAsrRetryDelayMs) { transientDelayText = settings.transientAsrRetryDelayMs.toString(); transientDelayError = false }
                LaunchedEffect(settings.asrStopToStartCooldownMs) { stopStartCooldownText = settings.asrStopToStartCooldownMs.toString(); stopStartCooldownError = false }
                LaunchedEffect(settings.asrMinAcceptedSpeechMs) { minAcceptedSpeechMsText = settings.asrMinAcceptedSpeechMs.toString(); minAcceptedSpeechMsError = false }
                LaunchedEffect(settings.asrMinAcceptedSpeechFrames) { minAcceptedSpeechFramesText = settings.asrMinAcceptedSpeechFrames.toString(); minAcceptedSpeechFramesError = false }
                LaunchedEffect(settings.asrShortSpeechAcceptFrames) { shortSpeechAcceptFramesText = settings.asrShortSpeechAcceptFrames.toString(); shortSpeechAcceptFramesError = false }


                TextFieldSetting(
                    value = transientPromptText,
                    onValueChange = {
                        transientPromptText = it
                        val value = it.toIntOrNull()
                        val isValid = value != null && value in SessionViewModel.MIN_TRANSIENT_ASR_PROMPT_THRESHOLD..SessionViewModel.MAX_TRANSIENT_ASR_PROMPT_THRESHOLD
                        transientPromptError = it.isNotEmpty() && !isValid
                        if (isValid) value?.let(viewModel::setTransientAsrPromptThreshold)
                    },
                    label = stringResource(id = R.string.asr_transient_prompt_threshold),
                    isError = transientPromptError,
                    supportingText = stringResource(id = R.string.asr_transient_prompt_threshold_hint, SessionViewModel.MIN_TRANSIENT_ASR_PROMPT_THRESHOLD, SessionViewModel.MAX_TRANSIENT_ASR_PROMPT_THRESHOLD)
                )
                TextFieldSetting(
                    value = transientDelayText,
                    onValueChange = {
                        transientDelayText = it
                        val value = it.toIntOrNull()
                        val isValid = value != null && value in SessionViewModel.MIN_TRANSIENT_ASR_RETRY_DELAY_MS..SessionViewModel.MAX_TRANSIENT_ASR_RETRY_DELAY_MS
                        transientDelayError = it.isNotEmpty() && !isValid
                        if (isValid) value?.let(viewModel::setTransientAsrRetryDelayMs)
                    },
                    label = stringResource(id = R.string.asr_transient_retry_delay_ms),
                    isError = transientDelayError,
                    supportingText = stringResource(id = R.string.asr_transient_retry_delay_ms_hint, SessionViewModel.MIN_TRANSIENT_ASR_RETRY_DELAY_MS, SessionViewModel.MAX_TRANSIENT_ASR_RETRY_DELAY_MS)
                )
                TextFieldSetting(
                    value = stopStartCooldownText,
                    onValueChange = {
                        stopStartCooldownText = it
                        val value = it.toIntOrNull()
                        val isValid = value != null && value in SessionViewModel.MIN_ASR_STOP_TO_START_COOLDOWN_MS..SessionViewModel.MAX_ASR_STOP_TO_START_COOLDOWN_MS
                        stopStartCooldownError = it.isNotEmpty() && !isValid
                        if (isValid) value?.let(viewModel::setAsrStopToStartCooldownMs)
                    },
                    label = stringResource(id = R.string.asr_stop_start_cooldown_ms),
                    isError = stopStartCooldownError,
                    supportingText = stringResource(id = R.string.asr_stop_start_cooldown_ms_hint, SessionViewModel.MIN_ASR_STOP_TO_START_COOLDOWN_MS, SessionViewModel.MAX_ASR_STOP_TO_START_COOLDOWN_MS)
                )
                TextFieldSetting(
                    value = minAcceptedSpeechMsText,
                    onValueChange = {
                        minAcceptedSpeechMsText = it
                        val value = it.toIntOrNull()
                        val isValid = value != null && value in SessionViewModel.MIN_ASR_MIN_ACCEPTED_SPEECH_MS..SessionViewModel.MAX_ASR_MIN_ACCEPTED_SPEECH_MS
                        minAcceptedSpeechMsError = it.isNotEmpty() && !isValid
                        if (isValid) value?.let(viewModel::setAsrMinAcceptedSpeechMs)
                    },
                    label = stringResource(id = R.string.asr_min_accepted_speech_ms),
                    isError = minAcceptedSpeechMsError,
                    supportingText = stringResource(id = R.string.asr_min_accepted_speech_ms_hint, SessionViewModel.MIN_ASR_MIN_ACCEPTED_SPEECH_MS, SessionViewModel.MAX_ASR_MIN_ACCEPTED_SPEECH_MS)
                )
                TextFieldSetting(
                    value = minAcceptedSpeechFramesText,
                    onValueChange = {
                        minAcceptedSpeechFramesText = it
                        val value = it.toIntOrNull()
                        val isValid = value != null && value in SessionViewModel.MIN_ASR_MIN_ACCEPTED_SPEECH_FRAMES..SessionViewModel.MAX_ASR_MIN_ACCEPTED_SPEECH_FRAMES
                        minAcceptedSpeechFramesError = it.isNotEmpty() && !isValid
                        if (isValid) value?.let(viewModel::setAsrMinAcceptedSpeechFrames)
                    },
                    label = stringResource(id = R.string.asr_min_accepted_speech_frames),
                    isError = minAcceptedSpeechFramesError,
                    supportingText = stringResource(id = R.string.asr_min_accepted_speech_frames_hint, SessionViewModel.MIN_ASR_MIN_ACCEPTED_SPEECH_FRAMES, SessionViewModel.MAX_ASR_MIN_ACCEPTED_SPEECH_FRAMES)
                )
                TextFieldSetting(
                    value = shortSpeechAcceptFramesText,
                    onValueChange = {
                        shortSpeechAcceptFramesText = it
                        val value = it.toIntOrNull()
                        val isValid = value != null && value in shortSpeechFramesFloor..SessionViewModel.MAX_ASR_SHORT_SPEECH_ACCEPT_FRAMES
                        shortSpeechAcceptFramesError = it.isNotEmpty() && !isValid
                        if (isValid) value?.let(viewModel::setAsrShortSpeechAcceptFrames)
                    },
                    label = stringResource(id = R.string.asr_short_speech_accept_frames),
                    isError = shortSpeechAcceptFramesError,
                    supportingText = stringResource(id = R.string.asr_short_speech_accept_frames_hint, shortSpeechFramesFloor, SessionViewModel.MAX_ASR_SHORT_SPEECH_ACCEPT_FRAMES)
                )
                Spacer(modifier = Modifier.height(8.dp))
                ClickableSetting(title = stringResource(id = R.string.debug_reset_asr_tuning), onClick = { viewModel.resetTransientAsrTuning() })
            }
        }

        // --- Debugging Category ---
        item {
            ExpandableSettingsCategory(title = stringResource(R.string.settings_category_debug)) {
                DebugInfoSetting(label = stringResource(id = R.string.debug_all_bridge_checks), value = viewModel.allBridgeCheckResult) {
                    viewModel.runAllBridgeChecks()
                }
                DebugInfoSetting(label = stringResource(id = R.string.debug_bridge_event_check), value = viewModel.eventCheckResult) {
                    viewModel.runBridgeEventRoundTripCheck()
                }
                DebugInfoSetting(label = stringResource(id = R.string.debug_bridge_codec_check), value = viewModel.codecCheckResult) {
                    viewModel.runBridgeCodecCheck()
                }
                DebugInfoSetting(label = stringResource(id = R.string.debug_runtime_path_check), value = viewModel.runtimeCheckResult) {
                    viewModel.runRuntimePathCheck()
                }
                DebugInfoSetting(label = stringResource(id = R.string.debug_asr_error_check), value = viewModel.debugCheckResult) {
                    viewModel.runAsrErrorFlowCheck()
                }
            }
        }
    }
}

// --- Reusable Setting Composables ---

@Composable
private fun SettingsCategory(title: String) {
    Column {
        HorizontalDivider()
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun ExpandableSettingsCategory(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    SettingsCategory(title)
    Box(modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }
        .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    AnimatedVisibility(visible = expanded) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            content()
        }
    }
}


@Composable
private fun ClickableSetting(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun SwitchSetting(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
    HorizontalDivider()
}

@Composable
private fun DropdownSetting(
    title: String,
    selectedValue: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<Pair<String, String>>,
    onOptionSelected: (String) -> Unit
) {
    Box {
        ClickableSetting(
            title = title,
            subtitle = selectedValue,
            onClick = { onExpandedChange(true) }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            options.forEach { (id, displayName) ->
                DropdownMenuItem(
                    text = { Text(displayName) },
                    onClick = {
                        onOptionSelected(id)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun CheckboxSetting(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle(!checked) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(modifier = Modifier.size(6.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            enabled = enabled
        )
    }
    HorizontalDivider()
}

@Composable
fun TextFieldSetting(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    supportingText: String
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(text = label) },
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(text = supportingText) }
        )
    }
}

@Composable
private fun DebugInfoSetting(label: String, value: String, onCheck: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value.ifEmpty { stringResource(R.string.debug_not_run) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onCheck) {
            Text(stringResource(R.string.run_check))
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}
