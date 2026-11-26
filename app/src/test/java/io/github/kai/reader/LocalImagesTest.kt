package io.github.kai.reader

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalImagesTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun sortsMixedImageNamesByEveryNumericSegment() {
        val names = listOf("第10话-2.PNG", "第2话-10.jpg", "第2话-2.webp", "第1话-9.jpg")

        assertEquals(
            listOf("第1话-9.jpg", "第2话-2.webp", "第2话-10.jpg", "第10话-2.PNG"),
            names.sortedWith(::compareImageNames),
        )
    }

    @Test
    fun sortsLargeAndZeroPaddedNumbersWithoutIntegerConversion() {
        val names = listOf("a100000000000000000000.jpg", "A02.jpg", "a99999999999999999999.jpg", "a2.jpg")

        assertEquals(
            listOf("A02.jpg", "a2.jpg", "a99999999999999999999.jpg", "a100000000000000000000.jpg"),
            names.sortedWith(::compareImageNames),
        )
    }

    @Test
    fun picksPowerOfTwoSamplingWithoutGoingBelowTheDisplayWidth() {
        assertEquals(1, imageSampleSize(640, 1080))
        assertEquals(2, imageSampleSize(2000, 1000))
        assertEquals(2, imageSampleSize(3500, 1000))
        assertEquals(4, imageSampleSize(4000, 1000))
    }

    @Test
    fun constrainsExtremeRegionDimensionsAndPixelCount() {
        assertEquals(1, boundedImageSampleSize(4096, 1024, 4096))
        assertEquals(2, boundedImageSampleSize(4096, 1025, 4096))
        assertEquals(8, boundedImageSampleSize(2000, 21_000, 1000))
        assertEquals(1 shl 20, boundedImageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun readsLossyWebpDimensionsFromTheFirstThirtyBytes() {
        val payload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(0x30, 0, 0, 0x9d.toByte(), 0x01, 0x2a))
            .putShort(832)
            .putShort(384)
            .array()
        val prefix = webpPrefix("VP8 ", payload, claimedPayloadSize = 1_000_000)

        assertEquals(ImageBounds(832, 384), readImageBounds(ByteArrayInputStream(prefix).buffered()))
    }

    @Test
    fun rejectsTruncatedWebpOfflineEvenWhenItsDimensionsAreReadable() {
        val payload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(0x30, 0, 0, 0x9d.toByte(), 0x01, 0x2a))
            .putShort(832)
            .putShort(384)
            .array()
        val truncated = webpPrefix("VP8 ", payload, claimedPayloadSize = 1_000_000)
        val file = directory.newFile("incomplete.webp").apply { writeBytes(truncated) }

        assertEquals(ImageBounds(832, 384), readImageBounds(ByteArrayInputStream(truncated).buffered()))
        assertThrows(IOException::class.java) { validateWebpFileLength(file) }
    }

    @Test
    fun readsLosslessWebpDimensionsFromPackedFields() {
        val fields = ((3000 - 1) shl 14) or (721 - 1)
        val payload = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x2f)
            .putInt(fields)
            .array()
        val prefix = webpPrefix("VP8L", payload, claimedPayloadSize = 50_000)

        assertEquals(ImageBounds(721, 3000), readImageBounds(ByteArrayInputStream(prefix).buffered()))
    }

    @Test
    fun readsExtendedWebpCanvasAndRejectsTruncatedDimensions() {
        val payload = byteArrayOf(0x10, 0, 0, 0, 0xff.toByte(), 0x0f, 0, 0x7f, 0x38, 0x01)
        val prefix = webpPrefix("VP8X", payload)

        assertEquals(ImageBounds(4096, 80_000), readImageBounds(ByteArrayInputStream(prefix).buffered()))
        assertNull(readImageBounds(ByteArrayInputStream(prefix.dropLast(1).toByteArray()).buffered()))
    }

    @Test
    fun identifiesSingleScanBaselineJpegWithoutConsumingTheStream() {
        val input = ByteArrayInputStream(jpegPrefix(0xffc0, 3)).buffered()

        assertTrue(isSingleScanBaselineJpeg(input))
        assertEquals(0xff, input.read())
        assertEquals(0xd8, input.read())
    }

    @Test
    fun excludesProgressiveMultiscanAndTruncatedJpegHeadersFromLongPreviews() {
        val progressive = jpegPrefix(0xffc2, 3)
        val multiscan = jpegPrefix(0xffc0, 1)
        val truncated = jpegPrefix(0xffc0, 3).dropLast(1).toByteArray()

        for (header in listOf(progressive, multiscan, truncated)) {
            assertFalse(isSingleScanBaselineJpeg(ByteArrayInputStream(header).buffered()))
        }
    }

    private fun jpegPrefix(frameMarker: Int, scanComponents: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeShort(0xffd8)
            output.writeShort(0xffe1)
            output.writeShort(6)
            output.write(byteArrayOf(0xff.toByte(), 0xc2.toByte(), 0, 0))
            output.writeShort(frameMarker)
            output.writeShort(17)
            output.writeByte(8)
            output.writeShort(480)
            output.writeShort(320)
            output.writeByte(3)
            for (component in 1..3) output.write(byteArrayOf(component.toByte(), 0x11, 0))
            output.writeShort(0xffda)
            output.writeShort(6 + 2 * scanComponents)
            output.writeByte(scanComponents)
            for (component in 1..scanComponents) output.write(byteArrayOf(component.toByte(), 0))
            output.write(byteArrayOf(0, 63, 0))
        }
        return buffer.toByteArray()
    }

    private fun webpPrefix(chunk: String, payload: ByteArray, claimedPayloadSize: Int = payload.size): ByteArray =
        ByteBuffer.allocate(20 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(12 + claimedPayloadSize + (claimedPayloadSize and 1))
            .put("WEBP".toByteArray(Charsets.US_ASCII))
            .put(chunk.toByteArray(Charsets.US_ASCII))
            .putInt(claimedPayloadSize)
            .put(payload)
            .array()
}
