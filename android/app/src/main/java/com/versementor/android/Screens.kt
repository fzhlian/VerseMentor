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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.versementor.android.session.SessionUiState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    hasPermission: Boolean,
    uiState: SessionUiState,
    onControlTap: () -> Unit,
    onControlLongPress: () -> Unit,
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
fun SessionScreen(viewModel: SessionViewModel, onBack: () -> Unit) {
    val ui = viewModel.uiState
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(id = R.string.session), style = MaterialTheme.typography.headlineSmall)
        Text(text = ui.statusText)
        Text(text = ui.lastSpoken)
        Text(text = ui.lastHeard)
        Button(onClick = onBack) { Text(text = "Back") }
        Divider()
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
    var expanded by remember { mutableStateOf(false) }
    var ttlText by remember { mutableStateOf(settings.variantTtlDays.toString()) }
    var ttlError by remember { mutableStateOf(false) }
    var aliasText by remember { mutableStateOf("") }
    var canonicalText by remember { mutableStateOf("") }
    var groupAlias by remember { mutableStateOf("") }
    var groupIds by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf("") }
    var authorAlias by remember { mutableStateOf("") }

    LaunchedEffect(settings.variantTtlDays) {
        ttlText = settings.variantTtlDays.toString()
        ttlError = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(id = R.string.settings), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onBack) { Text(text = "Back") }
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

        Text(text = stringResource(id = R.string.tts_voice))
        Button(onClick = { expanded = true }) {
            Text(text = settings.ttsVoiceName.ifEmpty { "Select" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            settings.ttsVoices.forEach { voice ->
                DropdownMenuItem(text = { Text(voice.displayName) }, onClick = {
                    viewModel.setTtsVoice(voice.id, voice.displayName)
                    expanded = false
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
            OutlinedTextField(modifier = Modifier.weight(1f), value = groupIds, onValueChange = { groupIds = it }, label = { Text(text = "Ids/Names" ) })
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
            OutlinedTextField(modifier = Modifier.weight(1f), value = authorName, onValueChange = { authorName = it }, label = { Text(text = "Author" ) })
            OutlinedTextField(modifier = Modifier.weight(1f), value = authorAlias, onValueChange = { authorAlias = it }, label = { Text(text = stringResource(id = R.string.alias)) })
            Button(onClick = { viewModel.addAuthor(authorName, authorAlias); authorName = ""; authorAlias = "" }) {
                Text(text = stringResource(id = R.string.add))
            }
        }

        Divider()
        Text(text = "Debug")
        Text(text = "ASR Error Check: ${viewModel.debugCheckResult}")
        Button(onClick = { viewModel.runAsrErrorFlowCheck() }) {
            Text(text = "Check ASR Error")
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
