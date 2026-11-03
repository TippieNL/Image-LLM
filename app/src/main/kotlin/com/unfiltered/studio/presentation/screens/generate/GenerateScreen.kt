package com.unfiltered.studio.presentation.screens.generate

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.domain.models.ActiveLora
import com.unfiltered.studio.domain.models.CompatibilityRules
import com.unfiltered.studio.domain.models.Compatibility
import com.unfiltered.studio.domain.models.ConnectionState
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraSupport
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.PromptEntry
import com.unfiltered.studio.domain.models.PromptTemplate
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderStatus
import com.unfiltered.studio.domain.usecases.GenerationPreparer
import com.unfiltered.studio.presentation.components.ArchitecturePill
import com.unfiltered.studio.presentation.components.BannerTone
import com.unfiltered.studio.presentation.components.EmptyState
import com.unfiltered.studio.presentation.components.InfoBanner
import com.unfiltered.studio.presentation.components.Pill
import com.unfiltered.studio.presentation.components.PreviewImage
import com.unfiltered.studio.presentation.components.StatusDot
import com.unfiltered.studio.presentation.components.TextInputDialog
import com.unfiltered.studio.presentation.theme.LocalExtendedColors

@Composable
fun GenerateScreen(
    onOpenImage: (String) -> Unit,
    onOpenGeneration: (String) -> Unit,
    onManageProviders: () -> Unit,
    onAddProvider: () -> Unit,
    onBrowseModels: () -> Unit,
    onOpenLoraLibrary: () -> Unit,
    viewModel: GenerateViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val params by viewModel.params.collectAsStateWithLifecycle()
    val caps by viewModel.capabilities.collectAsStateWithLifecycle()
    val provider by viewModel.activeProvider.collectAsStateWithLifecycle()
    val providers by viewModel.providerList.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val online by viewModel.isOnline.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var showLoraPicker by rememberSaveable { mutableStateOf(false) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setInitImage) }

    val running = active.firstOrNull { it.status == GenerationStatus.RUNNING }
    val queued = active.count { it.status == GenerationStatus.QUEUED }
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }

    fun generate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.generate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generate") },
                actions = {
                    ProviderSwitcher(provider, providers, statuses, onSelect = viewModel::selectProvider, onManage = onManageProviders, onAdd = onAddProvider, onTest = viewModel::testConnection)
                    IconButton(onClick = { showLibrary = true }) { Icon(Icons.Default.Bookmarks, "Prompt library") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (providers.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = "Connect a GPU backend",
                    message = "Add ComfyUI, Stable Diffusion WebUI/Forge, Hugging Face or any compatible API to start generating.",
                    action = { Button(onClick = onAddProvider) { Text("Add provider") } },
                )
            }
            return@Scaffold
        }
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val preview: @Composable (Modifier) -> Unit = { m ->
                PreviewCard(
                    running = running,
                    live = running?.let { live[it.id] },
                    queuedCount = queued,
                    latest = latest,
                    cancelHint = caps.cancellation.description,
                    onCancel = viewModel::cancel,
                    onOpenImage = onOpenImage,
                    onRetry = viewModel::retryLatest,
                    onVariation = viewModel::variation,
                    onOpenGeneration = onOpenGeneration,
                    modifier = m,
                )
            }
            val controls: @Composable () -> Unit = {
                if (!online) InfoBanner("You're offline. Jobs will wait until a network is available (LAN servers work on Wi-Fi without internet).", tone = BannerTone.WARNING)
                statuses[provider?.id]?.takeIf { it.state == ConnectionState.ERROR }?.let { s ->
                    InfoBanner(s.error ?: "Provider unreachable", tone = BannerTone.ERROR, action = { TextButton(onClick = viewModel::testConnection) { Text("Retry") } })
                }
                PromptCard(
                    params = params,
                    caps = caps,
                    stack = stack,
                    embeddings = catalog?.embeddings.orEmpty(),
                    onPrompt = viewModel::setPrompt,
                    onNegative = viewModel::setNegative,
                    onInsert = viewModel::insertText,
                    onCopy = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("prompt", params.prompt))
                    },
                    onPaste = {
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text ->
                            viewModel.setPrompt(if (params.prompt.isBlank()) text else params.prompt.trimEnd() + " " + text)
                        }
                    },
                    onRandom = viewModel::randomPrompt,
                    onFavorite = viewModel::saveFavorite,
                    onOpenLibrary = { showLibrary = true },
                )
                ModelCard(
                    provider = provider,
                    params = params,
                    caps = caps,
                    catalog = catalog,
                    catalogLoading = ui.catalogLoading,
                    catalogError = ui.catalogError,
                    onCheckpoint = { viewModel.setCheckpoint(it) },
                    onArchitecture = { viewModel.setArchitecture(it) },
                    onVae = viewModel::setVae,
                    onComponent = viewModel::setComponent,
                    onRefresh = { viewModel.refreshCatalog() },
                    onBrowse = onBrowseModels,
                )
                LoraStackCard(
                    provider = provider,
                    stack = stack,
                    params = params,
                    caps = caps,
                    catalog = catalog,
                    showNsfw = settings.showNsfwPreviews,
                    onAdd = { showLoraPicker = true },
                    onStrength = viewModel::setLoraStrength,
                    onClipStrength = viewModel::setLoraClipStrength,
                    onEnabled = viewModel::setLoraEnabled,
                    onRemove = viewModel::removeLora,
                    onAutoTrigger = viewModel::setAutoTrigger,
                    onSync = viewModel::syncLorasFromServer,
                )
                SettingsCard(
                    params = params,
                    caps = caps,
                    aspect = ui.aspect,
                    onAspect = viewModel::setAspect,
                    onWidth = viewModel::setWidth,
                    onHeight = viewModel::setHeight,
                    onSwap = viewModel::swapDimensions,
                    onSteps = viewModel::setSteps,
                    onCfg = viewModel::setCfg,
                    onGuidance = viewModel::setGuidance,
                    onSeedText = viewModel::setSeedText,
                    onRandomSeed = viewModel::randomizeSeed,
                    onReuseSeed = viewModel::reuseLastSeed,
                    onBatch = viewModel::setBatch,
                    onReset = { viewModel.resetToDefaults() },
                )
                AdvancedCard(
                    params = params,
                    caps = caps,
                    importingImage = ui.importingImage,
                    onSampler = viewModel::setSampler,
                    onScheduler = viewModel::setScheduler,
                    onClipSkip = viewModel::setClipSkip,
                    onHires = { enabled -> viewModel.setHires { it.copy(enabled = enabled) } },
                    onHiresScale = { v -> viewModel.setHires { it.copy(scale = (v * 20).toInt() / 20f) } },
                    onHiresSteps = { v -> viewModel.setHires { it.copy(steps = v) } },
                    onHiresDenoise = { v -> viewModel.setHires { it.copy(denoise = (v * 100).toInt() / 100f) } },
                    onHiresUpscaler = { v -> viewModel.setHires { it.copy(upscaler = v) } },
                    onPickInit = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onClearInit = viewModel::clearInitImage,
                    onDenoise = { viewModel.setDenoise((it * 100).toInt() / 100f) },
                )
                caps.notes.forEach { InfoBanner(it) }
                Spacer(Modifier.height(96.dp))
            }

            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(0.55f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { controls() }
                    Column(Modifier.weight(0.45f).padding(vertical = 8.dp)) { preview(Modifier) }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    preview(Modifier)
                    controls()
                }
            }

            GenerateBar(
                enqueuing = ui.enqueuing,
                running = running != null,
                queued = queued,
                promptEmpty = params.prompt.isBlank(),
                batch = params.batchSize,
                onGenerate = ::generate,
                onCancelAll = viewModel::cancelAll,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showLibrary) {
        PromptLibrarySheet(
            viewModel = viewModel,
            onDismiss = { showLibrary = false },
        )
    }
    if (showLoraPicker) {
        LoraPickerSheet(
            library = viewModel.library.collectAsStateWithLifecycle().value,
            stack = stack,
            provider = provider,
            catalog = catalog,
            loraSupport = caps.loras,
            architecture = params.architecture,
            showNsfw = settings.showNsfwPreviews,
            onToggle = viewModel::toggleLora,
            onSync = viewModel::syncLorasFromServer,
            onManage = { showLoraPicker = false; onOpenLoraLibrary() },
            onDismiss = { showLoraPicker = false },
        )
    }
    ui.preflight?.let { prepared ->
        var dontAsk by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = viewModel::dismissPreflight,
            title = { Text("Before generating") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    prepared.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { dontAsk = !dontAsk }) {
                        Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                        Text("Don't ask again this session")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmPreflight(dontAsk) }) { Text("Generate anyway") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPreflight) { Text("Cancel") } },
        )
    }
    if (ui.errors.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissErrors,
            title = { Text("Can't generate yet") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { ui.errors.forEach { Text("• $it") } } },
            confirmButton = { TextButton(onClick = viewModel::dismissErrors) { Text("OK") } },
        )
    }
}

@Composable
private fun ProviderSwitcher(
    provider: ProviderConfig?,
    providers: List<ProviderConfig>,
    statuses: Map<String, ProviderStatus>,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    onTest: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(statuses[provider?.id]?.state ?: ConnectionState.UNKNOWN)
                Spacer(Modifier.width(8.dp))
                Text(provider?.name ?: "No provider", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                Icon(Icons.Default.ExpandMore, "Switch provider")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            providers.forEach { p ->
                val status = statuses[p.id]
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.name)
                            Text(
                                listOfNotNull(p.type.displayName, status?.info?.serverName?.takeIf { status.state == ConnectionState.ONLINE }, status?.error?.take(60)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    },
                    leadingIcon = { StatusDot(status?.state ?: ConnectionState.UNKNOWN) },
                    onClick = { onSelect(p.id); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Test connection") }, onClick = { onTest(); open = false })
            DropdownMenuItem(text = { Text("Manage providers") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { onManage(); open = false })
            DropdownMenuItem(text = { Text("Add provider") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { onAdd(); open = false })
        }
    }
}

@Composable
private fun GenerateBar(
    enqueuing: Boolean,
    running: Boolean,
    queued: Int,
    promptEmpty: Boolean,
    batch: Int,
    onGenerate: () -> Unit,
    onCancelAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalExtendedColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running || queued > 0) {
                OutlinedButton(onClick = onCancelAll) { Text("Stop all") }
                Spacer(Modifier.width(10.dp))
            }
            Button(
                onClick = onGenerate,
                enabled = !enqueuing && !promptEmpty,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .then(if (!enqueuing && !promptEmpty) Modifier.background(ext.brandGradient) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (enqueuing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        else Icon(Icons.Default.AutoAwesome, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                running || queued > 0 -> "Add to queue"
                                batch > 1 -> "Generate $batch"
                                else -> "Generate"
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Prompt library sheet
// ---------------------------------------------------------------------------------------------

@Composable
private fun PromptLibrarySheet(viewModel: GenerateViewModel, onDismiss: () -> Unit) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var saveTemplate by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("History", "Favorites", "Templates").forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) }
        }
        LazyColumn(Modifier.heightIn(max = 600.dp), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> {
                    if (history.isEmpty()) item { Text("Prompts you generate with appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                    else item { TextButton(onClick = viewModel::clearPromptHistory) { Text("Clear history (keeps favorites)") } }
                    items(history, key = { it.id }) { entry -> PromptRow(entry, viewModel, onDismiss) }
                }
                1 -> {
                    if (favorites.isEmpty()) item { Text("Star prompts to keep them here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                    items(favorites, key = { it.id }) { entry -> PromptRow(entry, viewModel, onDismiss) }
                }
                else -> {
                    item { OutlinedButton(onClick = { saveTemplate = true }) { Text("Save current prompt as template") } }
                    items(templates, key = { it.id }) { template -> TemplateRow(template, viewModel, onDismiss) }
                }
            }
        }
    }
    if (saveTemplate) {
        TextInputDialog(title = "Save template", label = "Template name", onConfirm = viewModel::saveTemplate, onDismiss = { saveTemplate = false })
    }
}

@Composable
private fun PromptRow(entry: PromptEntry, viewModel: GenerateViewModel, onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, onClick = { viewModel.loadPrompt(entry); onDismiss() }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                entry.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(entry.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (entry.negativePrompt.isNotBlank()) Text("– ${entry.negativePrompt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Used ${entry.useCount}× · ${Formatters.relative(entry.lastUsedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { viewModel.setPromptFavorite(entry.id, !entry.isFavorite) }) {
                Icon(if (entry.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, if (entry.isFavorite) "Unfavorite" else "Favorite", tint = if (entry.isFavorite) LocalExtendedColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { viewModel.deletePrompt(entry.id) }) { Icon(Icons.Default.Delete, "Delete") }
        }
    }
}

@Composable
private fun TemplateRow(template: PromptTemplate, viewModel: GenerateViewModel, onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, onClick = { viewModel.applyTemplate(template); onDismiss() }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(template.name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Pill(template.category)
                }
                Text(template.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!template.builtIn) IconButton(onClick = { viewModel.deleteTemplate(template.id) }) { Icon(Icons.Default.Delete, "Delete template") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// LoRA picker sheet
// ---------------------------------------------------------------------------------------------

@Composable
private fun LoraPickerSheet(
    library: List<Lora>,
    stack: List<ActiveLora>,
    provider: ProviderConfig?,
    catalog: com.unfiltered.studio.domain.models.BackendCatalog?,
    loraSupport: LoraSupport,
    architecture: ModelArchitecture,
    showNsfw: Boolean,
    onToggle: (String) -> Unit,
    onSync: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var compatibleOnly by rememberSaveable { mutableStateOf(true) }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    val ext = LocalExtendedColors.current
    fun available(l: Lora) = when (loraSupport) {
        LoraSupport.PromptSyntax, LoraSupport.ComfyNodes -> l.isOnServer(provider?.id) || GenerationPreparer.matchOnServer(l, catalog) != null
        is LoraSupport.RemoteWeights -> l.hasRemoteWeights
        LoraSupport.TemplateVariable -> true
        LoraSupport.None -> false
    }
    val filtered = library.filter { l ->
        (query.isBlank() || l.name.contains(query, true) || l.triggerWords.any { it.contains(query, true) }) &&
            (!compatibleOnly || CompatibilityRules.loraOnBase(l.architecture, architecture) != Compatibility.INCOMPATIBLE) &&
            (!availableOnly || available(l))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add LoRAs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (loraSupport == LoraSupport.PromptSyntax || loraSupport == LoraSupport.ComfyNodes) TextButton(onClick = onSync) { Text("Sync from server") }
                TextButton(onClick = onManage) { Text("Library") }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search name or trigger word") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = compatibleOnly, onClick = { compatibleOnly = !compatibleOnly }, label = { Text("Hide incompatible") })
                FilterChip(selected = availableOnly, onClick = { availableOnly = !availableOnly }, label = { Text("Usable on ${provider?.name ?: "provider"}") })
            }
        }
        LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (library.isEmpty()) "Your LoRA library is empty. Sync from your server, import a file or add LoRAs from the Models tab."
                        else "No LoRAs match the filters.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filtered, key = { it.id }) { lora ->
                val inStack = stack.any { it.lora.id == lora.id }
                val compat = CompatibilityRules.loraOnBase(lora.architecture, architecture)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (inStack) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { onToggle(lora.id) },
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PreviewImage(lora.previewImageUrl, lora.nsfw, showNsfw, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lora.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ArchitecturePill(lora.architecture)
                                if (compat == Compatibility.INCOMPATIBLE) Pill("Incompatible", color = MaterialTheme.colorScheme.error)
                                if (compat == Compatibility.PARTIAL) Pill("Partial", color = ext.warning)
                                if (!available(lora)) Pill("Unavailable here", color = MaterialTheme.colorScheme.outline, icon = Icons.Default.CloudOff)
                            }
                        }
                        Checkbox(checked = inStack, onCheckedChange = { onToggle(lora.id) })
                    }
                }
            }
        }
    }
}
