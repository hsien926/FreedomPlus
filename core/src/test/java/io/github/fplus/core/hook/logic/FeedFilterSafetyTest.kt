// Kotlin · FeedFilterSafetyTest.kt · JUnit 4 / JVM 11 · 参数完整性与致命错误边界
package io.github.fplus.core.hook.logic

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.Collections

class FeedFilterSafetyTest {
    class Item(@JvmField val isAd: Boolean)
    class Wrapped(@JvmField val aweme: Item)
    class BrokenList : AbstractList<Item>() {
        override val size: Int get() = 2
        override fun get(index: Int): Item = if (index == 0) Item(false) else throw IllegalStateException("坏列表")
    }

    @Test fun immutable_argument_replacement_preserves_boolean_and_all_other_arguments() {
        for (refresh in listOf(false, true)) {
            val ordinary = Item(false); val ad = Item(true)
            val source = Collections.unmodifiableList(listOf(ordinary, ad))
            val marker = Any(); val args = arrayOf<Any?>(source, refresh, marker)
            val result = FeedFilterSafety.select_arguments(args)!!
            assertEquals(3, result.args.size)
            assertEquals(refresh, result.args[1])
            assertSame(marker, result.args[2])
            assertEquals(listOf(ordinary), result.args[0])
            assertSame(source, args[0])
            assertEquals(listOf(ordinary, ad), source)
            assertNotSame(args, result.args)
        }
    }

    @Test fun wrapped_argument_filter_preserves_extra_flag() {
        val ordinary = Wrapped(Item(false)); val ad = Wrapped(Item(true))
        val args = arrayOf<Any?>(listOf(ordinary, ad), false)
        val result = FeedFilterSafety.select_arguments(args, wrapped = true)!!
        assertEquals(listOf(ordinary), result.args[0])
        assertEquals(false, result.args[1])
        assertEquals(2, (args[0] as List<*>).size)
    }

    @Test fun all_ad_arguments_are_passed_through_without_empty_replacement() {
        val source = listOf(Item(true)); val args = arrayOf<Any?>(source, true)
        assertNull(FeedFilterSafety.select_arguments(args))
        assertSame(source, args[0])
        assertEquals(true, args[1])
    }

    @Test fun iterator_failure_discards_partial_selection() {
        val source = BrokenList(); val args = arrayOf<Any?>(source, true)
        assertNull(FeedFilterSafety.select_arguments(args))
        assertSame(source, args[0])
        assertEquals(2, source.size)
    }

    @Test fun ordinary_failure_is_isolated() {
        assertNull(FeedFilterSafety.guarded<Unit> { throw IllegalStateException("可恢复异常") })
    }

    @Test(expected = OutOfMemoryError::class)
    fun fatal_vm_error_is_not_swallowed() {
        FeedFilterSafety.guarded<Unit> { throw OutOfMemoryError("致命错误") }
    }

    @Test(expected = OutOfMemoryError::class)
    fun wrapped_fatal_vm_error_is_not_swallowed() {
        FeedFilterSafety.guarded<Unit> { throw InvocationTargetException(OutOfMemoryError("反射致命错误")) }
    }

    @Test(expected = OutOfMemoryError::class)
    fun initializer_wrapped_fatal_vm_error_is_not_swallowed() {
        FeedFilterSafety.guarded<Unit> { throw ExceptionInInitializerError(OutOfMemoryError("初始化致命错误")) }
    }

    @Test(expected = ThreadDeath::class)
    fun thread_termination_is_not_swallowed() {
        FeedFilterSafety.guarded<Unit> { throw ThreadDeath() }
    }
}
