package io.github.fplus.core.hook.logic

import java.io.File
import java.io.RandomAccessFile

/**
 * 轻量 ISO-BMFF 探测：只回答「这条轨道是视频还是图像序列」。
 *
 * 抖音的动图素材存在两种容器形态，仅凭 `ftyp` brand 无法区分：
 * - `ftyp` = `msf1` + `moov/mdia/hdlr` = `vide` → HEVC 短视频，改名 `.mp4` 可播；
 * - `ftyp` = `msf1` + `moov/mdia/hdlr` = `pict` → HEIF 图像序列
 *   （handler name `HEIF/ImageSequence/PictureHandler`），改名 `.mp4` 任何播放器都打不开。
 *
 * 实现上用 [RandomAccessFile] 顺序读顶层 box 头、遇 `mdat` 直接 `seek` 跳过，
 * 读取量只与 `moov` 大小相关，与文件体积无关（动图几 MB 也不会整读进内存）。
 *
 * 失败一律返回 null，调用方按「维持原后缀」处理，不阻断保存流程。
 */
object IsoBmffProbe {
    private const val MAX_BOX_COUNT = 64
    private const val MAX_MOOV_BYTES = 4 * 1024 * 1024

    /**
     * 返回 `moov` 里第一个 `hdlr` 的 handler_type（如 `vide` / `pict` / `soun`）。
     * 非 ISO-BMFF、解析失败或文件不可读时返回 null。
     */
    fun handlerType(file: File): String? = runCatching {
        if (!file.isFile || file.length() < 16L) {
            null
        } else {
            RandomAccessFile(file, "r").use { raf -> findHandlerType(raf) }
        }
    }.getOrNull()

    private fun findHandlerType(raf: RandomAccessFile): String? {
        val length = raf.length()
        var offset = 0L
        var count = 0

        while (offset + 8L <= length && count < MAX_BOX_COUNT) {
            count++
            raf.seek(offset)
            val size32 = readUInt32(raf)
            val type = readFourCc(raf) ?: return null

            var headerSize = 8L
            var boxSize = size32
            when (size32) {
                1L -> {
                    // 64 位 largesize
                    boxSize = readUInt64(raf)
                    headerSize = 16L
                }

                0L -> boxSize = length - offset
            }

            if (boxSize < headerSize || offset + boxSize > length) return null

            if (type == "moov") return readHandlerType(raf, boxSize - headerSize)

            offset += boxSize
        }
        return null
    }

    /** `moov` 体已经在 [raf] 当前位置；读取并在其中查找 `hdlr` */
    private fun readHandlerType(raf: RandomAccessFile, bodyLength: Long): String? {
        val size = bodyLength.coerceAtMost(MAX_MOOV_BYTES.toLong()).toInt()
        if (size < 16) return null
        val buffer = ByteArray(size)
        raf.readFully(buffer)

        val typeIndex = indexOfFourCc(buffer, "hdlr") ?: return null
        // hdlr 体：'hdlr'(4) + version/flags(4) + pre_defined(4) + handler_type(4)
        val handlerOffset = typeIndex + 12
        if (handlerOffset + 4 > buffer.size) return null
        return String(buffer, handlerOffset, 4, Charsets.US_ASCII)
    }

    private fun indexOfFourCc(buffer: ByteArray, fourCc: String): Int? {
        val b0 = fourCc[0].code.toByte()
        val b1 = fourCc[1].code.toByte()
        val b2 = fourCc[2].code.toByte()
        val b3 = fourCc[3].code.toByte()
        var i = 0
        while (i + 4 <= buffer.size) {
            if (buffer[i] == b0 && buffer[i + 1] == b1 && buffer[i + 2] == b2 && buffer[i + 3] == b3) {
                return i
            }
            i++
        }
        return null
    }

    private fun readFourCc(raf: RandomAccessFile): String? {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUInt32(raf: RandomAccessFile): Long {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
    }

    private fun readUInt64(raf: RandomAccessFile): Long {
        val bytes = ByteArray(8)
        raf.readFully(bytes)
        var value = 0L
        for (byte in bytes) {
            value = (value shl 8) or (byte.toLong() and 0xFF)
        }
        return value
    }
}
