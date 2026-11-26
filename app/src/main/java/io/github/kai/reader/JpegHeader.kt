package io.github.kai.reader

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException

private const val MARKER_PREFIX = 0xff
private const val START_OF_IMAGE = 0xffd8
private const val BASELINE_FRAME = 0xc0
private const val END_OF_IMAGE = 0xd9
private const val START_OF_SCAN = 0xda
private const val MAX_HEADER_BYTES = 256 * 1024

internal fun isSingleScanBaselineJpeg(input: BufferedInputStream): Boolean {
    // The optional preview may read a long JPEG only when libjpeg can stream its scan.
    input.mark(MAX_HEADER_BYTES + 1)
    val jpeg = DataInputStream(input)
    try {
        if (jpeg.readUnsignedShort() != START_OF_IMAGE) return false
        var remaining = MAX_HEADER_BYTES - 2
        var frameComponents = 0
        while (remaining >= 4) {
            if (jpeg.readUnsignedByte() != MARKER_PREFIX) return false
            var marker = jpeg.readUnsignedByte()
            remaining -= 2
            while (marker == MARKER_PREFIX && remaining > 0) {
                marker = jpeg.readUnsignedByte()
                remaining--
            }
            val missingSegmentLength = marker == END_OF_IMAGE || marker == 0 || remaining < 2
            if (missingSegmentLength) return false

            val segmentSize = jpeg.readUnsignedShort()
            if (segmentSize !in 2..remaining) return false
            var payloadSize = segmentSize - 2
            if (isFrameMarker(marker)) {
                val isSupportedFrame = marker == BASELINE_FRAME && frameComponents == 0 && payloadSize >= 6
                if (!isSupportedFrame || jpeg.skipBytes(5) != 5) return false
                frameComponents = jpeg.readUnsignedByte()
                if (frameComponents !in 1..4 || payloadSize != 6 + 3 * frameComponents) return false
                payloadSize -= 6
            } else if (marker == START_OF_SCAN) {
                if (frameComponents == 0 || payloadSize < 1) return false
                val scanComponents = jpeg.readUnsignedByte()
                val isSingleScan = scanComponents == frameComponents && payloadSize == 4 + 2 * scanComponents
                return isSingleScan && jpeg.skipBytes(payloadSize - 1) == payloadSize - 1
            }
            if (jpeg.skipBytes(payloadSize) != payloadSize) return false
            remaining -= segmentSize
        }
        return false
    } catch (_: EOFException) {
        return false
    } finally {
        input.reset()
    }
}

private fun isFrameMarker(marker: Int): Boolean =
    marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
