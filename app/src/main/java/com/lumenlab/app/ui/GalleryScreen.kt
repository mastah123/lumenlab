package com.lumenlab.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumenlab.app.camera.Gallery
import com.lumenlab.app.camera.GalleryFilter
import com.lumenlab.app.camera.GalleryItem
import com.lumenlab.app.camera.dateTimeLabel
import com.lumenlab.app.camera.dayLabel
import com.lumenlab.app.camera.filtered
import com.lumenlab.app.camera.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything Lumenlab saved: photos, stacks, panoramas, double exposures and burst frames. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var filter by rememberSaveable { mutableStateOf(GalleryFilter.Results) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<List<GalleryItem>?>(null) }

    suspend fun reload() {
        loaded = withContext(Dispatchers.IO) { Gallery.list(context) }
    }
    LaunchedEffect(Unit) { reload() }

    val all = loaded.orEmpty()
    val visible = all.filtered(filter)
    val selecting = selected.isNotEmpty()

    BackHandler(enabled = viewing != null || selecting) {
        if (viewing != null) viewing = null else selected = emptySet()
    }

    val viewed = viewing
    if (viewed != null) {
        GalleryViewer(
            items = visible,
            startUri = viewed,
            onClose = { viewing = null },
            onDelete = { pendingDelete = listOf(it) },
        )
    } else {
        Scaffold(
            containerColor = colors.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(if (selecting) "${selected.size} selected" else "Gallery") },
                    navigationIcon = {
                        if (selecting) {
                            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") }
                        } else BackButton(onBack)
                    },
                    actions = {
                        if (selecting) {
                            IconButton(onClick = { Gallery.share(context, all.filter { it.uri in selected }) }) {
                                Icon(Icons.Filled.Share, "Share")
                            }
                            IconButton(onClick = { pendingDelete = all.filter { it.uri in selected } }) {
                                Icon(Icons.Filled.DeleteOutline, "Delete")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.background,
                        titleContentColor = colors.onBackground,
                    ),
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    items(GalleryFilter.values().toList()) { f ->
                        FilterChip(
                            selected = f == filter,
                            onClick = { filter = f; selected = emptySet() },
                            label = { Text("${f.label} ${all.filtered(f).size}") },
                        )
                    }
                }
                when {
                    loaded == null -> Text("Loading…", color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    visible.isEmpty() -> Text(
                        if (all.isEmpty()) "No pictures yet. Photos you take are listed here."
                        else "Nothing in ${filter.label} yet.",
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> {
                        val now = remember { System.currentTimeMillis() }
                        val groups = remember(visible) { visible.groupBy { dayLabel(it.dateMs, now) } }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            groups.forEach { (label, list) ->
                                item(key = "header-$label", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        label, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 4.dp),
                                    )
                                }
                                items(list, key = { it.uri }) { item ->
                                    GalleryCell(
                                        item = item,
                                        selecting = selecting,
                                        isSelected = item.uri in selected,
                                        onClick = {
                                            if (selecting) {
                                                selected = if (item.uri in selected) selected - item.uri else selected + item.uri
                                            } else viewing = item.uri
                                        },
                                        onLongClick = { selected = selected + item.uri },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (list.size == 1) "Delete this picture?" else "Delete ${list.size} pictures?") },
            text = { Text("They are removed from your phone and cannot be recovered.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { Gallery.delete(context, list) }
                        selected = emptySet()
                        reload()
                        val text = buildString {
                            append("Deleted ${result.deleted}")
                            if (result.failed > 0) append(". ${result.failed} could not be deleted (Android refused, for example files from an earlier install)")
                        }
                        snackbar.showSnackbar(text)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(
    item: GalleryItem,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri) { thumb = withContext(Dispatchers.IO) { Gallery.thumbnail(context, item) } }

    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(colors.surface)
            .then(if (isSelected) Modifier.border(3.dp, colors.primary, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Image, null, tint = colors.outline)
        }
        if (selecting) {
            Icon(
                Icons.Filled.CheckCircle, if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) colors.primary else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp),
            )
        }
    }
}

@Composable
private fun GalleryViewer(
    items: List<GalleryItem>,
    startUri: String,
    onClose: () -> Unit,
    onDelete: (GalleryItem) -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.uri == startUri }.coerceAtLeast(0)) { items.size }
    val item = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            ViewerPage(items[page])
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp, vertical = 4.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClose)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { Gallery.openExternally(context, item) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in another app to zoom")
            }
            IconButton(onClick = { Gallery.share(context, listOf(item)) }) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = { onDelete(item) }) { Icon(Icons.Filled.DeleteOutline, "Delete") }
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(12.dp)
                .align(Alignment.BottomCenter),
        ) {
            Text(item.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                "${dateTimeLabel(item.dateMs)} · ${formatBytes(item.sizeBytes)} · ${item.category.name}",
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ViewerPage(item: GalleryItem) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(item.uri) {
        val loaded = withContext(Dispatchers.IO) { Gallery.loadBitmap(context, item, 2048) }
        bitmap = loaded
        failed = loaded == null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val b = bitmap
        when {
            b != null -> Image(b.asImageBitmap(), item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            failed -> Text("This file cannot be previewed here. Use the open button to view it in another app.",
                color = Color.White, modifier = Modifier.padding(32.dp))
            else -> Text("Loading…", color = Color.White.copy(alpha = 0.7f))
        }
    }
}
