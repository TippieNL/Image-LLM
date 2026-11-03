package com.unfiltered.studio.presentation.screens.loras

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.data.repositories.LoraRepositoryImpl
import com.unfiltered.studio.domain.models.ArchitectureFamily
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraAvailabilityFilter
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.presentation.components.ArchitecturePill
import com.unfiltered.studio.presentation.components.ConfirmDialog
import com.unfiltered.studio.presentation.components.EmptyState
import com.unfiltered.studio.presentation.components.InfoBanner
import com.unfiltered.studio.presentation.components.KeyValueRow
import com.unfiltered.studio.presentation.components.LabeledSlider
import com.unfiltered.studio.presentation.components.Pill
import com.unfiltered.studio.presentation.components.PickerSheet
import com.unfiltered.studio.presentation.components.PreviewImage
import com.unfiltered.studio.presentation.components.SectionCard
import com.unfiltered.studio.presentation.components.SelectField
import com.unfiltered.studio.presentation.components.ShareHelper
import com.unfiltered.studio.presentation.components.TextInputDialog
import com.unfiltered.studio.presentation.theme.LocalExtendedColors

@Composable
fun LoraLibraryScreen(
    onEdit: (String?) -> Unit,
    onBrowse: () -> Unit,
    viewModel: LoraLibraryViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val provider by viewModel.activeProvider.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var addMenu by remember { mutableStateOf(false) }
    var addUrl by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Lora?>(null) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::import) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LoRAs") },
                actions = {
                    IconButton(onClick = viewModel::sync) { Icon(Icons.Default.Sync, "Sync from ${provider?.name ?: "server"}") }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(onClick = { addMenu = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add LoRA") })
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Sync from ${provider?.name ?: "server"}") }, leadingIcon = { Icon(Icons.Default.Sync, null) }, onClick = { addMenu = false; viewModel.sync() })
                    DropdownMenuItem(text = { Text("Import .safetensors file") }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { addMenu = false; importFile.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("Add from Civitai / HF URL") }, leadingIcon = { Icon(Icons.Default.Link, null) }, onClick = { addMenu = false; addUrl = true })
                    DropdownMenuItem(text = { Text("Browse model sites") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { addMenu = false; onBrowse() })
                    DropdownMenuItem(text = { Text("Manual entry") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { addMenu = false; onEdit(null) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            OutlinedTextField(
                value = filter.query,
                onValueChange = viewModel::setQuery,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (filter.query.isNotEmpty()) ({ IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Default.Close, "Clear") } }) else null,
                placeholder = { Text("Search names, trigger words, tags") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter.categoryId == null && !filter.favoritesOnly, { viewModel.setCategory(null); viewModel.setFavoritesOnly(false) }, { Text("All") })
                FilterChip(filter.favoritesOnly, { viewModel.setFavoritesOnly(!filter.favoritesOnly) }, { Text("Favorites") }, leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(16.dp)) })
                categories.forEach { c -> FilterChip(filter.categoryId == c.id, { viewModel.setCategory(if (filter.categoryId == c.id) null else c.id) }, { Text("${c.name} (${c.count})") }) }
                FilterChip(filter.categoryId == LoraRepositoryImpl.UNCATEGORIZED, { viewModel.setCategory(LoraRepositoryImpl.UNCATEGORIZED) }, { Text("Uncategorized") })
                AssistChip(onClick = { newCategory = true }, label = { Text("+ Category") }, leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) })
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField(
                    "Availability", LoraAvailabilityFilter.entries.toList(), filter.availability, viewModel::setAvailability,
                    { if (it == LoraAvailabilityFilter.ON_SERVER) "On ${provider?.name ?: "server"}" else it.label }, Modifier.weight(1f),
                )
                SelectField(
                    "Base model", listOf<ArchitectureFamily?>(null) + ArchitectureFamily.entries, filter.family, viewModel::setFamily,
                    { it?.displayName ?: "Any" }, Modifier.weight(1f),
                )
            }
            if (stack.isNotEmpty()) {
                Text("Active stack", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(stack, key = { it.lora.id }) { a ->
                        InputChip(
                            selected = a.enabled,
                            onClick = { onEdit(a.lora.id) },
                            label = { Text("${a.lora.name} · ${Formatters.decimal(a.strength)}", maxLines = 1) },
                            trailingIcon = { IconButton(onClick = { viewModel.toggleStack(a.lora.id) }, Modifier.size(20.dp)) { Icon(Icons.Default.Close, "Remove from stack", Modifier.size(16.dp)) } },
                        )
                    }
                }
            }
            val list = items
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> EmptyState(
                    Icons.Default.Layers,
                    if (filter.query.isBlank() && filter.categoryId == null) "No LoRAs yet" else "No matches",
                    "Sync the LoRA folder of your ComfyUI/WebUI server, import a file, or add LoRAs from Civitai and Hugging Face.",
                    action = { FilledTonalButton(onClick = viewModel::sync) { Text("Sync from server") } },
                )
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { lora ->
                        LoraRow(
                            lora = lora,
                            provider = provider,
                            inStack = stack.any { it.lora.id == lora.id },
                            showNsfw = settings.showNsfwPreviews,
                            onOpen = { onEdit(lora.id) },
                            onToggleStack = { viewModel.toggleStack(lora.id) },
                            onFavorite = { viewModel.toggleFavorite(lora) },
                            onDelete = { deleteTarget = lora },
                        )
                    }
                }
            }
        }
    }
    if (addUrl) TextInputDialog("Add LoRA by URL", "Civitai or Hugging Face URL / owner/repo", confirmLabel = "Add", onConfirm = viewModel::addByUrl, onDismiss = { addUrl = false })
    if (newCategory) TextInputDialog("New category", "Name", onConfirm = viewModel::createCategory, onDismiss = { newCategory = false })
    deleteTarget?.let { target ->
        var deleteFile by remember { mutableStateOf(true) }
        ConfirmDialog(
            title = "Delete ${target.name}?",
            text = "Removes the library entry. Server files are never touched.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.delete(target, deleteFile) },
            onDismiss = { deleteTarget = null },
            extra = if (target.isOnDevice) ({
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                    Text("Also delete the file on this device")
                }
            }) else null,
        )
    }
}

@Composable
private fun LoraRow(
    lora: Lora,
    provider: ProviderConfig?,
    inStack: Boolean,
    showNsfw: Boolean,
    onOpen: () -> Unit,
    onToggleStack: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val ext = LocalExtendedColors.current
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = if (inStack) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (inStack) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PreviewImage(lora.previewImageUrl, lora.nsfw, showNsfw, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(lora.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ArchitecturePill(lora.architecture)
                    if (lora.isOnServer(provider?.id)) Pill("Server", color = ext.success, icon = Icons.Default.Storage)
                    if (lora.backendLinks.isNotEmpty() && !lora.isOnServer(provider?.id)) Pill("Other server", color = MaterialTheme.colorScheme.outline, icon = Icons.Default.Storage)
                    if (lora.isOnDevice) Pill("Device", color = MaterialTheme.colorScheme.secondary, icon = Icons.Default.PhoneAndroid)
                    if (lora.hasRemoteWeights) Pill("HF cloud", color = MaterialTheme.colorScheme.primary, icon = Icons.Default.Cloud)
                    else if (!lora.isOnDevice && lora.backendLinks.isEmpty() && lora.downloadUrl != null) Pill("Remote", color = MaterialTheme.colorScheme.outline, icon = Icons.Default.CloudDownload)
                }
                if (lora.triggerWords.isNotEmpty()) {
                    Text(lora.triggerWords.take(3).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Checkbox(checked = inStack, onCheckedChange = { onToggleStack() })
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (lora.isFavorite) "Unfavorite" else "Favorite") }, leadingIcon = { Icon(if (lora.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, null) }, onClick = { menu = false; onFavorite() })
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}

@Composable
fun LoraEditScreen(onBack: () -> Unit, viewModel: LoraEditViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val links by viewModel.links.collectAsStateWithLifecycle()
    val serverProviders by viewModel.serverProviders.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var confirmDelete by remember { mutableStateOf(false) }
    var newTrigger by rememberSaveable { mutableStateOf("") }
    var newCategory by remember { mutableStateOf(false) }
    var linkPicker by remember { mutableStateOf<ProviderConfig?>(null) }
    val pickPreview = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPreviewFromDevice) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "New LoRA" else "Edit LoRA") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!viewModel.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    TextButton(onClick = { viewModel.save(onBack) }, enabled = !draft?.name.isNullOrBlank()) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val lora = draft
        if (lora == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviewImage(lora.previewImageUrl, nsfw = false, showNsfw = true, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { pickPreview.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose preview") }
                    if (lora.previewImageUrl != null) TextButton(onClick = { viewModel.update { it.copy(previewImageUrl = null) } }) { Text("Remove preview") }
                }
            }
            OutlinedTextField(lora.name, { v -> viewModel.update { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(lora.description, { v -> viewModel.update { it.copy(description = v) } }, label = { Text("Description") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())

            SectionCard(title = "Trigger words") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lora.triggerWords.forEach { word ->
                        InputChip(selected = false, onClick = {}, label = { Text(word) }, trailingIcon = {
                            IconButton(onClick = { viewModel.update { it.copy(triggerWords = it.triggerWords - word) } }, Modifier.size(20.dp)) { Icon(Icons.Default.Close, "Remove $word", Modifier.size(16.dp)) }
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newTrigger, { newTrigger = it }, placeholder = { Text("Add trigger word") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val words = newTrigger.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (words.isNotEmpty()) viewModel.update { it.copy(triggerWords = (it.triggerWords + words).distinct()) }
                        newTrigger = ""
                    }) { Icon(Icons.Default.Add, "Add trigger word") }
                }
            }

            SectionCard(title = "Compatibility") {
                SelectField(
                    "Trained for", listOf<ModelArchitecture?>(null) + ModelArchitecture.entries, lora.architecture,
                    { a -> viewModel.update { it.copy(architecture = a) } }, { it?.displayName ?: "Unknown" },
                    supporting = "Used to warn when the LoRA doesn't match the selected checkpoint.",
                )
                OutlinedTextField(lora.baseModelLabel.orEmpty(), { v -> viewModel.update { it.copy(baseModelLabel = v.ifBlank { null }) } }, label = { Text("Base model label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LabeledSlider("Default strength", lora.defaultStrength, { v -> viewModel.update { it.copy(defaultStrength = (v * 100).toInt() / 100f) } }, -1f..2f, Formatters.decimal(lora.defaultStrength))
            }

            SectionCard(title = "Category") {
                SelectField(
                    "Category", listOf<String?>(null) + categories.map { it.id }, lora.categoryId,
                    { id -> viewModel.update { it.copy(categoryId = id) } }, { id -> categories.firstOrNull { it.id == id }?.name ?: "None" },
                )
                TextButton(onClick = { newCategory = true }) { Text("New category") }
            }

            SectionCard(title = "Servers", subtitle = "How each ComfyUI/WebUI server names this LoRA") {
                if (viewModel.isNew) Text("Save the LoRA first to link it to a server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (serverProviders.isEmpty()) Text("No ComfyUI or WebUI providers configured.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else serverProviders.forEach { p ->
                    val linked = links[p.id]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(linked ?: "Not linked", style = MaterialTheme.typography.bodySmall, color = if (linked != null) LocalExtendedColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        TextButton(onClick = { linkPicker = p }) { Text(if (linked != null) "Change" else "Link") }
                        if (linked != null) {
                            TextButton(onClick = { viewModel.readServerMetadata(p.id) }) { Text("Read metadata") }
                            IconButton(onClick = { viewModel.setLink(p.id, null) }) { Icon(Icons.Default.Close, "Unlink") }
                        }
                    }
                }
            }

            SectionCard(title = "Files & sources") {
                KeyValueRow("Source", lora.source.displayName)
                lora.fileName?.let { KeyValueRow("File name", it) }
                lora.fileSizeBytes?.let { KeyValueRow("Size", Formatters.bytes(it)) }
                lora.sha256?.let { KeyValueRow("SHA-256", it.take(16) + "…") }
                KeyValueRow("On device", lora.localFilePath ?: "No")
                OutlinedTextField(lora.downloadUrl.orEmpty(), { v -> viewModel.update { it.copy(downloadUrl = v.ifBlank { null }) } }, label = { Text("Download URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lora.hfRepoId.orEmpty(), { v -> viewModel.update { it.copy(hfRepoId = v.ifBlank { null }) } }, label = { Text("Hugging Face repo (for cloud providers)") }, placeholder = { Text("owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lora.hfWeightFile.orEmpty(), { v -> viewModel.update { it.copy(hfWeightFile = v.ifBlank { null }) } }, label = { Text("Weights file in the repo") }, placeholder = { Text("lora.safetensors") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lora.downloadUrl != null && !lora.isOnDevice) OutlinedButton(onClick = viewModel::downloadToDevice) { Text("Download to device") }
                    if (lora.isOnDevice) {
                        OutlinedButton(onClick = { viewModel.shareUri()?.let { ShareHelper.shareFile(context, it) } }) { Text("Send file to server/PC") }
                        OutlinedButton(onClick = viewModel::deleteLocalFile) { Text("Delete device copy") }
                    }
                    if (!viewModel.isNew) OutlinedButton(onClick = viewModel::lookupCivitai) { Text("Look up on Civitai") }
                }
                InfoBanner("Self-hosted servers load LoRAs from their own folder (ComfyUI: models/loras, WebUI: models/Lora). Cloud providers via Hugging Face need the repo + weights file above.")
            }
            Spacer(Modifier.size(32.dp))
        }
    }

    linkPicker?.let { p ->
        val catalog by remember(p.id) { viewModel.catalog(p.id) }.collectAsStateWithLifecycle(null)
        val names = catalog?.loras?.map { it.name }.orEmpty()
        if (catalog == null) {
            LaunchedEffect(p.id) { viewModel.refreshCatalog(p.id) }
            AlertDialog(onDismissRequest = { linkPicker = null }, title = { Text("Loading ${p.name}…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { linkPicker = null }) { Text("Close") } })
        } else {
            PickerSheet(
                title = "LoRA file on ${p.name}",
                options = names,
                selected = links[p.id],
                itemLabel = { it },
                onSelect = { viewModel.setLink(p.id, it); linkPicker = null },
                onDismiss = { linkPicker = null },
            )
        }
    }
    if (newCategory) TextInputDialog("New category", "Name", onConfirm = viewModel::createCategory, onDismiss = { newCategory = false })
    if (confirmDelete) {
        val target = draft
        var deleteFile by remember { mutableStateOf(true) }
        ConfirmDialog(
            title = "Delete ${target?.name}?",
            text = "Removes the library entry. Files on servers are never touched.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.delete(deleteFile, onBack) },
            onDismiss = { confirmDelete = false },
            extra = if (target?.isOnDevice == true) ({
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                    Text("Also delete the device copy")
                }
            }) else null,
        )
    }
}
