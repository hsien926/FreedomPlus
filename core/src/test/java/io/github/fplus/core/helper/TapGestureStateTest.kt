// Kotlin · TapGestureStateTest.kt · JUnit 4 / JVM 11 · 手势身份和取消回归
package io.github.fplus.core.helper

import org.junit.Assert.*
import org.junit.Test

class TapGestureStateTest {
    private fun new_state() = TapGestureState(500)
    private fun tap(state: TapGestureState, time: Long = 100, media: String? = "a") {
        assertFalse(state.begin(time, 10f, 10f, 300, 20, media))
        state.finish(time + 20)
    }
    @Test fun second_down_in_same_video_is_double_tap() {
        val state = new_state(); tap(state)
        assertTrue(state.begin(200, 12f, 10f, 300, 20, "a"))
    }
    @Test fun different_views_never_share_first_tap() {
        val first = new_state(); val second = new_state(); tap(first)
        assertFalse(second.begin(200, 10f, 10f, 300, 20, "a"))
    }
    @Test fun recycled_view_with_new_video_is_not_double_tap() {
        val state = new_state(); tap(state)
        assertFalse(state.begin(200, 10f, 10f, 300, 20, "b"))
    }
    @Test fun unknown_video_identity_is_not_taken_over() {
        val state = new_state(); tap(state, media = null)
        assertFalse(state.begin(200, 10f, 10f, 300, 20, null))
    }
    @Test fun drag_is_not_first_tap() {
        val state = new_state(); state.begin(100, 0f, 0f, 300, 20, "a")
        assertTrue(state.move(30f, 0f, 10)); state.finish(150)
        assertFalse(state.begin(200, 30f, 0f, 300, 20, "a"))
    }
    @Test fun double_tap_radius_is_circular_not_square() {
        val state = new_state(); tap(state)
        assertFalse(state.begin(200, 28f, 28f, 300, 20, "a"))
    }
    @Test fun cancellation_invalidates_posted_session() {
        val state = new_state(); tap(state); assertTrue(state.begin(200, 10f, 10f, 300, 20, "a"))
        val generation = state.generation; state.cancel()
        assertNotEquals(generation, state.generation)
        assertFalse(state.active)
    }
    @Test fun new_down_invalidates_posted_session() {
        val state = new_state(); state.begin(100, 0f, 0f, 300, 20, "a")
        val generation = state.generation; state.begin(120, 1f, 1f, 300, 20, "a")
        assertNotEquals(generation, state.generation)
    }
    @Test fun pointer_changes_never_become_a_tap() {
        val state = new_state(); state.begin(100, 10f, 10f, 300, 20, "a")
        state.multiple(); state.finish(120)
        assertFalse(state.begin(200, 10f, 10f, 300, 20, "a"))
    }
    @Test fun long_press_is_not_first_tap() {
        val state = new_state(); state.begin(100, 10f, 10f, 300, 20, "a")
        state.long_press(); state.finish(120)
        assertFalse(state.begin(200, 10f, 10f, 300, 20, "a"))
    }
    @Test fun held_press_is_not_first_tap() {
        val state = new_state(); state.begin(100, 10f, 10f, 300, 20, "a"); state.finish(600)
        assertFalse(state.begin(620, 10f, 10f, 300, 20, "a"))
    }
    @Test fun swallowed_second_tap_does_not_start_a_triple_sequence() {
        val state = new_state(); tap(state); assertTrue(state.begin(200, 10f, 10f, 300, 20, "a"))
        state.consume(); state.finish(220)
        assertFalse(state.begin(250, 10f, 10f, 300, 20, "a"))
    }
    @Test fun timeout_and_reverse_time_are_rejected() {
        val state = new_state(); tap(state)
        assertFalse(state.begin(500, 10f, 10f, 300, 20, "a"))
        val other = new_state(); tap(other)
        assertFalse(other.begin(110, 10f, 10f, 300, 20, "a"))
    }
    @Test fun up_without_down_does_not_create_a_tap() {
        val state = new_state(); state.finish(120)
        assertFalse(state.begin(200, 10f, 10f, 300, 20, "a"))
    }
    @Test fun normal_up_preserves_posted_comment_generation() {
        val state = new_state(); tap(state); assertTrue(state.begin(200, 10f, 10f, 300, 20, "a"))
        state.consume(); val generation = state.generation; state.finish(210)
        assertEquals(generation, state.generation)
    }
}
