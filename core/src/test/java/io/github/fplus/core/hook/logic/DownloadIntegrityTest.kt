// Kotlin · DownloadIntegrityTest.kt · JUnit 4 / JVM 11 · 短读取、长度和已有文件保护
package io.github.fplus.core.hook.logic

import com.freegang.ktutils.net.DownloadIntegrity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

class DownloadIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()

    private class ShortStream(bytes: ByteArray, private val chunk: Int) : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, chunk))
    }

    private fun expectIo(block: () -> Unit) {
        try {
            block()
            fail("应拒绝不完整或无效输入")
        } catch (_: IOException) { }
    }

    @Test fun one_byte_prefix_reads_preserve_every_byte() {
        val bytes = ByteArray(100) { it.toByte() }
        val input = ShortStream(bytes, 1)
        val prefix = DownloadIntegrity.readPrefix(input)
        assertArrayEquals(bytes.copyOf(16), prefix)
        val output = ByteArrayOutputStream()
        assertEquals(100L, DownloadIntegrity.copyValidated(input, output, prefix, 100))
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun short_files_are_not_padded_with_sixteen_zero_bytes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val input = ShortStream(bytes, 2)
        val prefix = DownloadIntegrity.readPrefix(input)
        assertArrayEquals(bytes, prefix)
        val output = ByteArrayOutputStream()
        assertEquals(5L, DownloadIntegrity.copyValidated(input, output, prefix, 5))
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun transient_zero_bulk_reads_do_not_spin_or_drop_a_byte() {
        val expected = ByteArray(40) { (it + 1).toByte() }
        val backing = ByteArrayInputStream(expected)
        val input = object : InputStream() {
            var zero = true
            override fun read(): Int = backing.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (zero) { zero = false; return 0 }
                zero = true
                return backing.read(buffer, offset, minOf(length, 3))
            }
        }
        val prefix = DownloadIntegrity.readPrefix(input)
        val output = ByteArrayOutputStream()
        DownloadIntegrity.copyValidated(input, output, prefix, expected.size.toLong())
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test fun progress_includes_the_prefix_bytes() {
        val bytes = ByteArray(40) { it.toByte() }
        val input = ShortStream(bytes, 3)
        val progress = mutableListOf<Long>()
        DownloadIntegrity.copyValidated(input, ByteArrayOutputStream(), DownloadIntegrity.readPrefix(input), 40) {
            progress.add(it)
        }
        assertEquals(16L, progress.first())
        assertEquals(40L, progress.last())
    }

    @Test fun missing_declared_bytes_are_rejected() {
        expectIo {
            val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
            DownloadIntegrity.copyValidated(input, ByteArrayOutputStream(), DownloadIntegrity.readPrefix(input), 10)
        }
    }

    @Test fun bytes_beyond_declared_length_are_rejected() {
        expectIo {
            val input = ByteArrayInputStream(ByteArray(20))
            DownloadIntegrity.copyValidated(input, ByteArrayOutputStream(), DownloadIntegrity.readPrefix(input), 10)
        }
    }

    @Test fun empty_body_is_rejected_even_without_a_declared_length() {
        expectIo { DownloadIntegrity.copyValidated(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), ByteArray(0)) }
    }

    @Test fun invalid_http_or_partial_response_is_rejected() {
        for (status in listOf(204, 206, 301, 404, 500)) expectIo { DownloadIntegrity.requireSuccessfulStatus(status) }
        DownloadIntegrity.requireSuccessfulStatus(200)
        DownloadIntegrity.requireSuccessfulStatus(201)
    }

    @Test fun invalid_content_length_is_rejected() {
        assertEquals(-1L, DownloadIntegrity.declaredLength(null))
        assertEquals(20L, DownloadIntegrity.declaredLength(" 20 "))
        for (value in listOf("", "-1", "abc", "9223372036854775808")) {
            expectIo { DownloadIntegrity.declaredLength(value) }
        }
    }

    @Test fun incomplete_save_cleans_only_its_own_partial_file() {
        val directory = temporary.newFolder()
        val existing = File(directory, "emoji.gif").apply { writeText("既有文件") }
        expectIo { DownloadIntegrity.save(ByteArrayInputStream(byteArrayOf(1, 2, 3)), existing, 100) }
        assertEquals("既有文件", existing.readText())
        assertEquals(listOf("emoji.gif"), directory.listFiles()!!.map { it.name })
    }

    @Test fun successful_name_collision_keeps_existing_content() {
        val directory = temporary.newFolder()
        val existing = File(directory, "emoji.gif").apply { writeText("既有文件") }
        val body = "GIF89a".toByteArray() + ByteArray(10)
        val saved = DownloadIntegrity.save(ShortStream(body, 1), File(directory, "emoji.bin"), body.size.toLong())
        assertEquals("既有文件", existing.readText())
        assertEquals("emoji-1.gif", saved.name)
        assertArrayEquals(body, saved.readBytes())
        assertFalse(directory.listFiles()!!.any { it.extension == "partial" })
    }

    @Test fun empty_save_does_not_publish_a_target_or_leave_partial_files() {
        val directory = temporary.newFolder()
        expectIo { DownloadIntegrity.save(ByteArrayInputStream(ByteArray(0)), File(directory, "emoji.bin"), 0) }
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun stream_failure_preserves_existing_target_and_cleans_partial() {
        val directory = temporary.newFolder()
        val existing = File(directory, "emoji.bin").apply { writeText("既有内容") }
        val input = object : InputStream() {
            var count = 0
            override fun read(): Int {
                if (count++ >= 20) throw IOException("模拟传输中断")
                return 1
            }
        }
        expectIo { DownloadIntegrity.save(input, existing) }
        assertEquals("既有内容", existing.readText())
        assertEquals(listOf("emoji.bin"), directory.listFiles()!!.map { it.name })
    }

    @Test fun complete_picture_sequence_download_is_heic_not_mp4() {
        fun box(type: String, body: ByteArray): ByteArray = ByteBuffer.allocate(8 + body.size)
            .putInt(8 + body.size).put(type.toByteArray()).put(body).array()
        val ftyp = box("ftyp", "msf1".toByteArray() + ByteArray(4))
        val handler = box("hdlr", ByteArray(8) + "pict".toByteArray() + ByteArray(12))
        val body = ftyp + box("moov", box("trak", box("mdia", handler)))
        val saved = DownloadIntegrity.save(ShortStream(body, 2), File(temporary.newFolder(), "emoji.mp4"), body.size.toLong())
        assertEquals("heic", saved.extension)
        assertArrayEquals(body, saved.readBytes())
    }
}
