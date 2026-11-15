// Kotlin · HighRefreshRatePolicyTest.kt · JUnit 4 / JVM 11 · JDK 17
package io.github.fplus.core.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighRefreshRatePolicyTest {
    private val current = RefreshMode(1, 1080, 2400, 60f)
    private fun mode(id: Int, hz: Float, width: Int = 1080, height: Int = 2400) =
        RefreshMode(id, width, height, hz)

    @Test
    fun selects_highest_rate_without_changing_resolution() {
        assertEquals(mode(3, 144f), HighRefreshRatePolicy.select_mode(current, listOf(
            current, mode(2, 120f), mode(3, 144f), mode(4, 165f, 720, 1600),
        )))
    }

    @Test
    fun supports_90_hz_without_hardcoding_120() {
        assertEquals(mode(2, 90f), HighRefreshRatePolicy.select_mode(current, listOf(current, mode(2, 90f))))
    }

    @Test
    fun keeps_current_mode_when_top_rates_tie() {
        val selected = mode(3, 120f)
        assertEquals(selected, HighRefreshRatePolicy.select_mode(selected, listOf(mode(2, 120f), selected)))
    }

    @Test
    fun resolves_top_rate_ties_deterministically() {
        val choices = listOf(mode(5, 120f), mode(2, 120f), current)
        assertEquals(mode(2, 120f), HighRefreshRatePolicy.select_mode(current, choices))
        assertEquals(mode(2, 120f), HighRefreshRatePolicy.select_mode(current, choices.reversed()))
    }

    @Test
    fun does_not_override_60_hz_only_displays() {
        assertNull(HighRefreshRatePolicy.select_mode(current, listOf(current, mode(2, 59.94f))))
    }

    @Test
    fun rejects_invalid_modes() {
        assertNull(HighRefreshRatePolicy.select_mode(current, listOf(
            mode(0, 120f), mode(-1, 144f), mode(2, Float.NaN),
            mode(3, Float.POSITIVE_INFINITY), mode(4, -120f),
        )))
    }

    @Test
    fun rejects_empty_or_mismatched_resolution_sets() {
        assertNull(HighRefreshRatePolicy.select_mode(current, emptyList()))
        assertNull(HighRefreshRatePolicy.select_mode(current, listOf(mode(2, 120f, 2400, 1080))))
        assertNull(HighRefreshRatePolicy.select_mode(current.copy(width = 0), listOf(mode(2, 120f))))
        assertNull(HighRefreshRatePolicy.select_mode(current.copy(height = -1), listOf(mode(2, 120f))))
    }

    @Test
    fun preserves_fractional_refresh_rates() {
        assertEquals(mode(2, 119.88f), HighRefreshRatePolicy.select_mode(current, listOf(mode(2, 119.88f))))
        assertEquals(119.88f, HighRefreshRatePolicy.override_surface_rate(29.97f, 119.88f, 1)!!, 0f)
    }

    @Test
    fun chooses_the_new_resolution_after_a_display_change() {
        val folded = RefreshMode(7, 1768, 2208, 60f)
        val folded_high = RefreshMode(8, 1768, 2208, 120f)
        assertEquals(folded_high, HighRefreshRatePolicy.select_mode(folded, listOf(mode(2, 144f), folded_high)))
    }

    @Test
    fun accepts_single_or_matching_display_targets() {
        assertEquals(90f, HighRefreshRatePolicy.common_rate(listOf(90f)), 0f)
        assertEquals(120f, HighRefreshRatePolicy.common_rate(listOf(120f, 120f)), 0f)
    }

    @Test
    fun does_not_guess_for_inactive_or_ambiguous_displays() {
        for (rates in listOf(emptyList(), listOf(0f), listOf(60f), listOf(90f, 120f), listOf(120f, 0f),
            listOf(Float.NaN), listOf(Float.POSITIVE_INFINITY))) {
            assertEquals(0f, HighRefreshRatePolicy.common_rate(rates), 0f)
        }
    }

    @Test
    fun raises_positive_source_requests() {
        assertEquals(120f, HighRefreshRatePolicy.override_surface_rate(60f, 120f, 1)!!, 0f)
        assertEquals(144f, HighRefreshRatePolicy.override_surface_rate(30f, 144f, 0)!!, 0f)
    }

    @Test
    fun preserves_zero_clear_and_invalid_source_requests() {
        for (rate in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(HighRefreshRatePolicy.override_surface_rate(rate, 120f, 1))
        }
    }

    @Test
    fun does_not_reduce_existing_high_requests() {
        assertNull(HighRefreshRatePolicy.override_surface_rate(120f, 120f, 1))
        assertNull(HighRefreshRatePolicy.override_surface_rate(144f, 120f, 0))
    }

    @Test
    fun rejects_invalid_or_inactive_targets() {
        for (rate in listOf(0f, 60f, -120f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(HighRefreshRatePolicy.override_surface_rate(30f, rate, 1))
        }
    }

    @Test
    fun preserves_invalid_api_arguments_for_original_validation() {
        assertNull(HighRefreshRatePolicy.override_surface_rate(30f, 120f, -1))
        assertNull(HighRefreshRatePolicy.override_surface_rate(30f, 120f, 2))
        assertNull(HighRefreshRatePolicy.override_surface_rate(30f, 120f, 0, -1))
        assertNull(HighRefreshRatePolicy.override_surface_rate(30f, 120f, 1, 2))
    }

    @Test
    fun accepts_both_public_change_strategies_without_rewriting_them() {
        for (strategy in listOf(0, 1)) {
            assertEquals(120f, HighRefreshRatePolicy.override_surface_rate(60f, 120f, 1, strategy)!!, 0f)
        }
    }
}
