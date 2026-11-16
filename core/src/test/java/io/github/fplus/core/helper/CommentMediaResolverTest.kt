// Kotlin · CommentMediaResolverTest.kt · JUnit 4 / JVM 11 · 媒体反射解析回归
package io.github.fplus.core.helper

import org.junit.Assert.*
import org.junit.Test

class CommentMediaResolverTest {
    private class UrlModel(@JvmField val urlList: List<String>)
    private class DirectImage(@JvmField val urlList: List<String>, @JvmField val downloadUrlList: List<String>)
    private class NamedImage(@JvmField val originUrl: UrlModel, @JvmField val thumbUrl: UrlModel)
    private open class ParentImage(@JvmField val a: UrlModel)
    private class ChildImage(model: UrlModel) : ParentImage(model)
    private class FixtureCommentImageLiveVideoStruct(
        @JvmField val a: List<Any> = listOf(1, 2),
        @JvmField val b: List<String> = listOf("identifier"),
        @JvmField val c: List<String> = listOf("https://example.org/live.mp4"),
    )
    private class LiveImage(@JvmField val x: FixtureCommentImageLiveVideoStruct, @JvmField val y: UrlModel)
    private class ObfuscatedImage(@JvmField val z: UrlModel)

    @Test fun reads_direct_list_and_prefers_download_resolution() {
        val image = DirectImage(listOf("https://example.org/view.jpg"), listOf("https://example.org/download.jpg"))
        assertEquals(listOf("https://example.org/download.jpg"), CommentMediaResolver.image_urls(image))
    }
    @Test fun falls_back_to_direct_url_list_if_download_list_is_empty() {
        val image = DirectImage(listOf("https://example.org/view.jpg"), emptyList())
        assertEquals(image.urlList, CommentMediaResolver.image_urls(image))
    }
    @Test fun prefers_origin_url_over_thumbnail() {
        val image = NamedImage(UrlModel(listOf("https://example.org/origin.jpg")), UrlModel(listOf("https://example.org/thumb.jpg")))
        assertEquals(image.originUrl.urlList, CommentMediaResolver.image_urls(image))
    }
    @Test fun resolves_obfuscated_nested_model() {
        val image = ObfuscatedImage(UrlModel(listOf("https://example.org/a.jpg")))
        assertEquals(image.z.urlList, CommentMediaResolver.image_urls(image))
    }
    @Test fun includes_fields_from_parent_classes() {
        assertEquals(listOf("https://example.org/a.jpg"), CommentMediaResolver.image_urls(ChildImage(UrlModel(listOf("https://example.org/a.jpg")))))
    }
    @Test fun unrelated_nonempty_lists_do_not_hide_video_addresses() {
        val image = LiveImage(FixtureCommentImageLiveVideoStruct(), UrlModel(listOf("https://example.org/still.jpg")))
        assertEquals(listOf("https://example.org/live.mp4"), CommentMediaResolver.comment_video_urls(image))
    }
    @Test fun video_object_is_not_selected_as_static_image() {
        val image = LiveImage(FixtureCommentImageLiveVideoStruct(), UrlModel(listOf("https://example.org/still.jpg")))
        assertEquals(listOf("https://example.org/still.jpg"), CommentMediaResolver.image_urls(image))
    }
    @Test fun static_image_has_no_comment_video() {
        assertTrue(CommentMediaResolver.comment_video_urls(DirectImage(listOf("https://example.org/a.jpg"), emptyList())).isEmpty())
    }
    @Test fun filters_blank_non_url_and_non_string_values() {
        assertEquals(listOf("https://example.org/a.jpg"), CommentMediaResolver.urls(listOf(null, 1, "", " id ", " https://example.org/a.jpg ", "https://example.org/a.jpg")))
    }
    @Test fun unknown_structure_returns_empty_lists() {
        assertTrue(CommentMediaResolver.image_urls(Any()).isEmpty()); assertTrue(CommentMediaResolver.comment_video_urls(Any()).isEmpty())
    }
}
