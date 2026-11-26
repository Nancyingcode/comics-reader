package io.github.kai.reader

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val PAGE_PREVIEW_WIDTH_PX = 96
private const val RECENT_STRIP_BYTES = 16 * 1024 * 1024

@Composable
internal fun ReaderScreen(
    chapter: LocalChapter,
    images: LocalImages,
    initialAnchor: ReadingAnchor?,
    onAnchor: (ReadingAnchor) -> Unit,
    onChapterList: () -> Unit,
    onNextChapter: (() -> Unit)?,
    onReloadChapter: () -> Unit,
    onClose: () -> Unit,
    appearance: ReaderAppearance,
    appearanceError: String?,
    onUpdateAppearance: ((ReaderAppearance) -> ReaderAppearance) -> Unit,
    superResolution: ReaderSuperResolution,
) {
    val firstPage = chapter.pages.firstOrNull() ?: return
    val position = remember(chapter.id) {
        ReadingPosition(initialAnchor ?: ReadingAnchor(firstPage.id, 0.0))
    }
    val reportAnchor by rememberUpdatedState(onAnchor)
    val persistAnchor = { reportAnchor(position.anchor()) }
    val close = {
        persistAnchor()
        onClose()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    var controlsVisible by rememberSaveable(chapter.id) { mutableStateOf(true) }
    var editingAppearance by rememberSaveable { mutableStateOf(false) }
    var previewContrast by rememberSaveable { mutableStateOf<Float?>(null) }
    val shownAppearance = previewContrast?.let(appearance::adjustContrast) ?: appearance
    val filter = remember(shownAppearance) {
        if (shownAppearance.showContrast) contrastFilter(shownAppearance.contrast) else null
    }
    val context = LocalContext.current.applicationContext
    val superResolutionSupported = superResolution.isSupported
    val superResolutionReady by superResolution.ready.collectAsStateWithLifecycle(initialValue = false)
    val enhancing = shownAppearance.showSuperResolution
    val enhancement = remember(chapter.id, superResolution, enhancing, superResolutionReady) {
        ReaderEnhancement(context, superResolution)
    }
    val canEnhance = enhancing && superResolutionReady && !enhancement.failed && !editingAppearance &&
        lifecycleState == Lifecycle.State.RESUMED
    val previews = remember(chapter.id, images) { mutableStateMapOf<String, ImageBitmap>() }
    val measured = remember(chapter.pages) { mutableStateMapOf<String, ReaderPage>() }
    val failures = remember(chapter.pages) { mutableStateMapOf<String, PageReadFailure>() }
    val missingSizes = remember(chapter.pages) { chapter.pages.count { it.width <= 0 || it.height <= 0 } }

    BackHandler(onBack = close)
    DisposableEffect(lifecycle, position) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) persistAnchor()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            persistAnchor()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width > 0 && height > 0) {
            val pages by remember(chapter.pages, width, height) { derivedStateOf {
                if (missingSizes == 0) chapter.pages else chapter.pages.map { page ->
                    if (page.width > 0 && page.height > 0) page
                    else measured[page.id] ?: page.copy(width = width, height = height)
                }
            } }
            val viewport = remember(pages, width, height) {
                val previous = Snapshot.withoutReadObservation { position.viewport?.location() }
                val layout = ReaderLayout(pages, width, height)
                val start = layout.restoreViewport(previous, initialAnchor, measuringRemotePages = missingSizes > 0)
                ReaderViewport(layout, start)
            }
            SideEffect { position.viewport = viewport }
            LaunchedEffect(viewport) {
                snapshotFlow { viewport.location() }.collect { location ->
                    val isCurrentViewport = position.viewport === viewport
                    if (isCurrentViewport && location == viewport.location()) reportAnchor(location.anchor)
                }
            }

            val pageBasedSeek = missingSizes > measured.size
            if (pageBasedSeek) {
                val nearby by remember(viewport) { derivedStateOf { viewport.nearbyPageIndexes(chapter.pages.size) } }
                val candidates = nearby.map { chapter.pages[it] }
                // 先取得目标页尺寸，再预取邻页，避免网络慢时邻页占满可用连接。
                val focus = candidates.firstOrNull()
                val requested = if (focus != null && focus.width <= 0 && measured[focus.id] == null &&
                    failures[focus.id] == null) listOf(focus) else candidates
                for (page in requested) {
                    if (page.width > 0 || measured.containsKey(page.id) || failures.containsKey(page.id)) continue
                    key(page.id) {
                        LaunchedEffect(images, page) {
                            try {
                                measured[page.id] = images.resolveRemotePage(page)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                failures[page.id] = pageReadFailure(failure)
                            }
                        }
                    }
                }
            }
            PagePreviewLoader(viewport, images, previews)
            val allowInference = if (canEnhance) rememberSuperResolutionPermission(viewport) else null
            key(viewport) {
                val recentStrips = remember(images) {
                    object : LruCache<ReaderStrip, Bitmap>(RECENT_STRIP_BYTES) {
                        override fun sizeOf(key: ReaderStrip, value: Bitmap) = value.allocationByteCount
                    }
                }
                LazyColumn(
                    state = viewport.listState,
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { controlsVisible = !controlsVisible }
                    },
                ) {
                    items(viewport.layout.stripCount) { index ->
                        val strip = remember(index) { viewport.layout.strip(index) }
                        val source = chapter.pages[strip.pageNumber - 1]
                        val reload = { persistAnchor(); onReloadChapter() }
                        if (source.width <= 0 && measured[source.id] == null) {
                            Box(Modifier.fillMaxWidth().stripHeight(strip.heightPx), contentAlignment = Alignment.Center) {
                                val failure = failures[source.id]
                                if (failure == null) CircularProgressIndicator() else {
                                    PageRetry(failure, onRetry = { failures.remove(source.id) }, onReload = reload)
                                }
                            }
                        } else {
                            val visibleRows by remember(viewport, index) {
                                derivedStateOf { viewport.enhancementRows(index) }
                            }
                            MangaStrip(
                                strip, width, images, previews[strip.page.id], recentStrips, filter,
                                enhancement, if (canEnhance) visibleRows else null,
                                allowInference = allowInference?.value == true, onReload = reload,
                            )
                        }
                    }
                }
            }
            if (controlsVisible) {
                ReaderHeader(
                    name = chapter.name,
                    onClose = close,
                    onAppearance = { editingAppearance = true },
                    onChapterList = {
                        persistAnchor()
                        onChapterList()
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            val atChapterEnd by remember(viewport) {
                derivedStateOf {
                    viewport.listState.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                        !viewport.listState.canScrollForward
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                if (atChapterEnd && onNextChapter != null) {
                    FilledTonalButton(
                        onClick = { persistAnchor(); onNextChapter() },
                        modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp),
                    ) { Text("下一章") }
                }
                if (controlsVisible) ChapterSlider(viewport, chapter.pages, pageBasedSeek, Modifier.fillMaxWidth())
            }
        }
    }
    if (editingAppearance) {
        fun updateAppearance(change: (ReaderAppearance) -> ReaderAppearance) {
            val draft = previewContrast
            onUpdateAppearance { current ->
                change(draft?.let(current::adjustContrast) ?: current)
            }
            previewContrast = null
        }
        val commit = { if (previewContrast != null) updateAppearance { it } }
        val superResolutionStatus = when {
            !superResolutionSupported -> "此设备暂不支持 2× 超分"
            enhancing && enhancement.failed -> "2× 超分暂不可用"
            enhancing && !superResolutionReady -> "设备过热，已暂缓超分"
            else -> null
        }
        AppearanceSheet(
            shownAppearance, appearanceError, superResolutionSupported, superResolutionStatus,
            onPreview = { previewContrast = it }, onCommit = commit,
            onSuperResolution = { enabled -> updateAppearance { it.setSuperResolution(enabled) } },
            onToggle = { updateAppearance(ReaderAppearance::toggleOriginal) },
            onDismiss = { commit(); editingAppearance = false },
        )
    }
}

@Composable
private fun rememberSuperResolutionPermission(viewport: ReaderViewport): State<Boolean> {
    val settled = remember(viewport) { mutableStateOf<SuperResolutionScrollSample?>(null) }
    LaunchedEffect(viewport) {
        val policy = SuperResolutionScrollPolicy()
        snapshotFlow { viewport.enhancementSample() }.collectLatest { sample ->
            settled.value = null
            val waitMs = policy.settleDelayMs(sample, SystemClock.uptimeMillis()) ?: return@collectLatest
            delay(waitMs)
            if (viewport.enhancementSample() == sample) settled.value = sample
        }
    }
    return remember(viewport, settled) {
        derivedStateOf(structuralEqualityPolicy()) {
            val ready = settled.value
            // A new viewport position can be visible before snapshotFlow delivers its next sample.
            ready != null && ready == viewport.enhancementSample()
        }
    }
}

private class ReadingPosition(private val initialAnchor: ReadingAnchor) {
    var viewport: ReaderViewport? = null

    fun anchor(): ReadingAnchor = viewport?.location()?.anchor ?: initialAnchor
}

@OptIn(ExperimentalFoundationApi::class)
private class ReaderViewport(val layout: ReaderLayout, private val initialLocation: ReaderLocation) {
    val listState = LazyListState(
        cacheWindow = LazyLayoutCacheWindow(0.5f, 0.25f),
        firstVisibleItemIndex = initialLocation.stripIndex,
        firstVisibleItemScrollOffset = initialLocation.offsetPx,
    )
    private var pendingSeek by mutableStateOf<Seek?>(null)
    private var seekRevision by mutableIntStateOf(0)
    private val isMoving: Boolean get() = pendingSeek != null || listState.isScrollInProgress

    fun location(): ReaderLocation {
        pendingSeek?.let { return it.target }
        if (listState.layoutInfo.visibleItemsInfo.isEmpty()) return initialLocation
        return layout.atStrip(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    fun previewPages(): List<ReaderPage> {
        val target = pendingSeek?.target
        if (target != null) return layout.previewPages(target.stripIndex, target.stripIndex)
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: initialLocation.stripIndex
        val last = visible.lastOrNull()?.index ?: first
        return layout.previewPages(first, last)
    }

    fun nearbyPageIndexes(count: Int): List<Int> {
        val visible = listState.layoutInfo.visibleItemsInfo
        val target = pendingSeek?.target
        val first = target?.pageNumber?.minus(1) ?: visible.firstOrNull()?.let { layout.strip(it.index).pageNumber - 1 }
            ?: (initialLocation.pageNumber - 1)
        val last = if (target != null) first else visible.lastOrNull()?.let { layout.strip(it.index).pageNumber - 1 } ?: first
        return (listOf(first) + (first + 1..minOf(last + 1, count - 1)) + listOf(first - 1)).filter { it in 0 until count }
    }

    fun enhancementRows(index: Int): IntRange? {
        if (isMoving) return null
        val layoutInfo = listState.layoutInfo
        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        val top = (layoutInfo.viewportStartOffset - item.offset).coerceAtLeast(0)
        val bottom = (layoutInfo.viewportEndOffset - item.offset).coerceAtMost(item.size)
        return (top until bottom).takeUnless(IntRange::isEmpty)
    }

    fun enhancementSample(): SuperResolutionScrollSample {
        val moving = isMoving
        val hasVisiblePosition = !moving && listState.layoutInfo.visibleItemsInfo.isNotEmpty()
        val position = if (hasVisiblePosition) {
            SuperResolutionViewportPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        } else null
        return SuperResolutionScrollSample(moving, position, seekRevision)
    }

    fun seek(location: ReaderLocation, scope: CoroutineScope) {
        val seek = Seek(location)
        pendingSeek = seek
        // An immediate jump can start and finish between two observations of the list state.
        seekRevision++
        scope.launch {
            if (pendingSeek !== seek) return@launch
            try {
                listState.scrollToItem(seek.target.stripIndex, seek.target.offsetPx)
            } finally {
                // An earlier suspended request can complete after the next slider gesture.
                if (pendingSeek === seek) pendingSeek = null
            }
        }
    }

    private class Seek(val target: ReaderLocation)
}

@Composable
private fun ReaderHeader(
    name: String,
    onClose: () -> Unit,
    onAppearance: () -> Unit,
    onChapterList: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("返回") }
            Text(
                name,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onAppearance) { Text("画质") }
            TextButton(onClick = onChapterList) { Text("目录") }
        }
    }
}

@Composable
private fun ChapterSlider(viewport: ReaderViewport, pages: List<ReaderPage>, pageBasedSeek: Boolean, modifier: Modifier) {
    val pageCount = pages.size
    val byPage = pageBasedSeek && pageCount > 1
    val scrollScope = rememberCoroutineScope()
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val current = viewport.location()
    fun target(value: Float): ReaderLocation {
        if (!byPage || value == 1f) return viewport.layout.atProgress(value.toDouble())
        val index = (value * pages.lastIndex).roundToInt().coerceIn(pages.indices)
        return viewport.layout.atAnchor(ReadingAnchor(pages[index].id, 0.0))
    }
    val preview = dragProgress?.let(::target) ?: current
    val canSeek = viewport.layout.scrollablePx > 0
    val progress = dragProgress ?: if (byPage && current.progress < 1.0) {
        (current.pageNumber - 1).toFloat() / pages.lastIndex
    } else current.progress.toFloat()
    val page = if (byPage && dragProgress != null) (progress * pages.lastIndex).roundToInt() + 1
        else if (preview.progress == 1.0) pageCount else preview.pageNumber
    val label = if (pageCount == 1 && canSeek) {
        "本章 ${(progress * 100).roundToInt()}%"
    } else {
        "第 $page / $pageCount 页"
    }

    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Column(
            Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Slider(
                value = progress,
                onValueChange = { dragProgress = it },
                onValueChangeFinished = {
                    dragProgress?.let { viewport.seek(target(it), scrollScope) }
                    dragProgress = null
                },
                enabled = canSeek,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "章节进度" },
            )
        }
    }
}

private sealed interface StripImage {
    data object Loading : StripImage
    data class Failed(val failure: PageReadFailure) : StripImage
    data class Ready(val bitmap: ImageBitmap) : StripImage
}

@Composable
private fun PagePreviewLoader(
    viewport: ReaderViewport,
    images: LocalImages,
    previews: MutableMap<String, ImageBitmap>,
) {
    val pages by remember(viewport) { derivedStateOf(structuralEqualityPolicy()) { viewport.previewPages() } }
    val previewSlot = remember(images) { Semaphore(1) }
    for (page in pages) {
        key(page.id) {
            DisposableEffect(images, page) {
                onDispose { previews.remove(page.id) }
            }
            LaunchedEffect(images, page) {
                val bitmap = try {
                    // Leave the other decode slot available for on-screen detail.
                    previewSlot.withPermit { images.decode(page, 0, page.height, PAGE_PREVIEW_WIDTH_PX) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@LaunchedEffect
                }
                previews[page.id] = bitmap.asImageBitmap()
            }
        }
    }
}

@Composable
private fun MangaStrip(
    strip: ReaderStrip,
    width: Int,
    images: LocalImages,
    preview: ImageBitmap?,
    recentStrips: LruCache<ReaderStrip, Bitmap>,
    colorFilter: ColorFilter?,
    enhancement: ReaderEnhancement,
    visibleRows: IntRange?,
    allowInference: Boolean,
    onReload: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val cached = remember(strip, recentStrips) { if (strip.keepRecentBitmap) recentStrips.get(strip) else null }
    val image by key(images, strip, width, attempt) {
        val initial = cached?.let { StripImage.Ready(it.asImageBitmap()) } ?: StripImage.Loading
        produceState<StripImage>(initial) {
            if (cached != null) return@produceState
            value = try {
                val bitmap = images.decode(strip.page, strip.sourceTop, strip.sourceBottom, width)
                if (strip.keepRecentBitmap) recentStrips.put(strip, bitmap)
                StripImage.Ready(bitmap.asImageBitmap())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                StripImage.Failed(pageReadFailure(failure))
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth().stripHeight(strip.heightPx),
        contentAlignment = Alignment.Center,
    ) {
        val pageDescription = if (strip.sourceTop == 0) "第 ${strip.pageNumber} 页" else null
        when (val displayed = image) {
            is StripImage.Ready -> {
                Image(
                    bitmap = displayed.bitmap,
                    contentDescription = pageDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = colorFilter,
                )
                if (visibleRows != null) {
                    SuperResolutionLayer(strip, visibleRows, images, enhancement, colorFilter, allowInference)
                }
            }
            else -> {
                if (preview != null) MangaStripPreview(strip, preview, pageDescription, colorFilter)
                if (displayed is StripImage.Failed) {
                    PageRetry(displayed.failure, onRetry = { attempt++ }, onReload = onReload)
                }
            }
        }
    }
}

private data class PageReadFailure(val message: String, val chapterChanged: Boolean)

private fun pageReadFailure(failure: Exception) = PageReadFailure(
    failure.localizedMessage ?: "无法读取此页。",
    chapterChanged = failure is RemoteContentChanged,
)

@Composable
private fun PageRetry(failure: PageReadFailure, onRetry: () -> Unit, onReload: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(failure.message, color = Color.White)
        if (failure.chapterChanged) {
            TextButton(onClick = onReload) { Text("重新打开章节") }
        } else {
            TextButton(onClick = onRetry) { Text("重试此页") }
        }
    }
}

@Composable
private fun MangaStripPreview(strip: ReaderStrip, preview: ImageBitmap, description: String?, colorFilter: ColorFilter?) {
    val slice = remember(strip, preview) { strip.previewSlice(preview.height) }
    val semantics = if (description == null) Modifier else Modifier.semantics { contentDescription = description }
    Canvas(Modifier.fillMaxSize().then(semantics)) {
        val scaleY = size.height / slice.visibleRows
        clipRect {
            withTransform({
                translate(top = (-slice.topWithinSlice * scaleY).toFloat())
                scale(size.width / preview.width, scaleY.toFloat(), Offset.Zero)
            }) {
                val cropSize = IntSize(preview.width, slice.rowCount)
                drawImage(
                    image = preview,
                    srcOffset = IntOffset(0, slice.firstRow),
                    srcSize = cropSize,
                    dstSize = cropSize,
                    filterQuality = FilterQuality.Low,
                    colorFilter = colorFilter,
                )
            }
        }
    }
}

private fun Modifier.stripHeight(heightPx: Int) = layout { measurable, constraints ->
    // Preserve the integer strip boundaries used by the progress and anchor calculations.
    val placeable = measurable.measure(constraints.copy(minHeight = heightPx, maxHeight = heightPx))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}
