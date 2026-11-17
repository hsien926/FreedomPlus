// Kotlin · CommentPanelState.kt · JVM 11 / Android 调用层 · 评论面板实时可见状态
package io.github.fplus.core.helper

import java.util.WeakHashMap

internal class CommentPanelState<T : Any>(private val is_visible: (T) -> Boolean) {
    // 只弱持有根视图；不靠 onStop/onDestroy 回调复位全局布尔值。
    private val panels = WeakHashMap<T, Unit>()

    fun bind(panel: T) {
        panels[panel] = Unit
    }

    fun is_open(): Boolean = panels.keys.any(is_visible)
}
