// Kotlin · ImageDownloadSummaryTest.kt · JUnit 4 / JVM 11 · 图文下载结果与进度回归
package io.github.fplus.core.hook.logic

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ImageDownloadSummaryTest {
    private val image = File("image.jpg")
    private val video = File("image.mp4")

    @Test fun static_image_is_one_complete_file() {
        val summary = ImageDownloadSummary(listOf(ImageDownloadResult(image)))
        assertTrue(summary.complete); assertEquals(1, summary.files.size); assertEquals("下载成功, 共1个文件!", summary.message())
    }
    @Test fun live_pair_requires_two_successful_files() {
        val summary = ImageDownloadSummary(listOf(ImageDownloadResult(image, true, video)))
        assertTrue(summary.complete); assertEquals(2, summary.files.size); assertEquals(0, summary.missing_videos)
    }
    @Test fun failed_video_is_partial_not_complete() {
        val summary = ImageDownloadSummary(listOf(ImageDownloadResult(image, true, null)))
        assertFalse(summary.complete); assertEquals(1, summary.partial_count); assertEquals(1, summary.missing_videos)
        assertEquals(0, summary.complete_count); assertTrue(summary.message().contains("部分完成"))
    }
    @Test fun failed_image_with_successful_video_is_partial() {
        val summary = ImageDownloadSummary(listOf(ImageDownloadResult(null, true, video)))
        assertFalse(summary.complete); assertEquals(1, summary.missing_images); assertEquals(0, summary.missing_videos); assertEquals(listOf(video), summary.files)
    }
    @Test fun no_live_video_url_counts_as_missing_required_video() {
        val summary = ImageDownloadSummary(listOf(ImageDownloadResult(image, true)))
        assertEquals(1, summary.missing_videos); assertFalse(summary.complete)
    }
    @Test fun complete_failure_has_no_upload_candidates() {
        val summary = ImageDownloadSummary(listOf(ImageDownloadResult(null, true)))
        assertTrue(summary.files.isEmpty()); assertEquals(1, summary.failed_count); assertEquals(1, summary.missing_images); assertEquals(1, summary.missing_videos)
    }
    @Test fun mixed_batch_counts_each_outcome_separately() {
        val summary = ImageDownloadSummary(listOf(ImageDownloadResult(File("a.jpg")), ImageDownloadResult(image, true), ImageDownloadResult(null)))
        assertEquals(1, summary.complete_count); assertEquals(1, summary.partial_count); assertEquals(1, summary.failed_count)
        assertEquals(2, summary.files.size); assertFalse(summary.complete)
    }
    @Test fun empty_batch_is_not_reported_as_success() { assertFalse(ImageDownloadSummary(emptyList()).complete) }
    @Test fun upload_candidates_are_current_successes_not_directory_contents() {
        val results = mutableListOf(ImageDownloadResult(image), ImageDownloadResult(null, true, video))
        val summary = ImageDownloadSummary(results); results.clear()
        assertEquals(listOf(image, video), summary.files)
    }
    @Test fun repeated_paths_are_counted_as_one_real_file() {
        assertEquals(1, ImageDownloadSummary(listOf(ImageDownloadResult(image), ImageDownloadResult(image))).files.size)
    }
    @Test fun unknown_or_zero_content_length_does_not_divide_by_zero() {
        assertEquals(0, DownloadProgress.percent(10, 0)); assertEquals(0, DownloadProgress.percent(10, -1))
    }
    @Test fun progress_is_bounded_and_safe_for_large_files() {
        assertEquals(50, DownloadProgress.percent(50, 100)); assertEquals(100, DownloadProgress.percent(200, 100))
        assertEquals(0, DownloadProgress.percent(-10, 100)); assertEquals(100, DownloadProgress.percent(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
