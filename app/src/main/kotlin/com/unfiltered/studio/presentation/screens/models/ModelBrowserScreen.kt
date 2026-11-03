package com.unfiltered.studio.presentation.screens.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.core.displayMessage
import com.unfiltered.studio.domain.models.ArchitectureFamily
import com.unfiltered.studio.domain.models.BackendModel
import com.unfiltered.studio.domain.models.BrowseSort
import com.unfiltered.studio.domain.models.DownloadStatus
import com.unfiltered.studio.domain.models.ModelKind
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.domain.models.RemoteModel
import com.unfiltered.studio.presentation.components.ArchitecturePill
import com.unfiltered.studio.presentation.components.EmptyState
import com.unfiltered.studio.presentation.components.ErrorPanel
import com.unfiltered.studio.presentation.components.InfoBanner
import com.unfiltered.studio.presentation.components.Pill
import com.unfiltered.studio.presentation.components.PreviewImage
import com.unfiltered.studio.presentation.components.SectionCard
import com.unfiltered.studio.presentation.components.SelectField
import com.unfiltered.studio.presentation.components.ShareHelper

private enum class ModelTab(val title: String) { HUGGING_FACE("Hugging Face"), CIVITAI("Civitai"), SERVER("On server"), DEVICE("On device") }

@Composable
fun ModelBrowserScreen(
    onOpenModel: (ModelSource, String) -> Unit,
    onOpenDownloads: () -> Unit,
    onUseCheckpoint: (String) -> Unit,
    viewModel: ModelBrowserViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                actions = {
                    BadgedBox(badge = { if (activeDownloads > 0) Badge { Text("$activeDownloads") } }) {
                        IconButton(onClick = onOpenDownloads) { Icon(Icons.Default.Download, "Downloads") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                ModelTab.entries.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = {
                        tab = i
                        when (t) {
                            ModelTab.HUGGING_FACE -> viewModel.update { it.copy(source = ModelSource.HUGGING_FACE) }
                            ModelTab.CIVITAI -> viewModel.update { it.copy(source = ModelSource.CIVITAI) }
                            else -> Unit
                        }
                    }, text = { Text(t.title) })
                }
            }
            when (ModelTab.entries[tab]) {
                ModelTab.HUGGING_FACE, ModelTab.CIVITAI -> RemoteBrowser(viewModel, query.source, onOpenModel)
                ModelTab.SERVER -> ServerModels(viewModel, onUseCheckpoint)
                ModelTab.DEVICE -> DeviceModels(viewModel, onOpenDownloads)
            }
        }
    }
}

@Composable
private fun RemoteBrowser(viewModel: ModelBrowserViewModel, source: ModelSource, onOpenModel: (ModelSource, String) -> Unit) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val items = viewModel.results.collectAsLazyPagingItems()
    var text by rememberSaveable(source) { mutableStateOf(query.text) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; viewModel.update { q -> q.copy(text = it) } },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (text.isNotEmpty()) ({ IconButton(onClick = { text = ""; viewModel.update { it.copy(text = "") } }) { Icon(Icons.Default.Close, "Clear") } }) else null,
            placeholder = { Text("Search ${source.displayName}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModelKind.entries.filter { it != ModelKind.OTHER && !(source == ModelSource.CIVITAI && it == ModelKind.IMG2IMG) }.forEach { kind ->
                FilterChip(selected = query.kind == kind, onClick = { viewModel.update { it.copy(kind = kind) } }, label = { Text(kind.displayName) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectField(
                label = "Base model",
                options = listOf<ArchitectureFamily?>(null) + ArchitectureFamily.entries.filter { it != ArchitectureFamily.OTHER },
                selected = query.family,
                onSelect = { f -> viewModel.update { it.copy(family = f) } },
                itemLabel = { it?.displayName ?: "Any" },
                modifier = Modifier.weight(1f),
            )
            SelectField(
                label = "Sort",
                options = BrowseSort.entries.toList(),
                selected = query.sort,
                onSelect = { s -> viewModel.update { it.copy(sort = s) } },
                itemLabel = { it.displayName },
                modifier = Modifier.weight(1f),
            )
        }
        if (source == ModelSource.CIVITAI) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = query.includeNsfw, onClick = { viewModel.update { it.copy(includeNsfw = !it.includeNsfw) } }, label = { Text("Include NSFW models") })
                Spacer(Modifier.width(8.dp))
                if (query.includeNsfw && !settings.showNsfwPreviews) Text("Previews stay blurred (Settings → Content)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (source == ModelSource.HUGGING_FACE && query.kind == ModelKind.VAE) {
            InfoBanner("Hugging Face has no VAE tag — results are a name search for \"vae\".", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        val refresh = items.loadState.refresh
        when {
            refresh is LoadState.Error && items.itemCount == 0 -> ErrorPanel(refresh.error.displayMessage, Modifier.padding(16.dp), onRetry = { items.retry() })
            refresh is LoadState.Loading && items.itemCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.itemCount == 0 -> EmptyState(Icons.Default.Search, "No models found", "Try another search or filter.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(168.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items.itemCount, key = items.itemKey { "${it.source}:${it.id}" }) { index ->
                    val model = items[index] ?: return@items
                    ModelCard(model, settings.showNsfwPreviews) { onOpenModel(model.source, model.id) }
                }
                val append = items.loadState.append
                if (append is LoadState.Loading) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                if (append is LoadState.Error) item(span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(append.error.displayMessage, onRetry = { items.retry() }) }
            }
        }
    }
}

@Composable
private fun ModelCard(model: RemoteModel, showNsfw: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val thumb = model.thumbnail
        PreviewImage(thumb?.url, nsfw = (thumb?.nsfw ?: false) || model.nsfw, showNsfw = showNsfw, modifier = Modifier.fillMaxWidth().aspectRatio(0.9f))
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            model.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(model.kind.displayName)
                ArchitecturePill(model.architecture)
                if (model.inferenceProviders.isNotEmpty()) Pill("Serverless", color = MaterialTheme.colorScheme.primary, icon = Icons.Default.Cloud)
                if (model.gated) Pill("Gated", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("↓ ${Formatters.count(model.downloads)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${Formatters.count(model.likes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ServerModels(viewModel: ModelBrowserViewModel, onUseCheckpoint: (String) -> Unit) {
    val provider by viewModel.activeProvider.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refreshServer, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            item {
                InfoBanner(
                    "Files stored on ${provider?.name ?: "your server"} — usable for generation right away. Phone downloads are separate (\"On device\" tab).",
                    action = { IconButton(onClick = viewModel::refreshServer) { Icon(Icons.Default.Refresh, "Refresh") } },
                )
            }
            val c = catalog
            if (provider == null) {
                item { EmptyState(Icons.Default.Storage, "No provider", "Add a provider in Settings to see its models.") }
            } else if (c == null) {
                item { EmptyState(Icons.Default.Storage, "Not loaded yet", "Pull to refresh to load the server's model list.") }
            } else {
                item { Text("${c.serverLabel ?: provider?.name} · updated ${Formatters.relative(c.fetchedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                c.warnings.forEach { w -> item { InfoBanner(w) } }
                if (c.checkpoints.isNotEmpty()) item { ServerSection("Checkpoints", c.checkpoints, actionLabel = "Use") { onUseCheckpoint(it.name) } }
                if (c.loras.isNotEmpty()) {
                    item {
                        ServerSection("LoRAs", c.loras, actionLabel = null, header = { TextButton(onClick = viewModel::syncLoras) { Text("Sync to library") } })
                    }
                }
                if (c.vaes.isNotEmpty()) item { ServerSection("VAEs", c.vaes, actionLabel = null) }
                c.componentFiles.filterValues { it.isNotEmpty() }.forEach { (slot, files) ->
                    item { ServerSection(slot.replace('_', ' ').replaceFirstChar { it.uppercase() }, files.map { BackendModel(it) }, actionLabel = null) }
                }
                if (c.embeddings.isNotEmpty()) item { ServerSection("Embeddings", c.embeddings.map { BackendModel(it) }, actionLabel = null) }
                if (c.models.isNotEmpty()) item { ServerSection("Suggested models", c.models.map { BackendModel(it) }, actionLabel = "Use") { onUseCheckpoint(it.name) } }
                if (c.checkpoints.isEmpty() && c.loras.isEmpty() && c.models.isEmpty()) {
                    item { Text("This provider doesn't list models (it uses a fixed model or model ids).", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun ServerSection(
    title: String,
    entries: List<BackendModel>,
    actionLabel: String?,
    header: (@Composable () -> Unit)? = null,
    onAction: (BackendModel) -> Unit = {},
) {
    SectionCard(title = "$title (${entries.size})", collapsible = true, initiallyExpanded = entries.size <= 12, actions = { header?.invoke() }) {
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.name != entry.displayName) Text(entry.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ArchitecturePill(entry.architecture)
                if (actionLabel != null) TextButton(onClick = { onAction(entry) }) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun DeviceModels(viewModel: ModelBrowserViewModel, onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    val downloads by viewModel.downloadsList.collectAsStateWithLifecycle()
    val completed = downloads.filter { it.status == DownloadStatus.COMPLETED }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            InfoBanner(
                "Files on this phone (${Formatters.bytes(completed.sumOf { it.totalBytes ?: 0 })} used, ${Formatters.bytes(viewModel.availableBytes())} free). " +
                    "Cloud and self-hosted servers can't read them — send them to your server's model folder to use them there.",
            )
        }
        item { OutlinedButton(onClick = onOpenDownloads) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Downloads in progress") } }
        if (completed.isEmpty()) item { EmptyState(Icons.Default.PhoneAndroid, "Nothing downloaded", "Download checkpoints or LoRAs from Hugging Face or Civitai.") }
        items(completed, key = { it.id }) { item ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.modelName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${item.kind.displayName} · ${item.fileName} · ${Formatters.bytes(item.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    IconButton(onClick = { viewModel.shareUri(item)?.let { ShareHelper.shareFile(context, it) } }) { Icon(Icons.Default.Share, "Send file") }
                    IconButton(onClick = { viewModel.deleteDownload(item) }) { Icon(Icons.Default.Delete, "Delete file") }
                }
            }
        }
    }
}
