// Kotlin · CommentImageState.kt · JVM 11 / Android 7+ · 评论浏览状态
package io.github.fplus.core.helper

import java.util.WeakHashMap

data class CommentMedia(val image_urls: List<String>, val video_urls: List<String> = emptyList())

class CommentImageState {
    private var comment_key = ""
    private var images = emptyList<CommentMedia>()
    private var page_index = 0
    val image_count get() = images.size

    fun bind(key: String, media: List<CommentMedia>) {
        val snapshot = media.map { CommentMedia(it.image_urls.toList(), it.video_urls.toList()) }
        if (comment_key != key || images != snapshot) page_index = 0
        comment_key = key
        images = snapshot
        if (page_index !in images.indices) page_index = 0
    }

    fun update_page(text: String?) {
        page_index = parse_page(text, images.size) ?: 0
    }

    fun current_media(): CommentMedia? = images.getOrNull(page_index)

    companion object {
        private val page_pattern = Regex("^\\s*(\\d+)\\s*[/／]\\s*(\\d+)\\s*$")

        fun parse_page(text: String?, image_count: Int): Int? {
            if (image_count <= 0 || text == null) return null
            val match = page_pattern.matchEntire(text) ?: return null
            val current = match.groupValues[1].toIntOrNull() ?: return null
            val total = match.groupValues[2].toIntOrNull() ?: return null
            return (current - 1).takeIf { total == image_count && current in 1..total }
        }
    }
}

class CommentImageStateStore {
    private val states = WeakHashMap<Any, CommentImageState>()

    @Synchronized
    fun for_owner(owner: Any): CommentImageState = states.getOrPut(owner) { CommentImageState() }

    @Synchronized
    fun associate(owner: Any, state: CommentImageState) { states[owner] = state }
}
