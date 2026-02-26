package com.versementor.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(hasPermission: Boolean, onStart: () -> Unit, onSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = stringResource(id = R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(text = if (hasPermission) stringResource(id = R.string.status_idle) else stringResource(id = R.string.permission_mic))
        Button(onClick = onStart, enabled = hasPermission) {
            Text(text = stringResource(id = R.string.start))
        }
        Button(onClick = onSettings) {
            Text(text = stringResource(id = R.string.settings))
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
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = label)
        Checkbox(checked = value, onCheckedChange = onToggle)
    }
}
