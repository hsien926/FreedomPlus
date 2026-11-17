// Kotlin · EmojiMediaSelectionTest.kt · JUnit 4 / JVM 11 · 表情源偏好与真实格式提示
package io.github.fplus.core.hook.logic

import com.freegang.ktutils.io.FileKind
import io.github.fplus.core.config.EmojiAnimateFormat
import org.junit.Assert.*
import org.junit.Test

class EmojiMediaSelectionTest {
    private val video = "https://cdn.example/first.mp4?signature=hidden"
    private val gif = "https://cdn.example/second.GIF?format=webp"
    private val webp = "https://cdn.example/third.webp"

    @Test fun source_preference_keeps_the_original_first_url() {
        assertEquals(video, EmojiMediaSelection.selectUrl(listOf(video, gif, webp), EmojiAnimateFormat.SOURCE))
    }

    @Test fun gif_preference_selects_a_gif_path_case_insensitively() {
        assertEquals(gif, EmojiMediaSelection.selectUrl(listOf(video, gif, webp), EmojiAnimateFormat.GIF))
    }

    @Test fun mp4_preference_selects_a_video_path_before_gif() {
        assertEquals(video, EmojiMediaSelection.selectUrl(listOf(gif, webp, video), EmojiAnimateFormat.MP4))
    }

    @Test fun missing_preferred_variant_falls_back_to_first_source() {
        assertEquals(webp, EmojiMediaSelection.selectUrl(listOf(webp, gif), EmojiAnimateFormat.MP4))
    }

    @Test fun blank_sources_are_ignored_and_an_empty_list_is_empty() {
        assertEquals(gif, EmojiMediaSelection.selectUrl(listOf("", " ", gif), EmojiAnimateFormat.SOURCE))
        assertNull(EmojiMediaSelection.selectUrl(emptyList(), EmojiAnimateFormat.GIF))
    }

    @Test fun url_query_and_hostname_are_not_file_extensions() {
        assertNull(EmojiMediaSelection.knownExtension("https://gif.example/object?format=.gif"))
        assertEquals("gif", EmojiMediaSelection.knownExtension("https://cdn.example/x.GIF#fragment.mp4"))
    }

    @Test fun heif_sequence_is_not_reported_as_a_saved_mp4() {
        val hint = EmojiMediaSelection.hint(FileKind.HEIC, "pict", EmojiAnimateFormat.MP4)!!
        assertTrue(hint.contains("HEIF 图像序列"))
        assertTrue(hint.contains(".heic"))
        assertTrue(hint.contains("暂不支持转 MP4"))
        assertFalse(hint.contains("已按 MP4 保存"))
    }

    @Test fun matching_video_is_success_without_claiming_its_codec() {
        assertNull(EmojiMediaSelection.hint(FileKind.MP4, "vide", EmojiAnimateFormat.MP4))
        val hint = EmojiMediaSelection.hint(FileKind.MP4, "vide", EmojiAnimateFormat.GIF)!!
        assertTrue(hint.contains("MP4 视频"))
        assertFalse(hint.contains("HEVC"))
    }

    @Test fun pure_audio_is_not_reported_as_video() {
        val hint = EmojiMediaSelection.hint(FileKind.M4A, "soun", EmojiAnimateFormat.MP4)!!
        assertTrue(hint.contains("音频"))
        assertTrue(hint.contains(".m4a"))
    }

    @Test fun unknown_content_is_reported_as_unconverted_raw_data() {
        val hint = EmojiMediaSelection.hint(null, null, EmojiAnimateFormat.GIF)!!
        assertTrue(hint.contains(".bin"))
        assertTrue(hint.contains("未进行转码"))
    }
}
