package com.unfiltered.studio.presentation.screens.generate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.domain.models.ActiveLora
import com.unfiltered.studio.domain.models.AspectRatioPreset
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.BatchSupport
import com.unfiltered.studio.domain.models.CheckpointSelection
import com.unfiltered.studio.domain.models.Compatibility
import com.unfiltered.studio.domain.models.CompatibilityRules
import com.unfiltered.studio.domain.models.Generation
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.domain.models.LiveProgress
import com.unfiltered.studio.domain.models.LoraSupport
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ProviderCapabilities
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.usecases.GenerationPreparer
import com.unfiltered.studio.presentation.components.ArchitecturePill
import com.unfiltered.studio.presentation.components.BannerTone
import com.unfiltered.studio.presentation.components.ErrorPanel
import com.unfiltered.studio.presentation.components.InfoBanner
import com.unfiltered.studio.presentation.components.LabeledSlider
import com.unfiltered.studio.presentation.components.Pill
import com.unfiltered.studio.presentation.components.PickerSheet
import com.unfiltered.studio.presentation.components.PreviewImage
import com.unfiltered.studio.presentation.components.SectionCard
import com.unfiltered.studio.presentation.components.SelectField
import com.unfiltered.studio.presentation.theme.LocalExtendedColors
import kotlinx.coroutines.delay
import java.io.File

// ---------------------------------------------------------------------------------------------
// Preview / progress
// ---------------------------------------------------------------------------------------------

@Composable
fun PreviewCard(
    running: Generation?,
    live: LiveProgress?,
    queuedCount: Int,
    latest: Generation?,
    cancelHint: String,
    onCancel: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onRetry: () -> Unit,
    onVariation: () -> Unit,
    onOpenGeneration: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalExtendedColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(previewAspect(running ?: latest))
                .heightIn(max = 560.dp),
        ) {
            val preview = live?.preview
            val latestImage = latest?.images?.firstOrNull()
            when {
                running != null && preview != null -> AsyncImage(model = preview, contentDescription = "Live preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                running != null -> ShimmerPlaceholder(Modifier.fillMaxSize())
                latest?.status == GenerationStatus.SUCCEEDED && latestImage != null -> {
                    Crossfade(latestImage.filePath, label = "result") { path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = "Latest result",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().clickable { onOpenImage(latestImage.id) },
                        )
                    }
                }
                else -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(ext.brandGradient),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Text("Your images appear here", style = MaterialTheme.typography.titleSmall)
                    Text("Write a prompt and tap Generate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (running != null) {
                ProgressOverlay(running, live, queuedCount, cancelHint, onCancel, Modifier.align(Alignment.BottomCenter))
            } else if (queuedCount > 0) {
                Pill("$queuedCount queued", Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        }

        if (running == null && latest != null) {
            when (latest.status) {
                GenerationStatus.FAILED -> ErrorPanel(
                    message = latest.error ?: "Generation failed",
                    details = latest.errorDetails,
                    onRetry = onRetry,
                    modifier = Modifier.padding(12.dp),
                )
                GenerationStatus.CANCELLED -> InfoBanner("Last generation was cancelled.", Modifier.padding(12.dp))
                else -> Unit
            }
            if (latest.status == GenerationStatus.SUCCEEDED) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        latest.images.drop(1).forEach { image ->
                            AsyncImage(
                                model = File(image.thumbnailPath),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpenImage(image.id) },
                            )
                        }
                        if (latest.images.size <= 1) {
                            Text(
                                buildString {
                                    append(Formatters.duration(latest.durationMs))
                                    latest.images.firstOrNull()?.seed?.let { append(" · seed $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onVariation) { Icon(Icons.Default.Casino, "Variation (new seed)") }
                    IconButton(onClick = onRetry) { Icon(Icons.Default.Replay, "Run again with the same seed") }
                    TextButton(onClick = { onOpenGeneration(latest.id) }) { Text("Details") }
                }
                if (latest.warnings.isNotEmpty()) {
                    InfoBanner(latest.warnings.first() + if (latest.warnings.size > 1) " (+${latest.warnings.size - 1} more)" else "", Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), tone = BannerTone.WARNING)
                }
            }
        }
    }
}

private fun previewAspect(g: Generation?): Float {
    val p = g?.params ?: return 1f
    return (p.width.toFloat() / p.height.coerceAtLeast(1)).coerceIn(0.56f, 1.78f)
}

@Composable
private fun ProgressOverlay(running: Generation, live: LiveProgress?, queuedCount: Int, cancelHint: String, onCancel: (String) -> Unit, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = now - (running.startedAt ?: live?.startedAt ?: now)
    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(live?.phase ?: "Starting…", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildList {
                            if (live?.step != null && live.totalSteps != null) add("Step ${live.step}/${live.totalSteps}")
                            live?.etaSeconds?.let { add("ETA ${it.toInt()}s") }
                            add(Formatters.duration(elapsed))
                            if (queuedCount > 0) add("$queuedCount queued")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { onCancel(running.id) }) { Text("Cancel") }
            }
            val fraction = live?.fraction
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(cancelHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShimmerPlaceholder(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "shift")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    Box(modifier.background(Brush.linearGradient(listOf(base, highlight, base), start = androidx.compose.ui.geometry.Offset(shift * 900f, 0f), end = androidx.compose.ui.geometry.Offset(shift * 900f + 600f, 600f))))
}

// ---------------------------------------------------------------------------------------------
// Prompt
// ---------------------------------------------------------------------------------------------

@Composable
fun PromptCard(
    params: GenerationParameters,
    caps: ProviderCapabilities,
    stack: List<ActiveLora>,
    embeddings: List<String>,
    onPrompt: (String) -> Unit,
    onNegative: (String) -> Unit,
    onInsert: (String) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onRandom: () -> Unit,
    onFavorite: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    SectionCard(title = "Prompt", icon = Icons.Default.AutoAwesome) {
        OutlinedTextField(
            value = params.prompt,
            onValueChange = onPrompt,
            placeholder = { Text("Describe the image… e.g. a cinematic portrait of a sea captain, rim light, 85mm") },
            minLines = 3,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = if (params.prompt.isNotEmpty()) ({ IconButton(onClick = { onPrompt("") }) { Icon(Icons.Default.Close, "Clear prompt") } }) else null,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = onOpenLibrary, label = { Text("Library") }, leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onRandom, label = { Text("Random") }, leadingIcon = { Icon(Icons.Default.Casino, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onFavorite, label = { Text("Save") }, leadingIcon = { Icon(Icons.Default.StarBorder, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onCopy, label = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onPaste, label = { Text("Paste") }, leadingIcon = { Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)) })
        }
        val triggers = stack.filter { it.enabled }.flatMap { it.lora.triggerWords }.distinct().filter { !params.prompt.contains(it, ignoreCase = true) }
        if (triggers.isNotEmpty()) {
            Text("LoRA trigger words — tap to insert", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                triggers.take(12).forEach { word ->
                    AssistChip(onClick = { onInsert(word) }, label = { Text(word, maxLines = 1) }, colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)))
                }
            }
        }
        if (embeddings.isNotEmpty()) {
            var showEmbeddings by remember { mutableStateOf(false) }
            TextButton(onClick = { showEmbeddings = true }) { Text("Insert embedding (${embeddings.size})") }
            if (showEmbeddings) {
                PickerSheet(
                    title = "Textual inversion embeddings",
                    options = embeddings,
                    selected = null,
                    itemLabel = { it },
                    onSelect = { onInsert(it); showEmbeddings = false },
                    onDismiss = { showEmbeddings = false },
                )
            }
        }
        if (caps.negativePrompt) {
            OutlinedTextField(
                value = params.negativePrompt,
                onValueChange = onNegative,
                label = { Text("Negative prompt") },
                placeholder = { Text("What to avoid… e.g. blurry, extra fingers, watermark") },
                minLines = 1,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!params.architecture.usesNegativePrompt) {
                Text("${params.architecture.displayName} mostly ignores negative prompts at CFG 1.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Model selection
// ---------------------------------------------------------------------------------------------

@Composable
fun ModelCard(
    provider: ProviderConfig?,
    params: GenerationParameters,
    caps: ProviderCapabilities,
    catalog: BackendCatalog?,
    catalogLoading: Boolean,
    catalogError: String?,
    onCheckpoint: (String?) -> Unit,
    onArchitecture: (ModelArchitecture) -> Unit,
    onVae: (String?) -> Unit,
    onComponent: (String, String?) -> Unit,
    onRefresh: () -> Unit,
    onBrowse: () -> Unit,
) {
    SectionCard(
        title = "Model",
        subtitle = catalog?.serverLabel?.let { "$it · updated ${Formatters.relative(catalog.fetchedAt)}" } ?: provider?.name,
        icon = Icons.Default.ViewInAr,
        actions = {
            if (catalogLoading) CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh model list") }
        },
    ) {
        if (catalogError != null) InfoBanner(catalogError, tone = BannerTone.ERROR)
        when (val selection = caps.checkpointSelection) {
            is CheckpointSelection.FromList -> {
                val byName = catalog?.checkpoints?.associateBy { it.name }.orEmpty()
                val options = buildList {
                    if (selection.allowEmpty) add("")
                    addAll(selection.options)
                    params.checkpoint?.let { if (it !in selection.options) add(it) }
                }.distinct()
                SelectField(
                    label = "Checkpoint",
                    options = options,
                    selected = params.checkpoint ?: if (selection.allowEmpty) "" else null,
                    onSelect = { onCheckpoint(it.ifBlank { null }) },
                    itemLabel = { if (it.isBlank()) "Server's current model" else (byName[it]?.displayName ?: it.substringAfterLast('/').substringAfterLast('\\')) },
                    itemSupporting = { name -> byName[name]?.architecture?.displayName },
                    placeholder = if (selection.options.isEmpty()) "Refresh to load models" else "Select a checkpoint",
                    supporting = if (selection.options.isEmpty() && !catalogLoading) "No checkpoints listed yet — tap refresh." else null,
                )
            }
            is CheckpointSelection.FreeText -> {
                var showSuggestions by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = params.checkpoint.orEmpty(),
                    onValueChange = { onCheckpoint(it) },
                    label = { Text("Model") },
                    placeholder = { Text(selection.hint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    supportingText = catalog?.hfModel?.takeIf { it.modelId == params.checkpoint }?.let { info ->
                        { Text(info.mappings.filter { it.isLive }.joinToString { it.provider }.ifBlank { "No live providers" }.let { "Providers: $it" }) }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selection.suggestions.isNotEmpty()) OutlinedButton(onClick = { showSuggestions = true }) { Text("Popular models") }
                    OutlinedButton(onClick = onBrowse) { Text("Browse") }
                }
                if (showSuggestions) {
                    PickerSheet(
                        title = "Models with live inference",
                        options = selection.suggestions,
                        selected = params.checkpoint,
                        itemLabel = { it },
                        onSelect = { onCheckpoint(it); showSuggestions = false },
                        onDismiss = { showSuggestions = false },
                    )
                }
            }
            CheckpointSelection.None -> Unit
        }

        SelectField(
            label = "Architecture",
            options = ModelArchitecture.entries.toList(),
            selected = params.architecture,
            onSelect = onArchitecture,
            itemLabel = { it.displayName },
            itemSupporting = { if (it.builtInComfySupport || provider?.type?.name != "COMFYUI") null else "Use a custom workflow on ComfyUI" },
            supporting = "Drives defaults, LoRA compatibility checks and which settings apply.",
        )

        caps.vaes?.let { vaes ->
            SelectField(
                label = "VAE",
                options = vaes,
                selected = params.vae ?: vaes.firstOrNull(),
                onSelect = { onVae(it.ifBlank { null }.takeUnless { v -> v == "Automatic" }) },
                itemLabel = { it.ifBlank { "From checkpoint" } },
            )
        }
        caps.components.forEach { slot ->
            if (slot.multiple) {
                MultiComponentField(slot.label, slot.options, params.components[slot.id]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()) { selected ->
                    onComponent(slot.id, selected.joinToString("\n").ifBlank { null })
                }
            } else {
                SelectField(
                    label = slot.label + if (slot.required) " *" else "",
                    options = slot.options,
                    selected = params.components[slot.id],
                    onSelect = { onComponent(slot.id, it) },
                    itemLabel = { it.substringAfterLast('/').substringAfterLast('\\') },
                    placeholder = if (slot.options.isEmpty()) "Refresh to load files" else "Select…",
                )
            }
        }
    }
}

@Composable
private fun MultiComponentField(label: String, options: List<String>, selected: List<String>, onChange: (List<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            selected.forEach { item ->
                AssistChip(onClick = { onChange(selected - item) }, label = { Text(item.substringAfterLast('/').substringAfterLast('\\'), maxLines = 1) }, trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) })
            }
            AssistChip(onClick = { open = true }, enabled = options.isNotEmpty(), label = { Text(if (options.isEmpty()) "No files listed" else "Add") })
        }
    }
    if (open) {
        PickerSheet(
            title = label,
            options = options.filter { it !in selected },
            selected = null,
            itemLabel = { it.substringAfterLast('/').substringAfterLast('\\') },
            onSelect = { onChange(selected + it); open = false },
            onDismiss = { open = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// LoRA stack
// ---------------------------------------------------------------------------------------------

@Composable
fun LoraStackCard(
    provider: ProviderConfig?,
    stack: List<ActiveLora>,
    params: GenerationParameters,
    caps: ProviderCapabilities,
    catalog: BackendCatalog?,
    showNsfw: Boolean,
    onAdd: () -> Unit,
    onStrength: (String, Float) -> Unit,
    onClipStrength: (String, Float) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onAutoTrigger: (Boolean) -> Unit,
    onSync: () -> Unit,
) {
    val ext = LocalExtendedColors.current
    SectionCard(
        title = "LoRAs",
        subtitle = when (val s = caps.loras) {
            LoraSupport.None -> "Not supported by ${provider?.name ?: "this provider"} with the current model/workflow"
            LoraSupport.PromptSyntax -> "Applied as <lora:name:weight> — files must be on the server"
            LoraSupport.ComfyNodes -> "Applied as LoraLoader nodes — files must be on the server"
            is LoraSupport.RemoteWeights -> "Loaded from Hugging Face by the provider (max ${s.maxCount})"
            LoraSupport.TemplateVariable -> "Passed to your custom API via {{loras}}"
        },
        icon = Icons.Default.Layers,
        actions = {
            if (caps.loras == LoraSupport.PromptSyntax || caps.loras == LoraSupport.ComfyNodes) {
                IconButton(onClick = onSync) { Icon(Icons.Default.Refresh, "Sync LoRAs from server") }
            }
            FilledTonalButton(onClick = onAdd) { Text("Add") }
        },
    ) {
        if (stack.isEmpty()) {
            Text("No LoRAs selected. Add some from your library.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        stack.forEach { active ->
            val lora = active.lora
            val compat = CompatibilityRules.loraOnBase(lora.architecture, params.architecture)
            val available = when (caps.loras) {
                LoraSupport.PromptSyntax, LoraSupport.ComfyNodes -> lora.isOnServer(provider?.id) || GenerationPreparer.matchOnServer(lora, catalog) != null
                is LoraSupport.RemoteWeights -> lora.hasRemoteWeights
                LoraSupport.TemplateVariable -> true
                LoraSupport.None -> false
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PreviewImage(lora.previewImageUrl, lora.nsfw, showNsfw, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lora.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ArchitecturePill(lora.architecture)
                                when (compat) {
                                    Compatibility.INCOMPATIBLE -> Pill("Incompatible", color = MaterialTheme.colorScheme.error)
                                    Compatibility.PARTIAL -> Pill("Partial", color = ext.warning)
                                    Compatibility.UNKNOWN -> Pill("Unverified", color = MaterialTheme.colorScheme.outline)
                                    Compatibility.COMPATIBLE -> Pill("Compatible", color = ext.success)
                                }
                                if (!available) Pill("Not on server", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Switch(checked = active.enabled, onCheckedChange = { onEnabled(lora.id, it) })
                        IconButton(onClick = { onRemove(lora.id) }) { Icon(Icons.Default.Delete, "Remove ${lora.name}") }
                    }
                    LabeledSlider(
                        label = "Strength",
                        value = active.strength,
                        onValueChange = { onStrength(lora.id, it) },
                        range = -1f..2f,
                        valueText = Formatters.decimal(active.strength),
                        enabled = active.enabled,
                    )
                    if (caps.loraClipStrength) {
                        LabeledSlider(
                            label = "CLIP strength",
                            value = active.clipStrength ?: active.strength,
                            onValueChange = { onClipStrength(lora.id, it) },
                            range = -1f..2f,
                            valueText = Formatters.decimal(active.clipStrength ?: active.strength),
                            enabled = active.enabled,
                        )
                    }
                    CompatibilityRules.describe(compat, lora.architecture, params.architecture)?.takeIf { compat != Compatibility.UNKNOWN }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (compat == Compatibility.INCOMPATIBLE) MaterialTheme.colorScheme.error else ext.warning)
                    }
                }
            }
        }
        if (stack.any { it.lora.triggerWords.isNotEmpty() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = params.autoTriggerWords, onCheckedChange = onAutoTrigger)
                Text("Add missing trigger words automatically", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsCard(
    params: GenerationParameters,
    caps: ProviderCapabilities,
    aspect: AspectRatioPreset,
    onAspect: (AspectRatioPreset) -> Unit,
    onWidth: (Int) -> Unit,
    onHeight: (Int) -> Unit,
    onSwap: () -> Unit,
    onSteps: (Int) -> Unit,
    onCfg: (Float) -> Unit,
    onGuidance: (Float) -> Unit,
    onSeedText: (String) -> Unit,
    onRandomSeed: () -> Unit,
    onReuseSeed: () -> Unit,
    onBatch: (Int) -> Unit,
    onReset: () -> Unit,
) {
    SectionCard(
        title = "Generation settings",
        icon = Icons.Default.Tune,
        actions = { TextButton(onClick = onReset) { Text("Defaults") } },
    ) {
        caps.size?.let { size ->
            Text("Aspect ratio", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AspectRatioPreset.entries.forEach { preset ->
                    FilterChip(selected = aspect == preset, onClick = { onAspect(preset) }, label = { Text(preset.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${params.width} × ${params.height}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${"%.1f".format((params.width * params.height) / 1_000_000f)} MP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onSwap) { Icon(Icons.Default.SwapHoriz, "Swap width and height") }
            }
            AnimatedVisibility(aspect == AspectRatioPreset.CUSTOM) {
                Column {
                    val min = size.min.coerceAtLeast(512).toFloat()
                    val max = size.max.coerceAtMost(2048).toFloat()
                    val steps = ((max - min) / 64f).toInt() - 1
                    LabeledSlider("Width", params.width.toFloat(), { onWidth(((it / 64).toInt() * 64)) }, min..max, "${params.width}px", steps = steps.coerceAtLeast(0))
                    LabeledSlider("Height", params.height.toFloat(), { onHeight(((it / 64).toInt() * 64)) }, min..max, "${params.height}px", steps = steps.coerceAtLeast(0))
                }
            }
        }
        caps.steps?.let { range ->
            val max = range.last.coerceAtMost(100)
            LabeledSlider("Steps", params.steps.toFloat(), { onSteps(it.toInt()) }, range.first.toFloat()..max.toFloat(), "${params.steps}", steps = (max - range.first - 1).coerceAtLeast(0))
        }
        caps.cfg?.let { range ->
            val min = range.start.coerceAtLeast(1f)
            val max = range.endInclusive.coerceAtMost(20f)
            LabeledSlider("CFG scale", params.cfgScale, onCfg, min..max, Formatters.decimal(params.cfgScale, 1), steps = ((max - min) * 2).toInt() - 1)
        }
        if (caps.distilledGuidance) {
            LabeledSlider(
                "Guidance (distilled)", params.distilledGuidance, onGuidance, 0f..10f, Formatters.decimal(params.distilledGuidance, 1),
                steps = 99, supporting = "FLUX-style guidance. Typical 2.5–4.",
            )
        }
        if (caps.seed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (params.seed < 0) "" else params.seed.toString(),
                    onValueChange = onSeedText,
                    label = { Text("Seed") },
                    placeholder = { Text("Random") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRandomSeed) { Icon(Icons.Default.Casino, "Random seed") }
                IconButton(onClick = onReuseSeed) { Icon(Icons.AutoMirrored.Filled.Undo, "Reuse last seed") }
            }
        }
        if (caps.batch !is BatchSupport.None) {
            Text("Batch size" + if (caps.batch is BatchSupport.Sequential) " (one request per image)" else "", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val max = caps.batch.max.coerceIn(1, 4)
                (1..max).forEach { n ->
                    SegmentedButton(selected = params.batchSize == n, onClick = { onBatch(n) }, shape = SegmentedButtonDefaults.itemShape(n - 1, max)) { Text("$n") }
                }
            }
        }
    }
}

@Composable
fun AdvancedCard(
    params: GenerationParameters,
    caps: ProviderCapabilities,
    importingImage: Boolean,
    onSampler: (String?) -> Unit,
    onScheduler: (String?) -> Unit,
    onClipSkip: (Int) -> Unit,
    onHires: (Boolean) -> Unit,
    onHiresScale: (Float) -> Unit,
    onHiresSteps: (Int) -> Unit,
    onHiresDenoise: (Float) -> Unit,
    onHiresUpscaler: (String) -> Unit,
    onPickInit: () -> Unit,
    onClearInit: () -> Unit,
    onDenoise: (Float) -> Unit,
) {
    val hasAnything = caps.samplers.isNotEmpty() || caps.schedulers.isNotEmpty() || caps.freeformScheduler || caps.clipSkip || caps.hiresFix != null || caps.img2img
    if (!hasAnything) return
    SectionCard(title = "Advanced", icon = Icons.Default.Tune, collapsible = true, initiallyExpanded = false) {
        if (caps.samplers.isNotEmpty()) {
            SelectField("Sampler", caps.samplers, params.sampler, { onSampler(it) }, { it }, placeholder = "Server default")
        }
        if (caps.schedulers.isNotEmpty() && !caps.freeformScheduler) {
            SelectField("Scheduler", caps.schedulers, params.scheduler, { onScheduler(it) }, { it }, placeholder = "Server default")
        } else if (caps.freeformScheduler) {
            SelectField("Scheduler (diffusers)", listOf("") + caps.schedulers, params.scheduler ?: "", { onScheduler(it) }, { it.ifBlank { "Model default" } })
        }
        if (caps.clipSkip) {
            LabeledSlider("CLIP skip", params.clipSkip.toFloat(), { onClipSkip(it.toInt()) }, 1f..12f, "${params.clipSkip}", steps = 10, supporting = "1 = off. Pony/anime models usually use 2.")
        }
        caps.hiresFix?.let { hires ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hires fix", style = MaterialTheme.typography.labelLarge)
                    Text("Second pass at higher resolution", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = params.hiresFix.enabled, onCheckedChange = onHires, enabled = params.initImagePath == null)
            }
            AnimatedVisibility(params.hiresFix.enabled && params.initImagePath == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectField("Upscaler", hires.upscalers, params.hiresFix.upscaler ?: hires.upscalers.firstOrNull(), onHiresUpscaler, { it })
                    LabeledSlider("Upscale by", params.hiresFix.scale, onHiresScale, 1f..hires.maxScale.coerceAtMost(4f), "×${Formatters.decimal(params.hiresFix.scale)}", steps = 29)
                    LabeledSlider("Hires steps", params.hiresFix.steps.toFloat(), { onHiresSteps(it.toInt()) }, 0f..60f, if (params.hiresFix.steps == 0) "Same" else "${params.hiresFix.steps}", steps = 59)
                    LabeledSlider("Denoising strength", params.hiresFix.denoise, onHiresDenoise, 0f..1f, Formatters.decimal(params.hiresFix.denoise), steps = 19)
                    Text("Output: ${(params.width * params.hiresFix.scale).toInt()} × ${(params.height * params.hiresFix.scale).toInt()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (caps.img2img) {
            Text("Image-to-image", style = MaterialTheme.typography.labelLarge)
            val init = params.initImagePath
            if (init == null) {
                OutlinedButton(onClick = onPickInit, enabled = !importingImage) {
                    Icon(Icons.Default.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (importingImage) "Importing…" else "Choose init image")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = File(init),
                        contentDescription = "Init image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = onPickInit) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Change") }
                        TextButton(onClick = onClearInit) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text("Remove") }
                    }
                }
                LabeledSlider("Denoising strength", params.denoisingStrength, onDenoise, 0.05f..1f, Formatters.decimal(params.denoisingStrength), steps = 18, supporting = "Lower keeps more of the init image.")
            }
        }
    }
}
