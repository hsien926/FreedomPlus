// Kotlin · MonotonicOpenGate.kt · JVM 11 / Android 7+ · 设置入口节流
package io.github.fplus.core.helper

class MonotonicOpenGate(private val interval_ms: Long = 800L) {
    private var last_open_at: Long? = null

    init { require(interval_ms >= 0L) }

    @Synchronized
    fun try_acquire(now_ms: Long): Boolean {
        require(now_ms >= 0L)
        val previous = last_open_at
        if (previous != null && now_ms >= previous && now_ms - previous < interval_ms) return false
        last_open_at = now_ms
        return true
    }

    @Synchronized
    fun release(now_ms: Long) {
        if (last_open_at == now_ms) last_open_at = null
    }
}
