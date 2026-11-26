package io.github.kai.reader

import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLayoutTest {
    @Test
    fun measuringRemotePagesPreservesAnInteriorAnchorAndAnExplicitChapterEnd() {
        val estimated = (1..42).map { ReaderPage("page-$it", "$it.png", 960, 1600) }
        val original = ReaderLayout(estimated, 960, 1600)
        val pages = estimated.toMutableList().apply {
            this[0] = first().copy(height = 3500)
            this[41] = last().copy(height = 8200)
        }
        val measured = ReaderLayout(pages, 960, 1600)
        val interior = original.atAnchor(ReadingAnchor("page-18", 0.375))
        val restored = measured.restoreViewport(interior, null, measuringRemotePages = true)
        assertEquals(interior.anchor, restored.anchor)
        val end = measured.restoreViewport(original.atProgress(1.0), null, measuringRemotePages = true)
        assertEquals(measured.atProgress(1.0), end)
        assertEquals("page-42", end.anchor.pageId)
    }

    @Test
    fun stripsCoverAllSourceRowsAndProjectToTheExactPageHeight() {
        val page = ReaderPage("odd", "odd.webp", width = 3001, height = 20_803)
        val width = 1080
        val layout = ReaderLayout(listOf(page), width, 1799)
        val strips = (0 until layout.stripCount).map(layout::strip)

        assertTrue(strips.size > 1)
        assertEquals(0, strips.first().sourceTop)
        assertEquals(page.height, strips.last().sourceBottom)
        strips.zipWithNext().forEach { (current, next) ->
            assertEquals(current.sourceBottom, next.sourceTop)
        }
        strips.forEach { strip ->
            assertTrue(strip.heightPx > 0)
            val sample = boundedImageSampleSize(page.width, strip.sourceBottom - strip.sourceTop, width)
            assertEquals(0, strip.sourceTop % sample)
        }
        val projectedHeight = (page.height.toDouble() * width / page.width).roundToLong()
        assertEquals(projectedHeight, strips.sumOf { it.heightPx }.toLong())
    }

    @Test
    fun aSubpixelTailRemainsPartOfTheLastVisibleStrip() {
        val page = ReaderPage("tail", "tail.png", width = 4000, height = 8001)
        val layout = ReaderLayout(listOf(page), 1000, 1000)

        assertEquals(2, layout.stripCount)
        assertEquals(4000, layout.strip(1).sourceTop)
        assertEquals(page.height, layout.strip(1).sourceBottom)
        assertEquals(1000, layout.strip(1).heightPx)
    }

    @Test
    fun webpStripStartsAreEvenAtFullResolution() {
        val page = ReaderPage("webp", "long.webp", width = 800, height = 5001)
        val layout = ReaderLayout(listOf(page), 800, 733)
        val strips = (0 until layout.stripCount).map(layout::strip)

        assertTrue(strips.size > 1)
        assertTrue(strips.all { it.sourceTop % 2 == 0 })
        assertEquals(page.height, strips.last().sourceBottom)
    }

    @Test
    fun mixedPageHeightsMapProgressToTheRightPageAndTheChapterBottom() {
        val pages = (1..42).map { number ->
            ReaderPage("page-$number", "$number.jpg", width = 1000, height = 730 + number * 31)
        }
        val viewportHeight = 670
        val layout = ReaderLayout(pages, 1000, viewportHeight)
        val totalHeight = pages.sumOf { it.height }.toLong()
        val page18 = pages[17]
        val page18Offset = pages.take(17).sumOf { it.height } + 123

        val target = layout.atProgress(page18Offset.toDouble() / layout.scrollablePx)
        assertEquals(totalHeight - viewportHeight, layout.scrollablePx)
        assertEquals(18, target.pageNumber)
        assertEquals(page18.id, target.anchor.pageId)
        assertEquals(123.0 / page18.height, target.anchor.fraction, 0.0)
        assertEquals(target, layout.atStrip(target.stripIndex, target.offsetPx))

        val end = layout.atProgress(1.0)
        assertEquals(42, end.pageNumber)
        assertEquals(1.0, end.progress, 0.0)
        assertEquals((pages.last().height - viewportHeight).toDouble() / pages.last().height, end.anchor.fraction, 0.0)
    }

    @Test
    fun aSingleLongImageRestoresItsAnchorAfterRotation() {
        val page = ReaderPage("long", "long.png", width = 1234, height = 20_125)
        val portrait = ReaderLayout(listOf(page), 1080, 2200)
        val landscape = ReaderLayout(listOf(page), 2200, 1080)

        val target = portrait.atProgress(0.63)
        val reopened = portrait.atAnchor(target.anchor)
        val rotated = landscape.atAnchor(target.anchor)
        assertEquals(target, reopened)
        assertEquals(page.id, rotated.anchor.pageId)
        val sourceRowDrift = (rotated.anchor.fraction - target.anchor.fraction) * page.height
        assertEquals(0.0, sourceRowDrift, 1.0)
        assertEquals(rotated, landscape.atStrip(rotated.stripIndex, rotated.offsetPx))
    }

    @Test
    fun anchorsSurvivePageReorderingAndShortChaptersDoNotScroll() {
        val first = ReaderPage("first", "1.png", width = 1000, height = 1000)
        val second = ReaderPage("second", "2.png", width = 1000, height = 1000)
        val reordered = ReaderLayout(listOf(second, first), 1000, 500)
        val anchor = ReadingAnchor(first.id, 0.25)
        assertEquals(2, reordered.atAnchor(anchor).pageNumber)
        assertEquals(anchor, reordered.atAnchor(anchor).anchor)
        assertEquals(second.id, reordered.atAnchor(ReadingAnchor("missing", 0.5)).anchor.pageId)

        val short = ReaderLayout(listOf(first), 100, 500)
        assertEquals(0L, short.scrollablePx)
        assertEquals(short.atProgress(0.0), short.atProgress(1.0))
    }

    @Test
    fun wholePagePreviewsStayLocalToTheViewportAndRespectSourceBudgets() {
        val jpeg = ReaderPage("jpeg", "long.jpg", width = 960, height = 64_800, isBaselineJpeg = true)
        val smallPng = ReaderPage("small-png", "thin.png", width = 200, height = 8000)
        val shortPage = ReaderPage("short", "regular.jpg", width = 960, height = 1200)
        val bigPng = ReaderPage("big-png", "large.png", width = 960, height = 64_800)
        val bigJpeg = ReaderPage("big-jpeg", "too-large.jpg", width = 960, height = 90_000, isBaselineJpeg = true)
        val layout = ReaderLayout(listOf(jpeg, smallPng, shortPage, bigPng, bigJpeg), 960, 1500)
        val firstStrips = (0 until layout.stripCount).groupBy { layout.strip(it).page.id }.mapValues { it.value.first() }
        val nearSmallPng = firstStrips.getValue(smallPng.id)
        val nearBigPng = firstStrips.getValue(bigPng.id)
        val nearBigJpeg = firstStrips.getValue(bigJpeg.id)

        assertEquals(listOf(smallPng, jpeg), layout.previewPages(nearSmallPng, nearSmallPng))
        assertTrue(layout.previewPages(nearBigPng, nearBigJpeg).isEmpty())
        assertTrue(layout.strip(nearBigPng).keepRecentBitmap)
        assertTrue(layout.strip(nearBigJpeg).keepRecentBitmap)
    }

    @Test
    fun previewStripsShareFractionalEdgesEvenWithOnlyOnePreviewRow() {
        val page = ReaderPage("thin", "thin.png", width = 200, height = 8001)
        val layout = ReaderLayout(listOf(page), 960, 1500)
        val slices = (0 until layout.stripCount).map { layout.strip(it).previewSlice(previewHeight = 1) }

        assertTrue(slices.size > 1)
        assertTrue(slices.all { it.firstRow == 0 && it.rowCount == 1 && it.visibleRows > 0 })
        slices.zipWithNext().forEach { (current, next) ->
            assertEquals(current.topWithinSlice + current.visibleRows, next.topWithinSlice, 1e-12)
        }
        assertEquals(0.0, slices.first().topWithinSlice, 0.0)
        val last = slices.last()
        assertEquals(1.0, last.topWithinSlice + last.visibleRows, 1e-12)
    }
}
