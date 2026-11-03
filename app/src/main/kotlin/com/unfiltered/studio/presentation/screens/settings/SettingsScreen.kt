package com.unfiltered.studio.presentation.screens.settings

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unfiltered.studio.BuildConfig
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.domain.models.ConnectionState
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.models.SaveLocation
import com.unfiltered.studio.domain.models.ThemeMode
import com.unfiltered.studio.presentation.components.BannerTone
import com.unfiltered.studio.presentation.components.InfoBanner
import com.unfiltered.studio.presentation.components.LabeledSlider
import com.unfiltered.studio.presentation.components.SecretField
import com.unfiltered.studio.presentation.components.SectionCard
import com.unfiltered.studio.presentation.components.ShareHelper
import com.unfiltered.studio.presentation.components.StatusDot

@Composable
fun SettingsScreen(
    onEditProvider: (String) -> Unit,
    onAddProvider: (ProviderType) -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val providers by viewModel.providerList.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val accounts by viewModel.accountState.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var chooseType by remember { mutableStateOf(false) }
    var hfToken by rememberSaveable { mutableStateOf("") }
    var civitaiKey by rememberSaveable { mutableStateOf("") }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            viewModel.update { it.copy(saveLocation = SaveLocation.CUSTOM_FOLDER, customFolderUri = uri.toString()) }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            SectionCard(title = "Providers", subtitle = "GPU backends and inference APIs", icon = Icons.Default.Cloud, actions = {
                FilledTonalButton(onClick = { chooseType = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add") }
            }) {
                if (providers.isEmpty()) Text("No providers yet. Add ComfyUI, WebUI/Forge, Hugging Face or a custom API.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                providers.forEach { p ->
                    val status = statuses[p.id]
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, onClick = { onEditProvider(p.id) }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = active?.id == p.id, onClick = { viewModel.setActive(p.id) })
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(status?.state ?: ConnectionState.UNKNOWN)
                                    Spacer(Modifier.width(6.dp))
                                    Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${p.type.displayName} · ${p.baseUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                status?.let { s ->
                                    val text = when (s.state) {
                                        ConnectionState.ONLINE -> listOfNotNull(s.info?.serverName, s.info?.version).joinToString(" ") + (s.info?.details?.firstOrNull()?.let { " · $it" } ?: "")
                                        ConnectionState.ERROR -> s.error
                                        ConnectionState.CHECKING -> "Checking…"
                                        ConnectionState.UNKNOWN -> null
                                    }
                                    text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (s.state == ConnectionState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                            TextButton(onClick = { viewModel.test(p.id) }) { Text("Test") }
                        }
                    }
                }
            }

            SectionCard(title = "Accounts & API keys", subtitle = "Stored encrypted with the Android Keystore", icon = Icons.Default.Key) {
                Text("Hugging Face", style = MaterialTheme.typography.titleSmall)
                accounts.hfAccount?.let { Text("Signed in as ${it.username}${it.tokenName?.let { n -> " · token \"$n\"" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                SecretField(
                    label = "Access token",
                    value = hfToken,
                    onValueChange = { hfToken = it },
                    hasSavedValue = accounts.hasHfToken,
                    onClear = { hfToken = ""; viewModel.removeHfToken() },
                    supporting = "Used for Inference Providers, gated models and higher rate limits. Needs the \"Inference Providers\" permission.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.saveHfToken(hfToken); hfToken = "" }, enabled = hfToken.isNotBlank()) { Text("Validate & save") }
                    if (accounts.hasHfToken) OutlinedButton(onClick = viewModel::validateHf) { Text("Check") }
                    TextButton(onClick = { ShareHelper.openUrl(context, "https://huggingface.co/settings/tokens") }) { Text("Create token") }
                }
                Spacer(Modifier.size(4.dp))
                Text("Civitai", style = MaterialTheme.typography.titleSmall)
                SecretField(
                    label = "API key",
                    value = civitaiKey,
                    onValueChange = { civitaiKey = it },
                    hasSavedValue = accounts.hasCivitaiKey,
                    onClear = { civitaiKey = ""; viewModel.removeCivitaiKey() },
                    supporting = "Required for many downloads and for hash lookups of some models.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.saveCivitaiKey(civitaiKey); civitaiKey = "" }, enabled = civitaiKey.isNotBlank()) { Text("Save") }
                    TextButton(onClick = { ShareHelper.openUrl(context, "https://civitai.com/user/account") }) { Text("Get a key") }
                }
            }

            SectionCard(title = "Generation defaults", icon = Icons.Default.Tune, collapsible = true, initiallyExpanded = false) {
                val d = settings.defaults
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Width", d.width, 256..2048, { v -> viewModel.update { it.copy(defaults = it.defaults.copy(width = v)) } }, Modifier.weight(1f))
                    NumberField("Height", d.height, 256..2048, { v -> viewModel.update { it.copy(defaults = it.defaults.copy(height = v)) } }, Modifier.weight(1f))
                }
                LabeledSlider("Steps", d.steps.toFloat(), { v -> viewModel.update { it.copy(defaults = it.defaults.copy(steps = v.toInt())) } }, 1f..100f, "${d.steps}", steps = 98)
                LabeledSlider("CFG scale", d.cfgScale, { v -> viewModel.update { it.copy(defaults = it.defaults.copy(cfgScale = (v * 2).toInt() / 2f)) } }, 1f..20f, Formatters.decimal(d.cfgScale, 1), steps = 37)
                LabeledSlider("Batch size", d.batchSize.toFloat(), { v -> viewModel.update { it.copy(defaults = it.defaults.copy(batchSize = v.toInt())) } }, 1f..4f, "${d.batchSize}", steps = 2)
                OutlinedTextField(d.negativePrompt, { v -> viewModel.update { it.copy(defaults = it.defaults.copy(negativePrompt = v)) } }, label = { Text("Default negative prompt") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.sampler.orEmpty(), { v -> viewModel.update { it.copy(defaults = it.defaults.copy(sampler = v.ifBlank { null })) } }, label = { Text("Default sampler (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Applied when you tap \"Defaults\" on the Generate screen and to new sessions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard(title = "Storage & export", icon = Icons.Default.SaveAlt) {
                SaveLocation.entries.forEach { loc ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (loc == SaveLocation.CUSTOM_FOLDER) pickFolder.launch(null) else viewModel.update { it.copy(saveLocation = loc) }
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.saveLocation == loc, onClick = {
                            if (loc == SaveLocation.CUSTOM_FOLDER) pickFolder.launch(null) else viewModel.update { it.copy(saveLocation = loc) }
                        })
                        Column {
                            Text(loc.label)
                            Text(
                                if (loc == SaveLocation.CUSTOM_FOLDER && settings.customFolderUri != null) settings.customFolderUri!!.substringAfterLast("%3A").replace("%2F", "/") else loc.description,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                ToggleRow("Save every result to the device automatically", settings.autoSaveToDevice) { v -> viewModel.update { it.copy(autoSaveToDevice = v) } }
                ToggleRow("Embed generation parameters in exported PNGs", settings.embedMetadata) { v -> viewModel.update { it.copy(embedMetadata = v) } }
                storage?.let { s ->
                    Text(
                        "Images ${Formatters.bytes(s.imagesBytes)} · Models ${Formatters.bytes(s.modelsBytes)} · Cache ${Formatters.bytes(s.cacheBytes)} · Free ${Formatters.bytes(s.freeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::clearCache) { Text("Clear cache") }
                    TextButton(onClick = { viewModel.refreshStorage() }) { Text("Recalculate") }
                }
            }

            SectionCard(title = "Downloads", icon = Icons.Default.Download, collapsible = true, initiallyExpanded = false) {
                ToggleRow("Download models on Wi-Fi only", settings.downloadOnWifiOnly) { v -> viewModel.update { it.copy(downloadOnWifiOnly = v) } }
                OutlinedButton(onClick = onOpenDownloads) { Text("Manage downloads") }
            }

            SectionCard(title = "Network", icon = Icons.Default.NetworkCheck, collapsible = true, initiallyExpanded = false) {
                LabeledSlider("Connect timeout", settings.connectTimeoutSec.toFloat(), { v -> viewModel.update { it.copy(connectTimeoutSec = v.toInt()) } }, 5f..60f, "${settings.connectTimeoutSec}s", steps = 10)
                LabeledSlider(
                    "Generation timeout", settings.readTimeoutSec.toFloat(), { v -> viewModel.update { it.copy(readTimeoutSec = (v / 30).toInt() * 30) } }, 60f..1800f, Formatters.duration(settings.readTimeoutSec * 1000L),
                    steps = 57, supporting = "WebUI's txt2img request stays open until all images are done — raise this for large batches or hires fix.",
                )
                LabeledSlider("Retries for transient errors", settings.maxRetries.toFloat(), { v -> viewModel.update { it.copy(maxRetries = v.toInt()) } }, 1f..6f, "${settings.maxRetries}", steps = 4)
            }

            SectionCard(title = "Appearance", icon = Icons.Default.Palette, collapsible = true, initiallyExpanded = false) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { mode -> FilterChip(selected = settings.themeMode == mode, onClick = { viewModel.update { it.copy(themeMode = mode) } }, label = { Text(mode.label) }) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ToggleRow("Use wallpaper colours (Material You)", settings.dynamicColor) { v -> viewModel.update { it.copy(dynamicColor = v) } }
                }
                LabeledSlider(
                    "Gallery columns", settings.galleryColumns.toFloat(), { v -> viewModel.update { it.copy(galleryColumns = v.toInt()) } }, 0f..6f,
                    if (settings.galleryColumns == 0) "Auto" else "${settings.galleryColumns}", steps = 5,
                )
            }

            SectionCard(title = "Content", icon = Icons.Default.Shield, collapsible = true, initiallyExpanded = false) {
                ToggleRow("Show NSFW previews in the model browser", settings.showNsfwPreviews) { v -> viewModel.update { it.copy(showNsfwPreviews = v) } }
                InfoBanner(
                    "The app doesn't filter prompts or outputs. What you can generate is determined by the models and providers you connect — hosted providers may apply their own policies. You are responsible for complying with applicable laws and model licenses.",
                    tone = BannerTone.INFO,
                )
            }

            SectionCard(title = "About", icon = Icons.Default.Info, collapsible = true, initiallyExpanded = false) {
                Text("Unfiltered AI Studio ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text("No analytics or telemetry. Prompts, images and credentials are only sent to the providers you configure.", style = MaterialTheme.typography.bodySmall)
                Text("Image metadata uses the AUTOMATIC1111 \"parameters\" format for compatibility with PNG-info tools.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (chooseType) {
        ProviderTypePickerDialog(onDismiss = { chooseType = false }, onPick = { chooseType = false; onAddProvider(it) })
    }
}

/** Lets the user pick which kind of backend to add; shared by Settings and the Generate empty state. */
@Composable
fun ProviderTypePickerDialog(onDismiss: () -> Unit, onPick: (ProviderType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProviderType.entries.forEach { t ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, onClick = { onPick(t) }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(t.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(t.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ToggleRow(label: String, checked: Boolean, supporting: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun NumberField(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in range } == true
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        isError = !valid,
        supportingText = if (!valid) ({ Text("${range.first}–${range.last}") }) else null,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
