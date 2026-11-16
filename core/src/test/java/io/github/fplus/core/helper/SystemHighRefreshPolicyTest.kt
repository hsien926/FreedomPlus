// Kotlin · SystemHighRefreshPolicyTest.kt · JUnit 4 / JVM 11 · JDK 17
package io.github.fplus.core.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemHighRefreshPolicyTest {
    private fun decide(
        pkg: String? = HighRefreshSyncPolicy.host_package,
        requested: Int = 60,
        cookie: Int = 248,
        video_cookie: Int = 248,
        target: Int = 120,
        enabled: Boolean = true,
        main: Boolean = true,
        power_save: Boolean = false,
        thermal: Int = 0,
    ) = SystemHighRefreshPolicy.override_rate(
        pkg, requested, cookie, video_cookie, target, enabled, main, power_save, thermal,
    )

    @Test
    fun overrides_only_enabled_douyin_video_policy() {
        assertEquals(120, decide())
        assertNull(decide(enabled = false))
        assertNull(decide(pkg = "com.miui.home"))
        assertNull(decide(pkg = null))
        assertNull(decide(pkg = "com.ss.android.ugc.aweme.lite"))
    }

    @Test
    fun preserves_zero_negative_and_already_high_rates() {
        for (rate in listOf(0, -1, 120, 144)) assertNull(decide(requested = rate))
    }

    @Test
    fun supports_main_screen_maximum_without_hardcoding_120() {
        assertEquals(90, decide(target = 90))
        assertEquals(144, decide(target = 144))
        assertEquals(165, decide(target = 165))
    }

    @Test
    fun preserves_clear_requests_and_60_hz_only_displays() {
        assertNull(decide(requested = 0))
        assertNull(decide(target = 60))
        assertNull(decide(target = 0))
    }

    @Test
    fun never_applies_main_screen_rate_to_rear_or_other_display() {
        assertNull(decide(main = false))
    }

    @Test
    fun preserves_thermal_cookie_and_other_system_scene_cookies() {
        for (cookie in listOf(244, 245, 247, 252, 253, 254)) assertNull(decide(cookie = cookie))
    }

    @Test
    fun uses_verified_video_cookie_instead_of_fixed_argument_guess() {
        assertEquals(120, decide(cookie = 300, video_cookie = 300))
        assertNull(decide(video_cookie = 0))
        assertNull(decide(cookie = 248, video_cookie = 300))
    }

    @Test
    fun keeps_power_save_and_moderate_or_higher_thermal_limits() {
        assertNull(decide(power_save = true))
        for (thermal in listOf(-1, 2, 3, 4, 5, 6)) assertNull(decide(thermal = thermal))
        assertEquals(120, decide(thermal = 1))
    }

    @Test
    fun only_positive_main_video_requests_remain_replayable() {
        val host = HighRefreshSyncPolicy.host_package
        assertEquals(true, SystemHighRefreshPolicy.is_target_video_request(host, 60, 248, 248, true))
        for (rate in listOf(0, -1)) {
            assertEquals(false, SystemHighRefreshPolicy.is_target_video_request(host, rate, 248, 248, true))
        }
        assertEquals(false, SystemHighRefreshPolicy.is_target_video_request(host, 60, 253, 248, true))
        assertEquals(false, SystemHighRefreshPolicy.is_target_video_request(host, 60, 248, 248, false))
        assertEquals(false, SystemHighRefreshPolicy.is_target_video_request("other.app", 60, 248, 248, true))
    }

    @Test
    fun replay_stops_after_foreground_screen_or_cookie_change() {
        val host = HighRefreshSyncPolicy.host_package
        assertEquals(true, SystemHighRefreshPolicy.can_replay_scene(host, 248, 248, true))
        for (cookie in listOf(0, 245, 247, 252, 253, 254)) {
            assertEquals(false, SystemHighRefreshPolicy.can_replay_scene(host, cookie, 248, true))
        }
        assertEquals(false, SystemHighRefreshPolicy.can_replay_scene("com.miui.home", 248, 248, true))
        assertEquals(false, SystemHighRefreshPolicy.can_replay_scene(null, 248, 248, true))
        assertEquals(false, SystemHighRefreshPolicy.can_replay_scene(host, 248, 248, false))
        assertEquals(false, SystemHighRefreshPolicy.can_replay_scene(host, 248, 0, true))
    }

    @Test
    fun cached_state_expires_without_binder_calls_on_policy_thread() {
        val state = HighRefreshSharedState(true, true, 1)
        assertEquals(true, SystemHighRefreshPolicy.is_cached_state_enabled(state, 1_000, 1_000, 20_000))
        assertEquals(true, SystemHighRefreshPolicy.is_cached_state_enabled(state, 1_000, 21_000, 20_000))
        assertEquals(false, SystemHighRefreshPolicy.is_cached_state_enabled(state, 1_000, 21_001, 20_000))
    }

    @Test
    fun uninitialized_disabled_and_failed_reads_never_enable_cached_state() {
        assertEquals(false, SystemHighRefreshPolicy.is_cached_state_enabled(
            HighRefreshSharedState(), 1_000, 1_100, 20_000))
        assertEquals(false, SystemHighRefreshPolicy.is_cached_state_enabled(
            HighRefreshSharedState(true, false, 2), 1_000, 1_100, 20_000))
        assertEquals(false, SystemHighRefreshPolicy.is_cached_state_enabled(
            HighRefreshSharedState(true, true, 2), -1, 1_100, 20_000))
    }

    @Test
    fun invalid_cache_clock_and_lifetime_are_rejected() {
        val state = HighRefreshSharedState(true, true, 1)
        assertEquals(false, SystemHighRefreshPolicy.is_cached_state_enabled(state, 1_000, 999, 20_000))
        assertEquals(false, SystemHighRefreshPolicy.is_cached_state_enabled(state, 1_000, 1_100, 0))
        assertEquals(false, SystemHighRefreshPolicy.is_cached_state_enabled(state, 1_000, 1_100, -1))
    }
}
