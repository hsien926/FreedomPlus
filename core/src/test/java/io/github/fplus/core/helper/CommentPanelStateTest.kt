// Kotlin · CommentPanelStateTest.kt · JUnit 4 / JVM 11 · 评论展开与延时长按回归
package io.github.fplus.core.helper

import org.junit.Assert.*
import org.junit.Test

class CommentPanelStateTest {
    private class Panel {
        var attached = true
        var shown = true
        var window_visible = true
        var on_screen = true
        fun visible() = attached && shown && window_visible && on_screen
    }

    private fun new_state() = CommentPanelState<Panel> { it.visible() }

    @Test fun video_without_comments_is_not_blocked() {
        assertFalse(new_state().is_open())
    }

    @Test fun visible_first_comment_blocks_module_gestures() {
        val panel = Panel()
        val state = new_state()
        state.bind(panel)
        assertTrue(state.is_open())
    }

    @Test fun precreated_hidden_panel_does_not_block_video() {
        val panel = Panel().apply { shown = false }
        val state = new_state()
        state.bind(panel)
        assertFalse(state.is_open())
        panel.shown = true
        assertTrue(state.is_open())
    }

    @Test fun panel_opened_between_down_and_timeout_is_rechecked() {
        val panel = Panel().apply { on_screen = false }
        val state = new_state()
        state.bind(panel)
        assertFalse(state.is_open()) // ACTION_DOWN 时尚未展开。
        panel.on_screen = true
        assertTrue(state.is_open()) // 延时长按和原生回调须重新读取。
    }

    @Test fun closing_panel_restores_video_without_lifecycle_callback() {
        val panel = Panel()
        val state = new_state()
        state.bind(panel)
        assertTrue(state.is_open())
        panel.shown = false
        assertFalse(state.is_open())
        panel.shown = true
        assertTrue(state.is_open())
    }

    @Test fun detached_panel_does_not_leave_a_global_open_flag() {
        val panel = Panel()
        val state = new_state()
        state.bind(panel)
        panel.attached = false
        assertFalse(state.is_open())
    }

    @Test fun offscreen_panel_does_not_block_video() {
        val panel = Panel().apply { on_screen = false }
        val state = new_state()
        state.bind(panel)
        assertFalse(state.is_open())
    }

    @Test fun hidden_activity_window_does_not_block_another_page() {
        val panel = Panel().apply { window_visible = false }
        val state = new_state()
        state.bind(panel)
        assertFalse(state.is_open())
    }

    @Test fun closing_one_panel_does_not_clear_another_visible_panel() {
        val first = Panel()
        val second = Panel()
        val state = new_state()
        state.bind(first)
        state.bind(second)
        first.shown = false
        assertTrue(state.is_open())
        second.shown = false
        assertFalse(state.is_open())
    }

    @Test fun rebinding_same_view_does_not_leave_duplicate_state() {
        val panel = Panel()
        val state = new_state()
        state.bind(panel)
        state.bind(panel)
        assertTrue(state.is_open())
        panel.shown = false
        assertFalse(state.is_open())
    }
}
