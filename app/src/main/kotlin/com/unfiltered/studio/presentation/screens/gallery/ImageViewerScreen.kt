package com.unfiltered.studio.presentation.screens.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.domain.models.GalleryImage
import com.unfiltered.studio.domain.models.Generation
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.presentation.components.ConfirmDialog
import com.unfiltered.studio.presentation.components.ErrorPanel
import com.unfiltered.studio.presentation.components.InfoBanner
import com.unfiltered.studio.presentation.components.BannerTone
import com.unfiltered.studio.presentation.components.KeyValueRow
import com.unfiltered.studio.presentation.components.SectionCard
import com.unfiltered.studio.presentation.components.ShareHelper
import com.unfiltered.studio.presentation.components.ZoomableImage
import com.unfiltered.studio.presentation.theme.LocalExtendedColors
import java.io.File

@Composable
fun ImageViewerScreen(
    onBack: () -> Unit,
    onOpenGenerate: () -> Unit,
    viewModel: ImageViewerViewModel = hiltViewModel(),
) {
    val ids by viewModel.ids.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val list = ids
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (list == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        val pager = rememberPagerState(initialPage = list.indexOf(viewModel.initialId).coerceAtLeast(0)) { list.size }
        var chrome by remember { mutableStateOf(true) }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { list.getOrNull(it) ?: it }) { page ->
            val id = list.getOrNull(page) ?: return@HorizontalPager
            val image by remember(id) { viewModel.image(id) }.collectAsStateWithLifecycle(null)
            image?.let { ZoomableImage(it.filePath, it.prompt.take(100), onTap = { chrome = !chrome }) }
        }
        val currentId = list.getOrNull(pager.currentPage)
        val current by remember(currentId) { currentId?.let { viewModel.image(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsStateWithLifecycle(null)
        LaunchedEffect(list) { if (list.isEmpty()) onBack() }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text("${pager.currentPage + 1} / ${list.size}", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                current?.let { Text("${it.width}×${it.height}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp)) }
            }
        }
        current?.let { image ->
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                ViewerActions(image, viewModel, onOpenGenerate)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
}

@Composable
private fun ViewerActions(image: GalleryImage, viewModel: ImageViewerViewModel, onOpenGenerate: () -> Unit) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val generation by remember(image.generationId) { viewModel.generation(image.generationId) }.collectAsStateWithLifecycle(null)
    Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).navigationBarsPadding().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = { viewModel.setFavorite(listOf(image.id), !image.isFavorite) }) {
            Icon(if (image.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (image.isFavorite) LocalExtendedColors.current.warning else Color.White)
        }
        IconButton(onClick = { viewModel.export(listOf(image.id)) }) { Icon(Icons.Default.SaveAlt, "Save to device", tint = Color.White) }
        IconButton(onClick = { viewModel.share(listOf(image.id)) { ShareHelper.shareImages(context, it) } }) { Icon(Icons.Default.Share, "Share", tint = Color.White) }
        IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, "Generation info", tint = Color.White) }
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = Color.White) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                generation?.let { g ->
                    DropdownMenuItem(text = { Text("Reuse settings") }, leadingIcon = { Icon(Icons.Default.Tune, null) }, onClick = { viewModel.reuse(g, image.seed); menu = false; onOpenGenerate() })
                    DropdownMenuItem(text = { Text("Regenerate (same seed)") }, leadingIcon = { Icon(Icons.Default.Replay, null) }, onClick = { viewModel.regenerate(g.id, newSeed = false); menu = false })
                    DropdownMenuItem(text = { Text("Variation (new seed)") }, leadingIcon = { Icon(Icons.Default.Casino, null) }, onClick = { viewModel.regenerate(g.id, newSeed = true); menu = false })
                }
                DropdownMenuItem(text = { Text("Use as img2img input") }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = { viewModel.useAsInit(image); menu = false; onOpenGenerate() })
                DropdownMenuItem(text = { Text("Copy prompt") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("prompt", generation?.params?.prompt ?: image.prompt))
                    menu = false
                })
            }
        }
    }
    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                generation?.let { GenerationInfo(it, image) } ?: Text("Loading…")
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete image?", "This removes the image from the app. Saved device copies stay.", "Delete", onConfirm = { viewModel.delete(listOf(image.id)) }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

/** Full metadata of a generation (shared by the viewer info sheet and the detail screen). */
@Composable
fun GenerationInfo(g: Generation, image: GalleryImage?) {
    val p = g.params
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Prompt", style = MaterialTheme.typography.labelLarge)
        Text(p.prompt, style = MaterialTheme.typography.bodyMedium)
        if (p.negativePrompt.isNotBlank()) {
            Text("Negative prompt", style = MaterialTheme.typography.labelLarge)
            Text(p.negativePrompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("prompt", p.prompt))
            }, label = { Text("Copy prompt") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) })
        }
        Column {
            KeyValueRow("Provider", "${g.providerName} (${g.providerType.displayName})")
            KeyValueRow("Model", p.checkpoint ?: "Server default")
            KeyValueRow("Architecture", p.architecture.displayName)
            KeyValueRow("Seed", (image?.seed ?: p.seed.takeIf { it >= 0 })?.toString() ?: "Random (not reported)")
            KeyValueRow("Size", "${image?.width ?: p.width} × ${image?.height ?: p.height}")
            KeyValueRow("Steps", p.steps.toString())
            KeyValueRow("CFG scale", Formatters.decimal(p.cfgScale, 1))
            if (p.architecture.usesDistilledGuidance) KeyValueRow("Guidance", Formatters.decimal(p.distilledGuidance, 1))
            p.sampler?.let { KeyValueRow("Sampler", it) }
            p.scheduler?.let { KeyValueRow("Scheduler", it) }
            if (p.clipSkip > 1) KeyValueRow("CLIP skip", p.clipSkip.toString())
            p.vae?.let { KeyValueRow("VAE", it) }
            p.components.forEach { (k, v) -> KeyValueRow(k, v.replace('\n', ',')) }
            if (p.hiresFix.enabled) KeyValueRow("Hires fix", "×${Formatters.decimal(p.hiresFix.scale)} · ${p.hiresFix.upscaler ?: "default"} · denoise ${Formatters.decimal(p.hiresFix.denoise)}")
            if (p.initImagePath != null) KeyValueRow("img2img", "denoise ${Formatters.decimal(p.denoisingStrength)}")
            KeyValueRow("Batch", p.batchSize.toString())
            KeyValueRow("Created", Formatters.dateTime(g.createdAt))
            KeyValueRow("Duration", Formatters.duration(g.durationMs))
            image?.let { KeyValueRow("File", "${it.mimeType} · ${Formatters.bytes(it.sizeBytes)}") }
        }
        if (p.loras.isNotEmpty()) {
            Text("LoRAs", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.loras.forEach { l -> AssistChip(onClick = {}, label = { Text("${l.name} · ${Formatters.decimal(l.strength)}") }) }
            }
        }
        g.warnings.forEach { InfoBanner(it, tone = BannerTone.WARNING) }
        if (g.status == GenerationStatus.FAILED) ErrorPanel(g.error ?: "Generation failed", details = g.errorDetails)
    }
}

@Composable
fun GenerationDetailScreen(
    onBack: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenGenerate: () -> Unit,
    viewModel: GenerationDetailViewModel = hiltViewModel(),
) {
    val generation by viewModel.generation.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generation") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete generation") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val g = generation
        if (g == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (g.images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    g.images.take(4).forEach { image ->
                        AsyncImage(
                            model = File(image.thumbnailPath),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).clickable { onOpenImage(image.id) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.reuse(g, g.images.firstOrNull()?.seed); onOpenGenerate() }) { Text("Reuse settings") }
                OutlinedButton(onClick = { if (g.status == GenerationStatus.SUCCEEDED) viewModel.regenerate(g.id, false) else viewModel.retry(g.id) }) {
                    Text(if (g.status == GenerationStatus.SUCCEEDED) "Regenerate" else "Retry")
                }
                OutlinedButton(onClick = { viewModel.regenerate(g.id, true) }) { Text("Variation") }
            }
            SectionCard(title = "Settings") { GenerationInfo(g, g.images.firstOrNull()) }
        }
    }
    if (confirmDelete) DeleteGenerationDialog(onConfirm = { viewModel.deleteGeneration(generation?.id ?: return@DeleteGenerationDialog, onBack) }, onDismiss = { confirmDelete = false })
}
