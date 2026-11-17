// Kotlin · SourceFilterLogicTest.kt · JUnit 4 / JVM 11 · Source 过滤回归
package io.github.fplus.core.hook.logic

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class SourceFilterLogicTest {
    class Item(@JvmField val isAd: Boolean)
    class Wrapped(@JvmField val aweme: Item?)
    class Direct(@JvmField var items: List<Item>)
    class Follow(@JvmField var mItems: List<Wrapped>)
    class ReadOnly(@JvmField val items: List<Item>)
    class Model(@JvmField var mData: Any?)
    class Host(@JvmField var mModel: Any?)
    class Alternate(@JvmField var mItems: List<Item>)
    class Dual(@JvmField var items: List<Item>, @JvmField var mItems: List<Wrapped>)
    class Mixed(@JvmField var items: List<String>, @JvmField var payload: List<Item>)
    class Unrelated(@JvmField var items: List<String>)
    class Ambiguous(@JvmField var items: List<Item>, @JvmField var history: List<Item>)
    class ConcreteList : ArrayList<Item>()
    class Concrete(@JvmField var items: ConcreteList)
    class BrokenList(private val ordinary: Item) : AbstractList<Item>() {
        override val size: Int get() = 2
        override fun get(index: Int): Item = if (index == 0) ordinary else throw IllegalStateException("坏迭代器")
    }

    @Test fun direct_filter_replaces_reference_without_mutating_original() {
        val ordinary = Item(false); val ad = Item(true)
        val original = Collections.unmodifiableList(listOf(ordinary, ad))
        val host = Direct(original)
        assertEquals(1, SourceFilterLogic.filter(host, false, "test"))
        assertEquals(listOf(ordinary), host.items)
        assertEquals(listOf(ordinary, ad), original)
        assertNotSame(original, host.items)
    }

    @Test fun wrapped_filter_keeps_non_ad_and_null_payload() {
        val ordinary = Wrapped(Item(false)); val missing = Wrapped(null); val ad = Wrapped(Item(true))
        val original = listOf(ordinary, ad, missing)
        val host = Follow(original)
        assertEquals(1, SourceFilterLogic.filter(host, true, "test"))
        assertEquals(listOf(ordinary, missing), host.mItems)
        assertEquals(3, original.size)
    }

    @Test fun all_ad_source_is_not_emptied() {
        val original = listOf(Item(true), Item(true)); val host = Direct(original)
        assertEquals(-1, SourceFilterLogic.filter(host, false, "test"))
        assertSame(original, host.items)
        assertEquals(2, original.size)
    }

    @Test fun final_list_field_is_not_modified() {
        val original = listOf(Item(false), Item(true)); val host = ReadOnly(original)
        assertEquals(-1, SourceFilterLogic.filter(host, false, "test"))
        assertSame(original, host.items)
        assertEquals(2, original.size)
    }

    @Test fun incompatible_concrete_list_field_is_not_rewritten_in_place() {
        val original = ConcreteList().apply { add(Item(false)); add(Item(true)) }
        val host = Concrete(original)
        assertEquals(-1, SourceFilterLogic.filter(host, false, "test"))
        assertSame(original, host.items)
        assertEquals(2, original.size)
    }

    @Test fun delayed_model_initialization_retries_path_discovery() {
        val host = Host(null)
        assertEquals(-1, SourceFilterLogic.filter(host, false, "test"))
        val data = Direct(listOf(Item(false), Item(true)))
        host.mModel = Model(data)
        assertEquals(1, SourceFilterLogic.filter(host, false, "test"))
        assertEquals(1, data.items.size)
    }

    @Test fun delayed_data_initialization_does_not_cache_failure() {
        val model = Model(null); val host = Host(model)
        assertEquals(-1, SourceFilterLogic.filter(host, false, "test"))
        val data = Direct(listOf(Item(false), Item(true)))
        model.mData = data
        assertEquals(1, SourceFilterLogic.filter(host, false, "test"))
        assertEquals(1, data.items.size)
    }

    @Test fun data_runtime_type_drift_invalidates_cached_fields() {
        val first = Direct(listOf(Item(false), Item(true)))
        val model = Model(first); val host = Host(model)
        assertEquals(1, SourceFilterLogic.filter(host, false, "test"))
        val second = Alternate(listOf(Item(false), Item(true)))
        model.mData = second
        assertEquals(1, SourceFilterLogic.filter(host, false, "test"))
        assertEquals(1, first.items.size)
        assertEquals(1, second.mItems.size)
    }

    @Test fun wrapped_and_direct_paths_have_separate_cache_keys() {
        val host = Dual(listOf(Item(false), Item(true)), listOf(Wrapped(Item(false)), Wrapped(Item(true))))
        assertEquals(1, SourceFilterLogic.filter(host, false, "test"))
        assertEquals(2, host.mItems.size)
        assertEquals(1, SourceFilterLogic.filter(host, true, "test"))
        assertEquals(1, host.items.size)
        assertEquals(1, host.mItems.size)
    }

    @Test fun broken_iterator_leaves_original_source_reference_and_contents() {
        val original = BrokenList(Item(false)); val host = Direct(original)
        assertEquals(-1, SourceFilterLogic.filter(host, false, "test"))
        assertSame(original, host.items)
        assertEquals(2, original.size)
    }

    @Test(expected = OutOfMemoryError::class)
    fun fatal_iterator_error_is_propagated_without_source_replacement() {
        val original = object : AbstractList<Item>() {
            override val size: Int get() = 2
            override fun get(index: Int): Item = if (index == 0) Item(false) else throw OutOfMemoryError("迭代致命错误")
        }
        val host = Direct(original)
        try {
            SourceFilterLogic.filter(host, false, "test")
        } finally {
            assertSame(original, host.items)
        }
    }

    @Test fun unrelated_items_name_does_not_override_verified_payload_list() {
        val strings = listOf("广告文案", "普通文案")
        val host = Mixed(strings, listOf(Item(false), Item(true)))
        assertEquals(1, SourceFilterLogic.filter(host, false, "test"))
        assertSame(strings, host.items)
        assertEquals(1, host.payload.size)
    }

    @Test fun only_unrelated_items_list_is_passed_through() {
        val strings = listOf("a", "b"); val host = Unrelated(strings)
        assertEquals(-1, SourceFilterLogic.filter(host, false, "test"))
        assertSame(strings, host.items)
    }

    @Test fun multiple_matching_lists_are_ambiguous_and_unchanged() {
        val primary = listOf(Item(false), Item(true)); val history = listOf(Item(false), Item(true))
        val host = Ambiguous(primary, history)
        assertEquals(-1, SourceFilterLogic.filter(host, false, "test"))
        assertSame(primary, host.items)
        assertSame(history, host.history)
    }

    @Test fun empty_typed_source_remains_empty_without_false_failure_cache() {
        val host = Direct(emptyList())
        assertEquals(0, SourceFilterLogic.filter(host, false, "test"))
        host.items = listOf(Item(false), Item(true))
        assertEquals(1, SourceFilterLogic.filter(host, false, "test"))
    }

    @Test fun absent_flag_does_not_modify_other_objects() {
        val original = listOf("isAd", "true"); val host = Unrelated(original)
        assertEquals(-1, SourceFilterLogic.filter(host, false, "test"))
        assertSame(original, host.items)
    }
}
