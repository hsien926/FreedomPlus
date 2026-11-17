// Kotlin · IsoBmffInspectorTest.kt · JUnit 4 / JVM 11 · ISO-BMFF 与固定魔数回归
package io.github.fplus.core.hook.logic

import com.freegang.ktutils.io.FileKind
import com.freegang.ktutils.io.IsoBmffInspector
import com.freegang.ktutils.io.KFileUtils
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class IsoBmffInspectorTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun box(type: String, body: ByteArray, extended: Boolean = false): ByteArray =
        if (extended) ByteBuffer.allocate(16 + body.size).putInt(1).put(type.toByteArray())
            .putLong(16L + body.size).put(body).array()
        else ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()

    private fun handler(type: String, extended: Boolean = false): ByteArray =
        box("hdlr", ByteArray(8) + type.toByteArray() + ByteArray(12), extended)

    private fun track(type: String): ByteArray = box("trak", box("mdia", handler(type)))
    private fun ftyp(brand: String): ByteArray = box("ftyp", brand.toByteArray() + ByteArray(4))
    private fun file(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }
    private fun probe(bytes: ByteArray): String? = IsoBmffProbe.handlerType(file(bytes))

    @Test fun fake_handler_inside_udta_is_ignored() {
        val fake = box("udta", "hdlr".toByteArray() + ByteArray(8) + "pict".toByteArray())
        assertEquals("vide", probe(box("moov", fake + track("vide"))))
    }

    @Test fun handler_outside_mdia_is_not_a_track() {
        assertNull(probe(box("moov", handler("vide"))))
    }

    @Test fun audio_track_before_video_does_not_win() {
        assertEquals("vide", probe(box("moov", track("soun") + track("vide"))))
    }

    @Test fun picture_track_after_audio_is_selected() {
        assertEquals("pict", probe(box("moov", track("soun") + track("pict"))))
    }

    @Test fun pure_audio_is_never_classified_as_video() {
        val source = file(ftyp("isom") + box("moov", track("soun")))
        assertEquals("soun", IsoBmffProbe.handlerType(source))
        assertEquals(FileKind.M4A, KFileUtils.getFileKind(source))
    }

    @Test fun msf1_picture_sequence_keeps_heic_container() {
        val source = file(ftyp("msf1") + box("moov", track("pict")))
        assertEquals(FileKind.HEIC, KFileUtils.getFileKind(source))
        assertEquals(FileKind.HEIC, KFileUtils.getFileKind(ftyp("msf1")))
    }

    @Test fun msf1_video_is_mp4_after_full_track_probe() {
        assertEquals(FileKind.MP4, KFileUtils.getFileKind(file(ftyp("msf1") + box("moov", track("vide")))))
    }

    @Test fun common_video_brands_work_for_headers_and_complete_files() {
        for (brand in listOf("isom", "mp41", "mp42", "iso6", "avc1")) {
            assertEquals(brand, FileKind.MP4, KFileUtils.getFileKind(ftyp(brand)))
            assertEquals(brand, FileKind.MP4, KFileUtils.getFileKind(file(ftyp(brand) + box("moov", track("vide")))))
        }
    }

    @Test fun expanded_size_boxes_are_read_at_their_true_boundary() {
        val mdia = box("mdia", handler("vide", true), true)
        assertEquals("vide", probe(box("moov", box("trak", mdia, true), true)))
    }

    @Test fun zero_size_extends_to_parent_end() {
        val body = track("vide")
        val moov = ByteBuffer.allocate(8 + body.size).putInt(0).put("moov".toByteArray()).put(body).array()
        assertEquals("vide", probe(moov))
    }

    @Test fun truncated_expanded_size_is_rejected() {
        assertNull(probe(ByteBuffer.allocate(8).putInt(1).put("moov".toByteArray()).array()))
    }

    @Test fun unsigned_64_bit_size_cannot_overflow_long() {
        val bad = ByteBuffer.allocate(16).putInt(1).put("moov".toByteArray()).putLong(Long.MIN_VALUE).array()
        assertNull(probe(bad))
    }

    @Test fun child_cannot_cross_its_parent_boundary() {
        val bad = ByteBuffer.allocate(8).putInt(100).put("trak".toByteArray()).array()
        assertNull(probe(box("moov", bad)))
    }

    @Test fun truncated_handler_and_trailing_box_header_are_rejected() {
        assertNull(probe(box("moov", box("trak", box("mdia", box("hdlr", ByteArray(12)))))))
        assertNull(probe(box("moov", track("vide")) + byteArrayOf(1, 2, 3)))
    }

    @Test fun duplicate_handler_in_one_mdia_is_rejected() {
        assertNull(probe(box("moov", box("trak", box("mdia", handler("vide") + handler("pict"))))))
    }

    @Test fun box_budget_is_bounded() {
        val empty = box("free", ByteArray(0))
        val many = ByteArray(empty.size * 4097)
        for (index in 0 until 4097) empty.copyInto(many, index * empty.size)
        assertNull(probe(many + box("moov", track("vide"))))
    }

    @Test fun deeply_nested_non_track_boxes_do_not_match_handler() {
        var body = handler("vide")
        repeat(20) { body = box("moov", body) }
        assertNull(probe(body))
    }

    @Test fun mdat_payload_is_skipped_before_later_moov() {
        val payload = ByteArray(1024 * 1024)
        "hdlr".toByteArray().copyInto(payload, 10)
        "pict".toByteArray().copyInto(payload, 22)
        assertEquals("vide", probe(ftyp("isom") + box("mdat", payload) + box("moov", track("vide"))))
    }

    @Test fun incomplete_brand_box_is_rejected() {
        assertNull(IsoBmffInspector.probe(file(box("ftyp", "isom".toByteArray()))))
        assertNull(IsoBmffInspector.probe(file(box("ftyp", "isom".toByteArray() + ByteArray(5)))))
    }

    @Test fun gif_magic_does_not_include_dimensions_or_palette() {
        for (width in listOf(1, 200, 255)) {
            val bytes = "GIF89a".toByteArray() + byteArrayOf(width.toByte(), 0, 7, 0, 0, 0, 0, 0, 0, 0)
            assertEquals(FileKind.GIF, KFileUtils.getFileKind(bytes))
        }
        assertEquals(FileKind.GIF, KFileUtils.getFileKind("GIF87a".toByteArray()))
    }

    @Test fun webp_magic_does_not_include_riff_length() {
        for (size in listOf(12, 1000, 123456)) {
            val bytes = ByteBuffer.allocate(16).put("RIFF".toByteArray()).putInt(size)
                .put("WEBP".toByteArray()).putInt(0).array()
            assertEquals(FileKind.WEBP, KFileUtils.getFileKind(bytes))
        }
    }
}
