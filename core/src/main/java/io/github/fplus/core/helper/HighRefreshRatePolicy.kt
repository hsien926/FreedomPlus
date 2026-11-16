// Kotlin · HighRefreshRatePolicy.kt · JVM 11 / Android API 24+ · JDK 17
package io.github.fplus.core.helper

internal data class RefreshMode(
    val mode_id: Int,
    val width: Int,
    val height: Int,
    val refresh_rate: Float,
)

internal object HighRefreshRatePolicy {
    fun select_mode(current: RefreshMode, supported: List<RefreshMode>): RefreshMode? {
        if (current.width <= 0 || current.height <= 0) return null
        val candidates = supported.filter {
            it.mode_id > 0 && it.width == current.width && it.height == current.height &&
                it.refresh_rate.isFinite() && it.refresh_rate > 60f
        }
        val highest_rate = candidates.maxOfOrNull { it.refresh_rate } ?: return null
        val highest_modes = candidates.filter { it.refresh_rate == highest_rate }
        // 同率时保留当前模式；否则按模式 ID 稳定选择，避免显示变更回调来回切换。
        return highest_modes.firstOrNull { it.mode_id == current.mode_id }
            ?: highest_modes.minByOrNull { it.mode_id }
    }

    fun common_rate(rates: Collection<Float>): Float {
        val first = rates.firstOrNull() ?: return 0f
        // Surface 的公开参数没有显示器归属，多个活动窗口请求不一致时不猜测。
        return if (first.isFinite() && first > 60f && rates.all { it == first }) first else 0f
    }

    fun override_surface_rate(
        requested_rate: Float,
        target_rate: Float,
        compatibility: Int,
        change_strategy: Int? = null,
    ): Float? {
        if (!requested_rate.isFinite() || requested_rate <= 0f) return null
        if (!target_rate.isFinite() || target_rate <= 60f || requested_rate >= target_rate) return null
        if (compatibility != 0 && compatibility != 1) return null
        if (change_strategy != null && change_strategy != 0 && change_strategy != 1) return null
        // 0 是暂停/清除意图，非法值应继续由原 API 校验；只上调有效的正值请求。
        return target_rate
    }
}
