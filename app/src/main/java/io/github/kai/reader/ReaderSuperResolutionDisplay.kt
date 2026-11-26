package io.github.kai.reader

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val ENHANCEMENT_CACHE_BYTES = 16 * 1024 * 1024
private const val ENHANCEMENT_LOG = "KaiSuperResolution"
private const val FIRST_BAND_TARGET_PIXELS = 24 * 1024
private const val FILTER_CONTEXT_ROWS = 1
private const val MAX_EXTRA_INFERENCE_PERCENT = 15

internal data class SuperResolutionRows(val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top

    fun overlaps(other: SuperResolutionRows): Boolean = top < other.bottom && bottom > other.top
}

internal data class SuperResolutionRegion(
    val top: Int,
    val bottom: Int,
    val paddedTop: Int,
    val paddedBottom: Int,
    val targetWidth: Int,
    val sampleSize: Int,
)

internal fun superResolutionRegions(strip: ReaderStrip, visibleRows: IntRange): List<SuperResolutionRegion> {
    if (visibleRows.isEmpty()) return emptyList()
    val width = strip.page.width
    var sample = 1
    while ((width - 1) / sample + 1 > SUPER_RESOLUTION_MAX_EDGE) sample *= 2
    val sampledWidth = (width - 1) / sample + 1
    val targetWidth = width / sample
    val paddedRows = SUPER_RESOLUTION_CONTEXT * sample
    val alignment = maxOf(2, sample)
    val sampledHeightLimit = minOf(SUPER_RESOLUTION_MAX_EDGE, SUPER_RESOLUTION_MAX_INPUT_PIXELS / sampledWidth)
    // Align the decoder to a single sample grid, including WebP's even top rows, across adjacent strips.
    val sourceRows = (sampledHeightLimit - 2 * SUPER_RESOLUTION_CONTEXT) * sample - alignment
    val rowsPerRegion = sourceRows / alignment * alignment
    val visible = strip.sourceRows(visibleRows)
    return buildList {
        var row = strip.sourceTop + (visible.top - strip.sourceTop) / rowsPerRegion * rowsPerRegion
        while (row < visible.bottom) {
            val bottom = minOf(row.toLong() + rowsPerRegion, strip.sourceBottom.toLong()).toInt()
            val topWithContext = (row - paddedRows).coerceAtLeast(0)
            val bottomWithContext = (bottom.toLong() + paddedRows + alignment - 1) / alignment * alignment
            add(SuperResolutionRegion(
                top = row,
                bottom = bottom,
                paddedTop = topWithContext - topWithContext % alignment,
                paddedBottom = minOf(bottomWithContext, strip.page.height.toLong()).toInt(),
                targetWidth = targetWidth,
                sampleSize = sample,
            ))
            row = bottom
        }
    }
}

private fun ReaderStrip.sourceRows(visibleRows: IntRange): SuperResolutionRows {
    val height = sourceBottom - sourceTop
    val top = visibleRows.first.toLong() * height / heightPx
    val bottom = ((visibleRows.last + 1L) * height + heightPx - 1) / heightPx
    return SuperResolutionRows(sourceTop + top.toInt(), sourceTop + bottom.toInt())
}

internal fun SuperResolutionRegion.bands(visible: SuperResolutionRows): List<SuperResolutionRows> {
    val wholeRegion = SuperResolutionRows(top, bottom)
    val decodedHeight = (paddedBottom - paddedTop - 1) / sampleSize + 1
    val alignment = maxOf(2, sampleSize)
    val sampledRows = minOf(SUPER_RESOLUTION_TILE_SIZE, FIRST_BAND_TARGET_PIXELS / targetWidth) - 2 * FILTER_CONTEXT_ROWS
    val firstRows = (sampledRows * sampleSize / alignment * alignment).coerceAtLeast(alignment)
    val canSplit = decodedHeight > SUPER_RESOLUTION_TILE_SIZE &&
        wholeRegion.height >= firstRows + maxOf(firstRows / 4, alignment)
    if (!canSplit) return listOf(wholeRegion)

    // Give the first band room to show detail past the top edge while fitting in one native tile row.
    val firstOffset = minOf(wholeRegion.height / 3, wholeRegion.height - firstRows) / alignment * alignment
    val first = SuperResolutionRows(top + firstOffset, top + firstOffset + firstRows)
    val bands = buildList {
        add(first)
        if (first.top > top) add(SuperResolutionRows(top, first.top))
        if (first.bottom < bottom) add(SuperResolutionRows(first.bottom, bottom))
    }
    // A short group can spend a disproportionate share of its work reprocessing overlap.
    val wholeWork = estimatedInferenceRows(decodedHeight)
    val splitWork = bands.sumOf { estimatedInferenceRows(outputRows(it, decodedHeight).height) }
    if (splitWork > wholeWork + wholeWork * MAX_EXTRA_INFERENCE_PERCENT / 100) return listOf(wholeRegion)
    return bands.sortedBy { !it.overlaps(visible) }
}

private fun estimatedInferenceRows(height: Int): Int {
    val tileRows = (height - 1) / SUPER_RESOLUTION_TILE_SIZE + 1
    return height + tileRows * 2 * SUPER_RESOLUTION_CONTEXT
}

internal fun SuperResolutionRegion.outputRows(band: SuperResolutionRows, decodedHeight: Int): SuperResolutionRows {
    val paddedHeight = (paddedBottom - paddedTop).toLong()
    val top = (band.top - paddedTop).toLong() * decodedHeight / paddedHeight
    val bottom = ((band.bottom - paddedTop).toLong() * decodedHeight + paddedHeight - 1) / paddedHeight
    // Keep neighboring texels for bilinear filtering with the same transform as the full decode.
    return SuperResolutionRows(
        (top.toInt() - FILTER_CONTEXT_ROWS).coerceAtLeast(0),
        (bottom.toInt() + FILTER_CONTEXT_ROWS).coerceAtMost(decodedHeight),
    )
}

internal data class EnhancedBand(val bitmap: Bitmap, val sampledTop: Int, val decodedHeight: Int)

internal class ReaderEnhancement(context: Context, private val engine: ReaderSuperResolution) {
    private val testBuild = context.resources.getBoolean(R.bool.kai_super_resolution_fixture)
    private val failureSwitch = if (testBuild) File(context.noBackupFilesDir, "kai-force-super-resolution-failure") else null
    private val work = Mutex()
    private val cache =
        object : LruCache<Triple<ReaderPage, SuperResolutionRegion, SuperResolutionRows>, EnhancedBand>(
            ENHANCEMENT_CACHE_BYTES,
        ) {
            override fun sizeOf(
                key: Triple<ReaderPage, SuperResolutionRegion, SuperResolutionRows>,
                value: EnhancedBand,
            ) = value.bitmap.allocationByteCount
        }
    var failed by mutableStateOf(false)
        private set

    fun ready(page: ReaderPage, region: SuperResolutionRegion, band: SuperResolutionRows): EnhancedBand? =
        cache.get(Triple(page, region, band))

    suspend fun enhance(
        images: LocalImages,
        strip: ReaderStrip,
        region: SuperResolutionRegion,
        bands: List<SuperResolutionRows>,
        publish: (SuperResolutionRows, EnhancedBand) -> Unit,
    ): Unit = work.withLock {
        if (failed) return@withLock
        val page = strip.page
        val missing = bands.filter { band ->
            val cached = ready(page, region, band)
            if (cached != null) publish(band, cached)
            cached == null
        }
        if (missing.isEmpty()) return@withLock

        val area = "page=${strip.pageNumber} top=${region.top} bottom=${region.bottom}"
        val start = SystemClock.elapsedRealtime()
        if (testBuild) Log.d(ENHANCEMENT_LOG, "start $area")
        var source: Bitmap? = null
        try {
            if (failureSwitch?.isFile == true) {
                fail(area)
                return@withLock
            }

            val decoded = images.decode(page, region.paddedTop, region.paddedBottom, region.targetWidth).also { source = it }
            for (band in missing) {
                val rows = region.outputRows(band, decoded.height)
                val bitmap = engine.enhance(decoded, rows)
                val context = currentCoroutineContext()
                if (!context.isActive) bitmap?.recycle()
                context.ensureActive()
                if (bitmap == null) {
                    fail(area)
                    return@withLock
                }
                val result = EnhancedBand(bitmap, rows.top, decoded.height)
                cache.put(Triple(page, region, band), result)
                publish(band, result)
                if (testBuild) Log.d(ENHANCEMENT_LOG,
                    "published page=${strip.pageNumber} top=${band.top} bottom=${band.bottom} " +
                        "output=${bitmap.width}x${bitmap.height} elapsed_ms=${SystemClock.elapsedRealtime() - start} " +
                        "region_top=${region.top} region_bottom=${region.bottom}",
                )
            }
            if (testBuild) Log.d(ENHANCEMENT_LOG,
                "enhanced $area output=${decoded.width * SUPER_RESOLUTION_SCALE}x${decoded.height * SUPER_RESOLUTION_SCALE} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - start}",
            )
        } catch (cancelled: CancellationException) {
            if (testBuild) Log.d(ENHANCEMENT_LOG, "cancelled $area")
            throw cancelled
        } catch (_: Exception) {
            fail(area)
        } catch (_: OutOfMemoryError) {
            fail(area)
        } catch (_: LinkageError) {
            fail(area)
        } finally {
            source?.recycle()
        }
    }

    private fun fail(area: String) {
        failed = true
        if (testBuild) Log.d(ENHANCEMENT_LOG, "fallback $area")
    }
}

@Composable
internal fun SuperResolutionLayer(
    strip: ReaderStrip,
    visibleRows: IntRange,
    images: LocalImages,
    enhancement: ReaderEnhancement,
    colorFilter: ColorFilter?,
    allowInference: Boolean,
) {
    val regions = remember(strip, visibleRows) { superResolutionRegions(strip, visibleRows) }
    val visible = remember(strip, visibleRows) { strip.sourceRows(visibleRows) }
    for (region in regions) {
        key(strip.page, region, enhancement) {
            val bands = remember(region, visible) { region.bands(visible) }
            val processed = remember {
                mutableStateMapOf<SuperResolutionRows, EnhancedBand>().apply {
                    for (band in bands) enhancement.ready(strip.page, region, band)?.let { put(band, it) }
                }
            }
            if (allowInference) {
                LaunchedEffect(images, bands) {
                    enhancement.enhance(images, strip, region, bands) { band, result -> processed[band] = result }
                }
            }
            for (band in bands) {
                key(band) {
                    val result = processed[band]
                    if (result != null) EnhancedRegion(strip, region, band, result, colorFilter)
                }
            }
        }
    }
}

@Composable
private fun EnhancedRegion(
    strip: ReaderStrip,
    region: SuperResolutionRegion,
    band: SuperResolutionRows,
    result: EnhancedBand,
    colorFilter: ColorFilter?,
) {
    val bitmap = remember(result) { result.bitmap.asImageBitmap() }
    Canvas(Modifier.fillMaxSize()) {
        val rowScale = size.height / (strip.sourceBottom - strip.sourceTop)
        val top = (band.top - strip.sourceTop) * rowScale
        val bottom = (band.bottom - strip.sourceTop) * rowScale
        val paddedOrigin = (region.paddedTop - strip.sourceTop) * rowScale
        val paddedHeight = (region.paddedBottom - region.paddedTop) * rowScale
        val sampleScale = paddedHeight / result.decodedHeight
        val pixels = IntSize(bitmap.width, bitmap.height)
        clipRect(top = top, bottom = bottom) {
            // Transparent pixels replace the original strip's pixels over the reader's black background.
            drawRect(Color.Black)
            withTransform({
                translate(top = paddedOrigin + result.sampledTop * sampleScale)
                scale(size.width / pixels.width, sampleScale / SUPER_RESOLUTION_SCALE, Offset.Zero)
            }) {
                drawImage(bitmap, srcSize = pixels, dstSize = pixels, filterQuality = FilterQuality.Low, colorFilter = colorFilter)
            }
        }
    }
}
