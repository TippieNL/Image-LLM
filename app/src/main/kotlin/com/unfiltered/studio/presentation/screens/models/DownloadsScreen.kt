package com.unfiltered.studio.presentation.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.domain.models.DownloadItem
import com.unfiltered.studio.domain.models.DownloadStatus
import com.unfiltered.studio.presentation.components.EmptyState
import com.unfiltered.studio.presentation.components.InfoBanner
import com.unfiltered.studio.presentation.components.Pill
import com.unfiltered.studio.presentation.components.ShareHelper
import com.unfiltered.studio.presentation.theme.LocalExtendedColors

@Composable
fun DownloadsScreen(onBack: () -> Unit, viewModel: DownloadsViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { InfoBanner("Free space: ${Formatters.bytes(viewModel.availableBytes())}. Downloads resume automatically after network loss; paused downloads continue where they stopped.") }
            if (items.isEmpty()) item { EmptyState(Icons.Default.Download, "No downloads", "Start one from a model's page in the Models tab.") }
            items(items, key = { it.id }) { item ->
                DownloadRow(
                    item = item,
                    onPause = { viewModel.pause(item.id) },
                    onResume = { viewModel.resume(item.id) },
                    onCancel = { viewModel.cancel(item.id) },
                    onRemove = { viewModel.remove(item.id, deleteFile = true) },
                    onShare = { viewModel.shareUri(item)?.let { ShareHelper.shareFile(context, it) } },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit, onShare: () -> Unit) {
    val ext = LocalExtendedColors.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.modelName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (item.status) {
                    DownloadStatus.RUNNING -> Pill("Downloading", color = MaterialTheme.colorScheme.primary)
                    DownloadStatus.QUEUED -> Pill("Queued")
                    DownloadStatus.PAUSED -> Pill("Paused", color = ext.warning)
                    DownloadStatus.COMPLETED -> Pill("Done", color = ext.success)
                    DownloadStatus.FAILED -> Pill("Failed", color = MaterialTheme.colorScheme.error)
                    DownloadStatus.CANCELLED -> Pill("Cancelled", color = MaterialTheme.colorScheme.outline)
                }
            }
            if (item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.QUEUED) {
                val f = item.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "${Formatters.bytes(item.downloadedBytes)} of ${Formatters.bytes(item.totalBytes)}" + (item.fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            Row {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                        IconButton(onClick = onPause) { Icon(Icons.Default.Pause, "Pause") }
                        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
                    }
                    DownloadStatus.PAUSED -> {
                        IconButton(onClick = onResume) { Icon(Icons.Default.PlayArrow, "Resume") }
                        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                        IconButton(onClick = onResume) { Icon(Icons.Default.Replay, "Retry") }
                        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Remove") }
                    }
                    DownloadStatus.COMPLETED -> {
                        IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Send file") }
                        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Delete file") }
                    }
                }
            }
        }
    }
}
