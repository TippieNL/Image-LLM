package com.unfiltered.studio.presentation.screens.models

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.domain.models.ModelKind
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.presentation.components.ArchitecturePill
import com.unfiltered.studio.presentation.components.BannerTone
import com.unfiltered.studio.presentation.components.ErrorPanel
import com.unfiltered.studio.presentation.components.InfoBanner
import com.unfiltered.studio.presentation.components.KeyValueRow
import com.unfiltered.studio.presentation.components.Pill
import com.unfiltered.studio.presentation.components.PreviewImage
import com.unfiltered.studio.presentation.components.SectionCard
import com.unfiltered.studio.presentation.components.ShareHelper

@Composable
fun ModelDetailScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenGenerate: () -> Unit,
    viewModel: ModelDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val model by viewModel.model.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val versionIndex by viewModel.versionIndex.collectAsStateWithLifecycle()
    val prompt by viewModel.downloadPrompt.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(model?.name ?: "Model", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    model?.let { m -> IconButton(onClick = { ShareHelper.openUrl(context, m.webUrl) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open on website") } }
                    IconButton(onClick = onOpenDownloads) { Icon(Icons.Default.Download, "Downloads") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val m = model
        when {
            error != null && m == null -> ErrorPanel(error!!, Modifier.padding(padding).padding(16.dp), onRetry = { viewModel.load() })
            m == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val version = m.versions.getOrNull(versionIndex)
                val previews = (version?.previews?.ifEmpty { null } ?: m.previewUrls)
                if (previews.isNotEmpty()) {
                    val pager = rememberPagerState { previews.size }
                    Box {
                        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge)) { page ->
                            val p = previews[page]
                            PreviewImage(p.url, p.nsfw || m.nsfw, settings.showNsfwPreviews, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        if (previews.size > 1) {
                            Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                repeat(previews.size.coerceAtMost(12)) { i ->
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)))
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.name, style = MaterialTheme.typography.headlineSmall)
                    m.author?.let { Text("by $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(m.kind.displayName)
                        ArchitecturePill(m.architecture)
                        (version?.baseModelLabel ?: m.baseModelLabel)?.let { Pill(it, color = MaterialTheme.colorScheme.outline) }
                        if (m.gated) Pill("Gated — accept license on HF", color = MaterialTheme.colorScheme.error)
                        if (m.nsfw) Pill("NSFW", color = MaterialTheme.colorScheme.error)
                        m.inferenceProviders.forEach { Pill("Runs on $it", color = MaterialTheme.colorScheme.primary) }
                    }
                    Text("↓ ${Formatters.count(m.downloads)} downloads · ♥ ${Formatters.count(m.likes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.kind == ModelKind.LORA) {
                        FilledTonalButton(onClick = viewModel::addToLibrary) { Icon(Icons.Default.LibraryAdd, null); Spacer(Modifier.width(6.dp)); Text("Add to LoRA library") }
                    }
                    if (m.source == ModelSource.HUGGING_FACE && m.inferenceProviders.isNotEmpty()) {
                        Button(onClick = { if (viewModel.useWithHuggingFace()) onOpenGenerate() }) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Generate with HF") }
                    }
                    OutlinedButton(onClick = { ShareHelper.openUrl(context, m.webUrl) }) { Text("Open page") }
                }

                if (m.versions.size > 1) {
                    Text("Versions", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.versions.forEachIndexed { i, v -> FilterChip(selected = i == versionIndex, onClick = { viewModel.selectVersion(i) }, label = { Text(v.name, maxLines = 1) }) }
                    }
                }
                version?.triggerWords?.takeIf { it.isNotEmpty() }?.let { words ->
                    SectionCard(title = "Trigger words") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            words.forEach { w ->
                                AssistChip(onClick = {
                                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("trigger", w))
                                }, label = { Text(w, maxLines = 1) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp)) })
                            }
                        }
                    }
                }
                SectionCard(title = "Files", subtitle = "Downloads go to this phone's app storage") {
                    val files = version?.files.orEmpty()
                    if (files.isEmpty()) Text("No downloadable weight files listed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    files.forEach { f ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(Formatters.bytes(f.sizeBytes), f.format, f.precision, if (f.primary) "primary" else null).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { viewModel.askDownload(f) }) { Icon(Icons.Default.Download, "Download ${f.name}") }
                            }
                        }
                    }
                }
                m.license?.let { SectionCard(title = "License") { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                m.description?.takeIf { it.isNotBlank() }?.let { d ->
                    SectionCard(title = "Description", collapsible = true, initiallyExpanded = false) {
                        val text = remember(d) { if (m.descriptionIsHtml) AnnotatedString.fromHtml(d) else AnnotatedString(d) }
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(max = 4000.dp))
                    }
                }
                if (m.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { m.tags.take(24).forEach { Pill(it, color = MaterialTheme.colorScheme.outline) } }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    prompt?.let { p ->
        val free = viewModel.availableBytes()
        val needsKey = p.model.source == ModelSource.CIVITAI && !accounts.hasCivitaiKey
        val needsToken = p.model.source == ModelSource.HUGGING_FACE && p.model.gated && !accounts.hasHfToken
        AlertDialog(
            onDismissRequest = viewModel::dismissDownload,
            title = { Text("Download to this device?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyValueRow("File", p.file.name)
                    KeyValueRow("Size", Formatters.bytes(p.file.sizeBytes))
                    KeyValueRow("Free space", Formatters.bytes(free))
                    KeyValueRow("Saved to", "App storage › models/${p.model.kind.displayName.lowercase()}")
                    if (p.file.sha256 != null) KeyValueRow("Verified", "SHA-256 checked after download")
                    InfoBanner("Your GPU server can't use files on the phone. To generate with this on ComfyUI/WebUI, copy it to the server's models folder (or download it there directly).")
                    if (needsKey) InfoBanner("Many Civitai files require an API key — add one in Settings → Accounts.", tone = BannerTone.WARNING)
                    if (needsToken) InfoBanner("Gated model: add your Hugging Face token and accept the license on huggingface.co.", tone = BannerTone.WARNING)
                    if (p.file.sizeBytes != null && p.file.sizeBytes > free) InfoBanner("Not enough free space.", tone = BannerTone.ERROR)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDownload, enabled = p.file.sizeBytes == null || p.file.sizeBytes <= free) { Text("Download") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDownload) { Text("Cancel") } },
        )
    }
}
