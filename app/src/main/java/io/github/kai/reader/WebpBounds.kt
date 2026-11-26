package io.github.kai.reader

import java.io.DataInputStream
import java.io.File
import java.io.IOException

internal const val WEBP_MAX_BOUNDS_HEADER = 30

internal fun isWebpHeader(bytes: ByteArray, size: Int): Boolean =
    size >= 12 && bytes.fourCc(0) == "RIFF" && bytes.fourCc(8) == "WEBP"

internal fun readWebpBounds(bytes: ByteArray, size: Int): ImageBounds? {
    if (size < 20) return null
    val containerSize = bytes.littleEndian(4, 4).toLong() and 0xffff_ffffL
    val payloadSize = bytes.littleEndian(16, 4).toLong() and 0xffff_ffffL
    val paddedSize = payloadSize + (payloadSize and 1)
    if (containerSize < 12 + paddedSize) return null

    return when (bytes.fourCc(12)) {
        "VP8 " -> {
            if (size < 30 || payloadSize < 10) return null
            val isKeyFrame = (bytes[20].toInt() and 1) == 0
            val hasStartCode = bytes.littleEndian(23, 3) == 0x2a019d
            if (!isKeyFrame || !hasStartCode) return null

            val width = bytes.littleEndian(26, 2) and 0x3fff
            val height = bytes.littleEndian(28, 2) and 0x3fff
            if (width > 0 && height > 0) ImageBounds(width, height) else null
        }
        "VP8L" -> {
            if (size < 25 || payloadSize < 5 || bytes[20].toInt() != 0x2f) return null
            val fields = bytes.littleEndian(21, 4)
            if (fields ushr 29 != 0) return null

            val width = (fields and 0x3fff) + 1
            val height = ((fields ushr 14) and 0x3fff) + 1
            ImageBounds(width, height)
        }
        "VP8X" -> {
            if (size < 30 || payloadSize != 10L) return null
            ImageBounds(bytes.littleEndian(24, 3) + 1, bytes.littleEndian(27, 3) + 1)
        }
        else -> null
    }
}

internal fun validateWebpFileLength(file: File) {
    val header = ByteArray(12)
    if (file.length() < header.size) throw IOException("离线图片文件不完整。")
    DataInputStream(file.inputStream()).use { it.readFully(header) }
    if (!isWebpHeader(header, header.size)) return
    // 读取尺寸只使用 WebP 前缀，保存前还需校验 RIFF 声明的其余数据实际存在。
    val declaredSize = header.littleEndian(4, 4).toLong() and 0xffff_ffffL
    if (file.length() < declaredSize + 8) throw IOException("离线 WebP 图片文件不完整。")
}

private fun ByteArray.fourCc(offset: Int): String = String(this, offset, 4, Charsets.US_ASCII)

private fun ByteArray.littleEndian(offset: Int, size: Int): Int {
    var value = 0
    for (index in 0 until size) {
        val byte = this[offset + index].toInt() and 0xff
        value = value or (byte shl (8 * index))
    }
    return value
}
