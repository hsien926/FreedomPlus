// Kotlin · DownloadIntegrity.kt · JVM / Android API 21+ · 有界读取与不覆盖的完整下载落盘
package com.freegang.ktutils.net

import com.freegang.ktutils.io.KFileUtils
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 与 Android、连接和 UI 无关，供公共下载链路及 JVM 回归共用。 */
object DownloadIntegrity {
    fun requireSuccessfulStatus(status: Int) {
        if (status != 200 && status != 201) {
            throw IOException("下载响应状态无效: $status")
        }
    }

    fun declaredLength(header: String?): Long {
        if (header == null) return -1L
        return header.trim().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Content-Length 无效")
    }

    fun readPrefix(input: InputStream, limit: Int = 16): ByteArray {
        require(limit >= 0)
        val buffer = ByteArray(limit)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                buffer[size++] = next.toByte()
            } else size += count
        }
        return buffer.copyOf(size)
    }

    fun copyValidated(
        input: InputStream,
        output: OutputStream,
        prefix: ByteArray,
        expectedLength: Long = -1L,
        onProgress: (Long) -> Unit = {},
    ): Long {
        if (expectedLength < -1L) throw IOException("声明长度无效")
        var written = 0L
        fun writeChunk(bytes: ByteArray, size: Int) {
            if (size == 0) return
            if (written > Long.MAX_VALUE - size) throw IOException("下载长度溢出")
            val next = written + size
            if (expectedLength >= 0 && next > expectedLength) throw IOException("下载数据超过声明长度")
            output.write(bytes, 0, size)
            written = next
            onProgress(written)
        }
        writeChunk(prefix, prefix.size)
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                buffer[0] = next.toByte()
                writeChunk(buffer, 1)
            } else writeChunk(buffer, count)
        }
        if (written == 0L) throw IOException("下载内容为空")
        if (expectedLength >= 0 && written != expectedLength) throw IOException("下载数据未达到声明长度")
        return written
    }

    /** 只清理本次创建的临时文件；已有目标文件保留，新下载自动使用不冲突的名称。 */
    fun save(
        input: InputStream,
        requestedFile: File,
        expectedLength: Long = -1L,
        onProgress: (Long) -> Unit = {},
    ): File {
        val requested = requestedFile.absoluteFile
        val directory = requested.parentFile ?: throw IOException("下载目录缺失")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("下载目录不可创建")
        val partial = File.createTempFile(".freedom-download-", ".partial", directory)
        var published: File? = null
        var succeeded = false
        try {
            val written = input.use { source ->
                partial.outputStream().use { output ->
                    val prefix = readPrefix(source)
                    copyValidated(source, output, prefix, expectedLength, onProgress)
                }
            }
            val suffix = KFileUtils.getFileKind(partial)?.suffix
            val outputFile = reserveOutput(requested, suffix)
            published = outputFile
            // 原子占用名称后仅写自己新建的文件，避免 renameTo 在部分文件系统覆盖既有目标。
            partial.inputStream().use { source -> outputFile.outputStream().use { source.copyTo(it) } }
            if (outputFile.length() != written) throw IOException("下载落盘长度不完整")
            succeeded = true
            return outputFile
        } finally {
            partial.delete()
            if (!succeeded) published?.delete()
        }
    }

    private fun reserveOutput(requested: File, suffix: String?): File {
        val name = if (suffix == null) requested.name else requested.nameWithoutExtension + suffix
        val first = File(requested.parentFile, name)
        for (index in 0..9999) {
            val candidate = if (index == 0) first else {
                File(first.parentFile, "${first.nameWithoutExtension}-$index${first.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()}")
            }
            if (candidate.createNewFile()) return candidate
        }
        throw IOException("下载目标名称持续冲突")
    }
}
