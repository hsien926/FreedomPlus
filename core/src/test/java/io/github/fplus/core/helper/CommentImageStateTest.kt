// Kotlin · CommentImageStateTest.kt · JUnit 4 / JVM 11 · 评论图片状态回归
package io.github.fplus.core.helper

import org.junit.Assert.*
import org.junit.Test

class CommentImageStateTest {
    private fun images(vararg names: String) = names.map { CommentMedia(listOf("https://example.org/$it.jpg")) }
    private fun state(): CommentImageState = CommentImageState().apply { bind("a", images("a1", "a2", "a3")) }

    @Test fun parses_current_displayed_page() { assertEquals(1, CommentImageState.parse_page(" 2 / 3 ", 3)) }
    @Test fun accepts_full_width_separator() { assertEquals(1, CommentImageState.parse_page("2／3", 3)) }
    @Test fun rejects_unrelated_numeric_prefixes() {
        for (text in listOf("第2张", "2", "2/3张", "2/3 extra", "", "1/0")) assertNull(CommentImageState.parse_page(text, 3))
    }
    @Test fun rejects_out_of_range_or_wrong_total() {
        for (text in listOf("0/3", "4/3", "2/9", "999999999999999999/3")) assertNull(CommentImageState.parse_page(text, 3))
    }
    @Test fun resets_page_when_switching_comments() {
        val state = state(); state.update_page("3/3"); state.bind("b", images("b1", "b2"))
        assertEquals(images("b1").single(), state.current_media())
    }
    @Test fun different_comments_with_identical_images_reset_page() {
        val state = state(); state.update_page("3/3"); state.bind("different-comment", images("a1", "a2", "a3"))
        assertEquals(images("a1").single(), state.current_media())
    }
    @Test fun retains_page_for_repeated_same_comment_update() {
        val state = state(); state.update_page("2/3"); state.bind("a", images("a1", "a2", "a3"))
        assertEquals(images("a2").single(), state.current_media())
    }
    @Test fun resets_page_when_image_order_changes() {
        val state = state(); state.update_page("3/3"); state.bind("a", images("a3", "a2", "a1"))
        assertEquals(images("a3").single(), state.current_media())
    }
    @Test fun invalid_page_discards_previous_index() {
        val state = state(); state.update_page("3/3"); state.update_page("第1张")
        assertEquals(images("a1").single(), state.current_media())
    }
    @Test fun out_of_range_returns_first_instead_of_last() {
        val state = state(); state.update_page("999/3")
        assertEquals(images("a1").single(), state.current_media())
    }
    @Test fun empty_comment_clears_previous_image() {
        val state = state(); state.update_page("3/3"); state.bind("b", emptyList())
        assertEquals(0, state.image_count); assertNull(state.current_media())
    }
    @Test fun owners_do_not_share_comment_or_page_state() {
        val store = CommentImageStateStore(); val a = Any(); val b = Any()
        store.for_owner(a).bind("a", images("a1", "a2")); store.for_owner(a).update_page("2/2")
        store.for_owner(b).bind("b", images("b1", "b2"))
        assertEquals(images("a2").single(), store.for_owner(a).current_media())
        assertEquals(images("b1").single(), store.for_owner(b).current_media())
    }
    @Test fun snapshots_are_not_mutated_by_input_lists() {
        val urls = mutableListOf("https://example.org/original.jpg")
        val input = mutableListOf(CommentMedia(urls)); val state = CommentImageState()
        state.bind("a", input); urls.clear(); input.clear()
        assertEquals("https://example.org/original.jpg", state.current_media()?.image_urls?.single())
    }
    @Test fun page_event_before_media_does_not_leak_into_new_comment() {
        val state = CommentImageState(); state.update_page("3/3"); state.bind("a", images("a1", "a2", "a3"))
        assertEquals(images("a1").single(), state.current_media())
    }
    @Test fun late_owner_resolution_reuses_only_associated_state() {
        val store = CommentImageStateStore(); val view = Any(); val fragment = Any()
        val state = store.for_owner(view); state.bind("a", images("a1", "a2")); state.update_page("2/2")
        store.associate(fragment, state)
        assertSame(state, store.for_owner(fragment)); assertNull(store.for_owner(Any()).current_media())
    }
}
