// Kotlin · HighRefreshSyncPolicyTest.kt · JUnit 4 / JVM 11 · JDK 17
package io.github.fplus.core.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighRefreshSyncPolicyTest {
    @Test
    fun starts_uninitialized_and_disabled() {
        val state = HighRefreshSharedState()
        assertFalse(state.initialized)
        assertFalse(state.enabled)
        assertEquals(0L, state.revision)
    }

    @Test
    fun first_host_migration_preserves_enabled_flag() {
        assertEquals(HighRefreshSharedState(true, true, 1),
            HighRefreshSyncPolicy.migrate(HighRefreshSharedState(), true))
    }

    @Test
    fun first_host_migration_preserves_disabled_flag() {
        assertEquals(HighRefreshSharedState(true, false, 1),
            HighRefreshSyncPolicy.migrate(HighRefreshSharedState(), false))
    }

    @Test
    fun subsequent_migration_never_overwrites_shared_flag() {
        val disabled = HighRefreshSharedState(true, false, 4)
        val enabled = HighRefreshSharedState(true, true, 7)
        assertEquals(disabled, HighRefreshSyncPolicy.migrate(disabled, true))
        assertEquals(enabled, HighRefreshSyncPolicy.migrate(enabled, false))
    }

    @Test
    fun writes_are_idempotent() {
        val enabled = HighRefreshSharedState(true, true, 3)
        assertEquals(enabled, HighRefreshSyncPolicy.update(enabled, true))
    }

    @Test
    fun toggles_increment_revision_once() {
        val current = HighRefreshSharedState(true, true, 3)
        assertEquals(HighRefreshSharedState(true, false, 4),
            HighRefreshSyncPolicy.update(current, false))
    }

    @Test
    fun module_owner_can_read_and_write() {
        assertTrue(HighRefreshSyncPolicy.can_read(11000, 11000, emptySet()))
        assertTrue(HighRefreshSyncPolicy.can_write(11000, 11000, emptySet()))
    }

    @Test
    fun douyin_can_read_and_write() {
        val packages = setOf(HighRefreshSyncPolicy.host_package)
        assertTrue(HighRefreshSyncPolicy.can_read(10299, 11000, packages))
        assertTrue(HighRefreshSyncPolicy.can_write(10299, 11000, packages))
    }

    @Test
    fun powerkeeper_can_read_but_not_write() {
        val packages = setOf(HighRefreshSyncPolicy.powerkeeper_package)
        assertTrue(HighRefreshSyncPolicy.can_read(1000, 11000, packages))
        assertFalse(HighRefreshSyncPolicy.can_write(1000, 11000, packages))
    }

    @Test
    fun shell_unknown_and_lite_callers_are_rejected() {
        for (packages in listOf(emptySet(), setOf("unrelated.app"),
            setOf("com.ss.android.ugc.aweme.lite"))) {
            assertFalse(HighRefreshSyncPolicy.can_read(2000, 11000, packages))
            assertFalse(HighRefreshSyncPolicy.can_write(2000, 11000, packages))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun revision_overflow_is_rejected() {
        HighRefreshSyncPolicy.update(HighRefreshSharedState(true, true, Long.MAX_VALUE), false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negative_revision_is_rejected() {
        HighRefreshSyncPolicy.update(HighRefreshSharedState(false, false, -1), true)
    }
}
