package io.github.kai.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSuperResolutionDisplayTest {
    @Test
    fun partialViewportSelectsOnlyItsOverlappingRegions() {
        val strip = ReaderStrip(ReaderPage("page", "page", 384, 1800), 1, 200, 1600, 1400, false)
        val visible = 710 until 900

        val regions = superResolutionRegions(strip, visible)

        assertEquals(1, regions.size)
        val region = regions.single()
        assertTrue(region.top <= 910)
        assertTrue(region.bottom >= 1100)
        assertEquals(SUPER_RESOLUTION_CONTEXT, region.top - region.paddedTop)
        assertEquals(SUPER_RESOLUTION_CONTEXT, region.paddedBottom - region.bottom)
    }

    @Test
    fun wideOddSourceKeepsTheSameAlignedGridAndCropsAtPageBoundaries() {
        val page = ReaderPage("page", "page", 2049, 2419)
        val first = ReaderStrip(page, 1, 0, 1210, 620, false)
        val next = ReaderStrip(page, 1, 1210, 2419, 618, false)
        val regions = superResolutionRegions(first, 0 until first.heightPx) +
            superResolutionRegions(next, 0 until next.heightPx)
        val sample = imageSampleSize(page.width, regions.first().targetWidth)
        val sampledWidth = (page.width - 1) / sample + 1

        assertEquals(0, regions.first().paddedTop)
        assertEquals(page.height, regions.last().paddedBottom)
        for (region in regions) {
            val height = (region.paddedBottom - region.paddedTop - 1) / sample + 1
            assertEquals(0, region.paddedTop % sample)
            if (region.paddedBottom < page.height) assertEquals(0, region.paddedBottom % sample)
            assertTrue(sampledWidth <= SUPER_RESOLUTION_MAX_EDGE)
            assertTrue(height <= SUPER_RESOLUTION_MAX_EDGE)
            assertTrue(sampledWidth * height <= SUPER_RESOLUTION_MAX_INPUT_PIXELS)
        }
        for ((left, right) in regions.zipWithNext()) {
            assertEquals(left.bottom, right.top)
            assertTrue((left.paddedBottom - right.paddedTop) / sample >= 2 * SUPER_RESOLUTION_CONTEXT)
        }
    }

    @Test
    fun firstBandCoversASingleNativeTileRowAndBandSizesStayStableAcrossViewports() {
        val page = ReaderPage("page", "page", 384, 1152)
        val strip = ReaderStrip(page, 1, 0, 130, 593, false)
        val region = superResolutionRegions(strip, 0 until strip.heightPx).single()

        val bands = region.bands(SuperResolutionRows(0, 130))
        val first = bands.first()
        val firstOutput = region.outputRows(first, region.paddedBottom - region.paddedTop)

        assertEquals(SuperResolutionRows(42, 104), first)
        assertEquals(SUPER_RESOLUTION_TILE_SIZE, firstOutput.height)
        assertEquals(
            listOf(SuperResolutionRows(0, 42), first, SuperResolutionRows(104, 130)),
            bands.sortedBy { it.top },
        )
        val nearEnd = region.bands(SuperResolutionRows(112, 130))
        assertEquals(SuperResolutionRows(104, 130), nearEnd.first())
        assertEquals(bands.toSet(), nearEnd.toSet())
    }

    @Test
    fun shortWidePageUsesOneBandToAvoidRepeatingNativeContext() {
        val page = ReaderPage("page", "page", 2048, 55)
        val strip = ReaderStrip(page, 1, 0, 55, 47, false)
        val region = superResolutionRegions(strip, 0 until strip.heightPx).single()
        val allRows = SuperResolutionRows(0, 55)

        assertEquals(listOf(allRows), region.bands(allRows))
    }

    @Test
    fun bandsKeepFilteringNeighborsAtOddSampledEdges() {
        val page = ReaderPage("page", "page", 2049, 2419)
        val strip = ReaderStrip(page, 1, 2120, 2419, 292, false)
        val region = superResolutionRegions(strip, 0 until strip.heightPx).single()
        val bands = region.bands(SuperResolutionRows(strip.sourceTop, strip.sourceBottom)).sortedBy { it.top }
        val paddedHeight = region.paddedBottom - region.paddedTop
        val sampledHeight = (paddedHeight - 1) / region.sampleSize + 1

        assertTrue(bands.size > 1)
        assertEquals(sampledHeight, region.outputRows(bands.last(), sampledHeight).bottom)
        for ((upper, lower) in bands.zipWithNext()) {
            val upperOutput = region.outputRows(upper, sampledHeight)
            val lowerOutput = region.outputRows(lower, sampledHeight)
            val boundary = (upper.bottom - region.paddedTop).toDouble() * sampledHeight / paddedHeight
            assertEquals(upper.bottom, lower.top)
            assertTrue(upperOutput.bottom >= boundary + 1)
            assertTrue(lowerOutput.top <= boundary - 1)
            assertTrue(lowerOutput.top >= 0)
        }
    }
}
