// Kotlin · IsoBmffInspector.kt · JVM / Android API 21+ · 有界 ISO-BMFF 轨道探测
package com.freegang.ktutils.io

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** 只报告经过真实 box 边界验证的 ftyp 和 moov/trak/mdia/hdlr。 */
data class IsoBmffInfo(
    val majorBrand: String?,
    val compatibleBrands: Set<String>,
    val handlerTypes: List<String>,
) {
    val handlerType: String?
        get() = handlerTypes.firstOrNull { it == "vide" }
            ?: handlerTypes.firstOrNull { it == "pict" }
            ?: handlerTypes.firstOrNull { it == "soun" }
            ?: handlerTypes.firstOrNull()
}

object IsoBmffInspector {
    private const val MAX_BOX_COUNT = 4096
    private const val MAX_DEPTH = 8
    private const val MAX_BRAND_COUNT = 256

    private data class Box(val type: String, val bodyStart: Long, val end: Long)
    private class State {
        var boxCount = 0
        var majorBrand: String? = null
        val brands = linkedSetOf<String>()
        val handlers = mutableListOf<String>()
    }

    @JvmStatic
    fun probe(file: File): IsoBmffInfo? {
        if (!file.isFile) return null
        return try {
            RandomAccessFile(file, "r").use { source ->
                val state = State()
                walk(source, 0, source.length(), 0, "root", state)
                if (state.majorBrand == null && state.handlers.isEmpty()) null
                else IsoBmffInfo(state.majorBrand, state.brands.toSet(), state.handlers.toList())
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun walk(
        source: RandomAccessFile,
        start: Long,
        end: Long,
        depth: Int,
        container: String,
        state: State,
    ) {
        if (depth > MAX_DEPTH) throw IOException("ISO-BMFF 容器层级超过限制")
        var offset = start
        var handlerSeen = false
        while (offset < end) {
            val box = readBox(source, offset, end, state)
            when {
                container == "root" && box.type == "ftyp" -> readBrands(source, box, state)
                container == "root" && box.type == "moov" ->
                    walk(source, box.bodyStart, box.end, depth + 1, "moov", state)
                container == "moov" && box.type == "trak" ->
                    walk(source, box.bodyStart, box.end, depth + 1, "trak", state)
                container == "trak" && box.type == "mdia" ->
                    walk(source, box.bodyStart, box.end, depth + 1, "mdia", state)
                container == "mdia" && box.type == "hdlr" -> {
                    if (handlerSeen) throw IOException("同一 mdia 包含重复 hdlr")
                    handlerSeen = true
                    state.handlers.add(readHandler(source, box))
                }
            }
            // mdat、udta、其它非目标 box 的载荷不读取，直接跳到已校验的终点。
            offset = box.end
        }
    }

    private fun readBox(source: RandomAccessFile, offset: Long, parentEnd: Long, state: State): Box {
        if (++state.boxCount > MAX_BOX_COUNT) throw IOException("ISO-BMFF box 数量超过限制")
        val remaining = parentEnd - offset
        if (offset < 0 || remaining < 8) throw IOException("ISO-BMFF box 头截断")
        source.seek(offset)
        val size32 = source.readInt().toLong() and 0xffffffffL
        val type = readFourCc(source)
        var headerSize = 8L
        val size = when (size32) {
            0L -> remaining
            1L -> {
                if (remaining < 16) throw IOException("ISO-BMFF 扩展 box 头截断")
                headerSize = 16
                source.readLong().also {
                    if (it < 0) throw IOException("无符号 box 长度超出 Long 范围")
                }
            }
            else -> size32
        }
        if (type == "uuid") headerSize += 16
        // 先比较剩余长度再做加法，拒绝溢出及跨父 box 边界的长度。
        if (size < headerSize || size > remaining) throw IOException("ISO-BMFF box 长度越界")
        return Box(type, offset + headerSize, offset + size)
    }

    private fun readBrands(source: RandomAccessFile, box: Box, state: State) {
        val bodyLength = box.end - box.bodyStart
        if (state.majorBrand != null || bodyLength < 8 || (bodyLength - 8) % 4 != 0L) {
            throw IOException("ISO-BMFF ftyp 内容无效")
        }
        if ((bodyLength - 8) / 4 > MAX_BRAND_COUNT) throw IOException("ftyp brand 数量超过限制")
        source.seek(box.bodyStart)
        state.majorBrand = readFourCc(source)
        source.readInt() // minor_version
        while (source.filePointer < box.end) state.brands.add(readFourCc(source))
    }

    private fun readHandler(source: RandomAccessFile, box: Box): String {
        if (box.end - box.bodyStart < 24) throw IOException("ISO-BMFF hdlr 内容截断")
        source.seek(box.bodyStart)
        if (source.readUnsignedByte() != 0) throw IOException("不支持的 hdlr 版本")
        source.seek(box.bodyStart + 8) // version/flags 与 pre_defined
        return readFourCc(source)
    }

    private fun readFourCc(source: RandomAccessFile): String {
        val bytes = ByteArray(4)
        source.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
