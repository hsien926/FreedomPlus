// Kotlin · SystemHighRefreshPolicy.kt · JVM 11 / PowerKeeper 主屏策略 · JDK 17
package io.github.fplus.core.helper

internal object SystemHighRefreshPolicy {
    fun is_target_video_request(
        package_name: String?, requested_rate: Int, cookie: Int, video_cookie: Int,
        main_scene: Boolean,
    ): Boolean = package_name == HighRefreshSyncPolicy.host_package && main_scene &&
        requested_rate > 0 && video_cookie > 0 && cookie == video_cookie

    fun can_replay_scene(
        foreground_package: String?, current_cookie: Int, video_cookie: Int,
        main_scene: Boolean,
    ): Boolean = foreground_package == HighRefreshSyncPolicy.host_package && main_scene &&
        video_cookie > 0 && current_cookie == video_cookie

    fun is_cached_state_enabled(
        state: HighRefreshSharedState, last_read_ms: Long, now_ms: Long, max_age_ms: Long,
    ): Boolean = state.initialized && state.enabled && last_read_ms >= 0 &&
        max_age_ms > 0 && now_ms >= last_read_ms && now_ms - last_read_ms <= max_age_ms

    fun override_rate(
        package_name: String?,
        requested_rate: Int,
        cookie: Int,
        video_cookie: Int,
        target_rate: Int,
        enabled: Boolean,
        main_scene: Boolean,
        power_save: Boolean,
        thermal_status: Int,
    ): Int? {
        if (!enabled || !is_target_video_request(
                package_name, requested_rate, cookie, video_cookie, main_scene,
            )) return null
        if (target_rate <= 60 || requested_rate >= target_rate) return null
        if (power_save || thermal_status < 0 || thermal_status >= 2) return null
        return target_rate
    }
}
