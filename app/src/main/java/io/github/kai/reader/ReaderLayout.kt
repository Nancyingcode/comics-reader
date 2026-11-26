package io.github.kai.reader

import kotlin.math.ceil
import kotlin.math.roundToLong

private const val MAX_STRIP_HEIGHT_PX = 1536
private const val MAX_STRIP_PIXELS = 1024 * 1024
private const val MAX_JPEG_PREVIEW_SOURCE_PIXELS = 64L * 1024 * 1024
private const val MAX_OTHER_PREVIEW_SOURCE_PIXELS = 4L * 1024 * 1024

internal data class ReaderStrip(
    val page: ReaderPage,
    val pageNumber: Int,
    val sourceTop: Int,
    val sourceBottom: Int,
    val heightPx: Int,
    val keepRecentBitmap: Boolean,
) {
    fun previewSlice(previewHeight: Int): ReaderPreviewSlice {
        val previewRowsPerSourceRow = previewHeight.toDouble() / page.height
        val top = sourceTop * previewRowsPerSourceRow
        val bottom = sourceBottom * previewRowsPerSourceRow
        // Include neighboring texels so filtering stays continuous between clipped strips.
        val firstRow = (top.toInt() - 1).coerceAtLeast(0)
        val endRow = (ceil(bottom).toInt() + 1).coerceAtMost(previewHeight)
        return ReaderPreviewSlice(firstRow, endRow - firstRow, top - firstRow, bottom - top)
    }
}

internal data class ReaderPreviewSlice(
    val firstRow: Int,
    val rowCount: Int,
    val topWithinSlice: Double,
    val visibleRows: Double,
)

internal data class ReaderLocation(
    val stripIndex: Int,
    val offsetPx: Int,
    val pageNumber: Int,
    val anchor: ReadingAnchor,
    val progress: Double,
)

internal class ReaderLayout(
    pages: List<ReaderPage>,
    val widthPx: Int,
    val viewportHeightPx: Int,
) {
    init {
        require(pages.isNotEmpty())
        require(widthPx > 0 && viewportHeightPx > 0)
    }

    private val stripHeightPx = minOf(
        viewportHeightPx,
        MAX_STRIP_HEIGHT_PX,
        (MAX_STRIP_PIXELS / widthPx).coerceAtLeast(1),
    )
    private val spans: List<PageSpan> = buildList {
        var startPx = 0L
        var firstStrip = 0
        pages.forEachIndexed { index, page ->
            val span = PageSpan(page, index + 1, widthPx, stripHeightPx, viewportHeightPx, startPx, firstStrip)
            add(span)
            startPx = Math.addExact(startPx, span.heightPx)
            firstStrip = Math.addExact(firstStrip, span.stripCount)
        }
    }
    private val spansById = spans.associateBy { it.page.id }
    val stripCount = spans.last().let { it.firstStrip + it.stripCount }
    val scrollablePx = spans.last().let { (it.startPx + it.heightPx - viewportHeightPx).coerceAtLeast(0) }

    fun strip(index: Int): ReaderStrip {
        val span = pageAtStrip(index)
        val stripInPage = index - span.firstStrip
        val top = span.sourceEdge(stripInPage)
        val bottom = span.sourceEdge(stripInPage + 1)
        return ReaderStrip(
            page = span.page,
            pageNumber = span.pageNumber,
            sourceTop = top,
            sourceBottom = bottom,
            heightPx = (span.displayEdge(stripInPage + 1) - span.displayEdge(stripInPage)).toInt(),
            keepRecentBitmap = span.isLongPage && !span.supportsPreview,
        )
    }

    fun previewPages(firstStrip: Int, lastStrip: Int): List<ReaderPage> {
        val firstPage = pageAtStrip(firstStrip).pageNumber - 1
        val lastPage = pageAtStrip(lastStrip).pageNumber - 1
        return buildList {
            for (index in firstPage..lastPage) {
                val span = spans[index]
                if (span.supportsPreview) add(span.page)
            }
            spans.getOrNull(firstPage - 1)?.takeIf { it.supportsPreview }?.let { add(it.page) }
            spans.getOrNull(lastPage + 1)?.takeIf { it.supportsPreview }?.let { add(it.page) }
        }
    }

    fun atStrip(index: Int, offsetPx: Int): ReaderLocation {
        val span = pageAtStrip(index)
        val stripTop = span.displayEdge(index - span.firstStrip)
        return atPixels(span.startPx + stripTop + offsetPx)
    }

    fun atProgress(progress: Double): ReaderLocation =
        atPixels((progress.unitFraction() * scrollablePx).roundToLong())

    fun restoreViewport(previous: ReaderLocation?, initial: ReadingAnchor?, measuringRemotePages: Boolean): ReaderLocation {
        // 动态补齐最后一页尺寸时仍须贴住章底。阅读途中的落点始终以页 ID 和页内位置为准。
        if (measuringRemotePages && previous?.progress == 1.0) return atProgress(1.0)
        return atAnchor(previous?.anchor ?: initial)
    }

    fun atAnchor(anchor: ReadingAnchor?): ReaderLocation {
        if (anchor == null) return atPixels(0)
        val span = spansById[anchor.pageId] ?: return atPixels(0)
        val offset = (anchor.fraction.unitFraction() * span.heightPx).roundToLong()
        return atPixels(span.startPx + offset.coerceAtMost(span.heightPx - 1))
    }

    private fun atPixels(pixels: Long): ReaderLocation {
        val position = pixels.coerceIn(0L, scrollablePx)
        val result = spans.binarySearchBy(position) { it.startPx }
        val span = spans[if (result >= 0) result else -result - 2]
        val yInPage = position - span.startPx
        val stripInPage = span.stripAt(yInPage)
        return ReaderLocation(
            stripIndex = span.firstStrip + stripInPage,
            offsetPx = (yInPage - span.displayEdge(stripInPage)).toInt(),
            pageNumber = span.pageNumber,
            anchor = ReadingAnchor(span.page.id, yInPage.toDouble() / span.heightPx),
            progress = if (scrollablePx == 0L) 0.0 else position.toDouble() / scrollablePx,
        )
    }

    private fun pageAtStrip(index: Int): PageSpan {
        val result = spans.binarySearchBy(index) { it.firstStrip }
        return spans[if (result >= 0) result else -result - 2]
    }
}

private class PageSpan(
    val page: ReaderPage,
    val pageNumber: Int,
    private val widthPx: Int,
    stripHeightPx: Int,
    viewportHeightPx: Int,
    val startPx: Long,
    val firstStrip: Int,
) {
    init {
        require(page.width > 0 && page.height > 0)
    }

    val heightPx = projectedHeight(page.height).coerceAtLeast(1)
    val isLongPage = heightPx > 2L * viewportHeightPx
    private val previewLimit = if (page.isBaselineJpeg) MAX_JPEG_PREVIEW_SOURCE_PIXELS else MAX_OTHER_PREVIEW_SOURCE_PIXELS
    val supportsPreview = isLongPage && page.width.toLong() * page.height <= previewLimit
    private val rowsPerStrip = stripRows(stripHeightPx)
    val stripCount: Int = run {
        val rawCount = ((page.height - 1) / rowsPerStrip + 1).toInt()
        val lastTop = (rawCount - 1) * rowsPerStrip
        // Merge a tail that would round to zero display pixels, keeping all its source rows.
        if (rawCount > 1 && projectedHeight(lastTop.toInt()) == heightPx) rawCount - 1 else rawCount
    }

    fun sourceEdge(edge: Int): Int = if (edge == stripCount) page.height else (edge * rowsPerStrip).toInt()

    fun displayEdge(edge: Int): Long = if (edge == stripCount) heightPx else projectedHeight(sourceEdge(edge))

    fun stripAt(yInPage: Long): Int {
        var first = 0
        var last = stripCount - 1
        while (first < last) {
            val middle = first + (last - first + 1) / 2
            if (displayEdge(middle) <= yInPage) first = middle else last = middle - 1
        }
        return first
    }

    private fun projectedHeight(rows: Int): Long =
        (rows.toLong() * widthPx + page.width / 2) / page.width

    private fun stripRows(stripHeightPx: Int): Long {
        if (heightPx <= stripHeightPx) return page.height.toLong()
        val rowsForOnePixel = (page.width.toLong() + widthPx - 1) / widthPx
        val rowsAtTargetHeight = (stripHeightPx.toLong() * page.width / widthPx).coerceAtLeast(1)
        val largestRegion = (rowsAtTargetHeight + rowsForOnePixel).coerceAtMost(page.height.toLong()).toInt()
        // WebP region decoding rounds odd starting rows down, even without subsampling.
        val sample = boundedImageSampleSize(page.width, largestRegion, widthPx)
        val alignment = maxOf(2, sample)
        val alignedRows = rowsAtTargetHeight / alignment * alignment
        val minimumAlignedRows = (rowsForOnePixel + alignment - 1) / alignment * alignment
        return maxOf(alignedRows, minimumAlignedRows)
    }
}

private fun Double.unitFraction(): Double = if (isFinite()) coerceIn(0.0, 1.0) else 0.0
