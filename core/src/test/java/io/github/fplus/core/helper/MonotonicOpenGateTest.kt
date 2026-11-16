// Kotlin · MonotonicOpenGateTest.kt · JUnit 4 / JVM 11 · 设置入口节流回归
package io.github.fplus.core.helper

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonotonicOpenGateTest {
    @Test fun first_open_succeeds_even_before_interval_has_elapsed() { assertTrue(MonotonicOpenGate().try_acquire(0)) }
    @Test fun repeated_calls_within_interval_are_rejected() {
        val gate = MonotonicOpenGate(); assertTrue(gate.try_acquire(1000)); assertFalse(gate.try_acquire(1000)); assertFalse(gate.try_acquire(1799))
    }
    @Test fun exact_interval_boundary_is_accepted() {
        val gate = MonotonicOpenGate(); assertTrue(gate.try_acquire(1000)); assertTrue(gate.try_acquire(1800))
    }
    @Test fun failed_launch_can_retry_immediately() {
        val gate = MonotonicOpenGate(); gate.try_acquire(1000); gate.release(1000); assertTrue(gate.try_acquire(1001))
    }
    @Test fun stale_failure_does_not_release_newer_open() {
        val gate = MonotonicOpenGate(); gate.try_acquire(1000); gate.try_acquire(1800); gate.release(1000); assertFalse(gate.try_acquire(1801))
    }
    @Test fun rollback_does_not_permanently_lock_opening() {
        val gate = MonotonicOpenGate(); gate.try_acquire(100000); assertTrue(gate.try_acquire(1000))
    }
    @Test fun concurrent_entry_accepts_exactly_one_open() {
        val gate = MonotonicOpenGate(); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map { pool.submit<Boolean> { start.await(); gate.try_acquire(1000) } }
            start.countDown(); assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
        } finally { pool.shutdownNow() }
    }
    @Test(expected = IllegalArgumentException::class) fun rejects_negative_interval() { MonotonicOpenGate(-1) }
}
