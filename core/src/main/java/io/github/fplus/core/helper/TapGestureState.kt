// Kotlin · TapGestureState.kt · JVM 11 / Android 调用层 · 每个 View 独立的轻点状态
package io.github.fplus.core.helper

internal class TapGestureState(private val long_press_timeout: Long) {
    var generation = 0L
        private set
    var down_time = 0L
        private set
    var down_x = 0f
        private set
    var down_y = 0f
        private set
    var active = false
        private set
    var moved = false
        private set
    private var multiple_pointers = false
    private var consumed = false
    private var last_tap_valid = false
    private var last_up_time = 0L
    private var last_x = 0f
    private var last_y = 0f
    private var last_media_id: String? = null
    private var media_id: String? = null

    fun begin(time: Long, x: Float, y: Float, double_timeout: Long, double_slop: Int, current_media: String?): Boolean {
        val dx = x - last_x
        val dy = y - last_y
        val elapsed = time - last_up_time
        val candidate = last_tap_valid && !current_media.isNullOrBlank() && current_media == last_media_id &&
            elapsed in 0..double_timeout && dx * dx + dy * dy <= double_slop.toFloat() * double_slop
        generation++
        down_time = time
        down_x = x
        down_y = y
        media_id = current_media
        active = true
        moved = false
        multiple_pointers = false
        consumed = false
        last_tap_valid = false
        return candidate
    }

    fun move(x: Float, y: Float, slop: Int): Boolean {
        if (!active || moved) return false
        val dx = x - down_x
        val dy = y - down_y
        if (dx * dx + dy * dy <= slop.toFloat() * slop) return false
        moved = true
        generation++
        last_tap_valid = false
        return true
    }

    fun consume() {
        consumed = true
        last_tap_valid = false
    }

    fun multiple() {
        multiple_pointers = true
        moved = true
        generation++
        last_tap_valid = false
    }

    fun long_press() {
        moved = true
        last_tap_valid = false
    }

    fun finish(time: Long, cancelled: Boolean = false) {
        val duration = time - down_time
        last_tap_valid = active && !cancelled && !moved && !multiple_pointers && !consumed &&
            duration in 0 until long_press_timeout
        last_up_time = if (last_tap_valid) time else 0L
        last_x = down_x
        last_y = down_y
        last_media_id = media_id
        active = false
        if (cancelled) generation++
    }

    fun cancel() {
        active = false
        moved = true
        last_tap_valid = false
        generation++
    }
}
