package com.unfiltered.studio.presentation.screens.gallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.domain.models.GallerySort
import com.unfiltered.studio.domain.models.Generation
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.presentation.components.ConfirmDialog
import com.unfiltered.studio.presentation.components.EmptyState
import com.unfiltered.studio.presentation.components.ErrorPanel
import com.unfiltered.studio.presentation.components.Pill
import com.unfiltered.studio.presentation.components.SelectField
import com.unfiltered.studio.presentation.components.ShareHelper
import com.unfiltered.studio.presentation.theme.LocalExtendedColors
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onOpenImage: (String) -> Unit,
    onOpenGeneration: (String) -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val images = viewModel.images.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showFilters by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = { IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Default.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { viewModel.setFavorite(selection, true); viewModel.clearSelection() }) { Icon(Icons.Default.Star, "Favorite") }
                        IconButton(onClick = { viewModel.export(selection); viewModel.clearSelection() }) { Icon(Icons.Default.SaveAlt, "Save to device") }
                        IconButton(onClick = { viewModel.share(selection) { ShareHelper.shareImages(context, it) } }) { Icon(Icons.Default.Share, "Share") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Gallery") },
                    actions = {
                        if (tab == 0) {
                            BadgedBox(badge = { if (filter.isActive) Badge() }) {
                                IconButton(onClick = { showFilters = true }) { Icon(Icons.Default.FilterList, "Filters") }
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Images") })
                Tab(selected = tab == 1, onClick = { tab = 1; viewModel.clearSelection() }, text = { Text("History") })
            }
            if (tab == 0) {
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = viewModel::setQuery,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (filter.query.isNotEmpty()) ({ IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Default.Close, "Clear") } }) else null,
                    placeholder = { Text("Search prompts and models") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (filter.isActive) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (filter.favoritesOnly) FilterChip(true, { viewModel.setFavoritesOnly(false) }, { Text("Favorites") })
                        filter.model?.let { FilterChip(true, { viewModel.setModel(null) }, { Text(it.substringAfterLast('/'), maxLines = 1) }) }
                        filter.loraName?.let { FilterChip(true, { viewModel.setLora(null) }, { Text("LoRA: $it", maxLines = 1) }) }
                        if (filter.fromMs != null) FilterChip(true, { viewModel.setDateRange(null, null) }, { Text("Date range") })
                        TextButton(onClick = viewModel::clearFilters) { Text("Clear all") }
                    }
                }
                val columns = if (settings.galleryColumns > 0) GridCells.Fixed(settings.galleryColumns) else GridCells.Adaptive(112.dp)
                if (images.itemCount == 0 && images.loadState.refresh is LoadState.NotLoading) {
                    EmptyState(
                        Icons.Default.PhotoLibrary,
                        if (filter.isActive) "No matching images" else "No images yet",
                        if (filter.isActive) "Try different filters." else "Generated images are stored here with their full settings.",
                    )
                } else {
                    LazyVerticalGrid(
                        columns = columns,
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(images.itemCount, key = images.itemKey { it.id }) { index ->
                            val image = images[index] ?: return@items
                            val selected = image.id in selection
                            Box(
                                Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                                    .combinedClickable(
                                        onClick = { if (selecting) viewModel.toggleSelection(image.id) else onOpenImage(image.id) },
                                        onLongClick = { viewModel.toggleSelection(image.id) },
                                    ),
                            ) {
                                AsyncImage(model = File(image.thumbnailPath), contentDescription = image.prompt.take(80), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (image.isFavorite) Icon(Icons.Default.Star, "Favorite", tint = LocalExtendedColors.current.warning, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
                                if (selected) Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.White, CircleShape))
                            }
                        }
                    }
                }
            } else {
                HistoryTab(viewModel, onOpenGeneration)
            }
        }
    }

    if (showFilters) FilterSheet(viewModel, onDismiss = { showFilters = false })
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${selection.size} image(s)?",
            text = "Files are removed from app storage. Copies you saved to the device gallery are kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.delete(selection.toList()) { viewModel.clearSelection() } },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun HistoryTab(viewModel: GalleryViewModel, onOpenGeneration: (String) -> Unit) {
    val items = viewModel.generationsPaged.collectAsLazyPagingItems()
    val query by viewModel.historyQueryState.collectAsStateWithLifecycle()
    val status by viewModel.historyStatusState.collectAsStateWithLifecycle()
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setHistoryQuery,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Search history") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(status == null, { viewModel.setHistoryStatus(null) }, { Text("All") })
            listOf(GenerationStatus.SUCCEEDED to "Succeeded", GenerationStatus.FAILED to "Failed", GenerationStatus.CANCELLED to "Cancelled").forEach { (s, label) ->
                FilterChip(status == s, { viewModel.setHistoryStatus(s) }, { Text(label) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
                item { EmptyState(Icons.Default.PhotoLibrary, "No history", "Every generation, including failed ones, is listed here.") }
            }
            items(items.itemCount, key = items.itemKey { it.id }) { index ->
                val g = items[index] ?: return@items
                HistoryRow(g, onOpen = { onOpenGeneration(g.id) }, onRetry = { viewModel.retry(g.id) })
            }
        }
    }
}

@Composable
private fun HistoryRow(g: Generation, onOpen: () -> Unit, onRetry: () -> Unit) {
    val ext = LocalExtendedColors.current
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (g.status) {
                    GenerationStatus.SUCCEEDED -> Pill("Done", color = ext.success)
                    GenerationStatus.FAILED -> Pill("Failed", color = MaterialTheme.colorScheme.error, icon = Icons.Default.ErrorOutline)
                    GenerationStatus.CANCELLED -> Pill("Cancelled", color = MaterialTheme.colorScheme.outline)
                    else -> Pill(g.status.name.lowercase())
                }
                Spacer(Modifier.width(8.dp))
                Text("${g.providerName} · ${g.modelLabel.substringAfterLast('/')}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Formatters.relative(g.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(g.params.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (g.images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.images.take(4).forEach { image ->
                        AsyncImage(model = File(image.thumbnailPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                    }
                }
            }
            if (g.status == GenerationStatus.FAILED) {
                Text(g.error ?: "Unknown error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = onRetry) { Icon(Icons.Default.Replay, null); Spacer(Modifier.width(6.dp)); Text("Retry") }
            }
            Text(
                "${g.params.width}×${g.params.height} · ${g.params.steps} steps · seed ${g.params.seed.takeIf { it >= 0 } ?: "random"} · ${Formatters.duration(g.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterSheet(viewModel: GalleryViewModel, onDismiss: () -> Unit) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val facets by viewModel.facets.collectAsStateWithLifecycle()
    var pickDates by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Filter images", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favorites only", modifier = Modifier.weight(1f))
                Switch(checked = filter.favoritesOnly, onCheckedChange = viewModel::setFavoritesOnly)
            }
            SelectField("Model", listOf("") + facets.models, filter.model ?: "", { viewModel.setModel(it.ifBlank { null }) }, { it.ifBlank { "Any model" }.substringAfterLast('/') })
            SelectField("LoRA", listOf("") + facets.loras, filter.loraName ?: "", { viewModel.setLora(it.ifBlank { null }) }, { it.ifBlank { "Any LoRA" } })
            SelectField(
                "Provider",
                listOf("" to "Any provider") + facets.providers,
                (filter.providerId ?: "").let { id -> (listOf("" to "Any provider") + facets.providers).firstOrNull { it.first == id } },
                { viewModel.setProvider(it.first.ifBlank { null }) },
                { it.second },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Date range")
                    Text(
                        if (filter.fromMs != null) "${Formatters.date(filter.fromMs!!)} – ${filter.toMs?.let { Formatters.date(it) } ?: "now"}" else "Any time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { pickDates = true }) { Text("Choose") }
                if (filter.fromMs != null) TextButton(onClick = { viewModel.setDateRange(null, null) }) { Text("Clear") }
            }
            SelectField("Sort", GallerySort.entries.toList(), filter.sort, viewModel::setSort, { it.label })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::clearFilters) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
    if (pickDates) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDateRange(state.selectedStartDateMillis, state.selectedEndDateMillis ?: state.selectedStartDateMillis)
                    pickDates = false
                }, enabled = state.selectedStartDateMillis != null) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun DeleteGenerationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this generation?") },
        text = { Text("Its images and settings are removed from the app.") },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ErrorBlock(g: Generation) {
    if (g.status == GenerationStatus.FAILED) ErrorPanel(g.error ?: "Generation failed", details = g.errorDetails)
}
