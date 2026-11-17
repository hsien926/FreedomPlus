// Kotlin · MethodOwnerResolverTest.kt · JUnit 4 / JVM 11 · 继承生命周期 Hook 声明类回归
package io.github.fplus.core.helper

import org.junit.Assert.*
import org.junit.Test

class MethodOwnerResolverTest {
    private open class LifecycleBase {
        fun onViewCreated(view: Any, bundle: String?) = Unit
    }
    private open class MiddleFragment : LifecycleBase()
    private class CommentFragment : MiddleFragment()
    private class OtherFragment : LifecycleBase()
    private class DirectFragment {
        fun onViewCreated(view: Any, bundle: String?) = Unit
    }
    private class OverloadedFragment : LifecycleBase() {
        fun onViewCreated(view: Any) = Unit
    }
    private class MissingFragment

    private fun owner(target: Class<*>?) = findMethodDeclaringClass(target,
        "onViewCreated", Any::class.java, String::class.java)

    @Test fun directly_declared_method_uses_target_class() {
        assertEquals(DirectFragment::class.java, owner(DirectFragment::class.java))
    }

    @Test fun inherited_lifecycle_resolves_the_declaring_parent() {
        assertEquals(LifecycleBase::class.java, owner(CommentFragment::class.java))
    }

    @Test fun multiple_inheritance_levels_are_walked() {
        assertEquals(LifecycleBase::class.java, owner(MiddleFragment::class.java))
    }

    @Test fun unrelated_overload_does_not_hide_inherited_signature() {
        assertEquals(LifecycleBase::class.java, owner(OverloadedFragment::class.java))
    }

    @Test fun missing_method_returns_null() {
        assertNull(owner(MissingFragment::class.java))
    }

    @Test fun null_target_returns_null() {
        assertNull(owner(null))
    }

    @Test fun parameter_order_is_exact() {
        assertNull(findMethodDeclaringClass(CommentFragment::class.java,
            "onViewCreated", String::class.java, Any::class.java))
    }

    @Test fun parent_hook_must_filter_unrelated_fragment_instances() {
        assertTrue(LifecycleBase::class.java.isInstance(OtherFragment()))
        assertFalse(CommentFragment::class.java.isInstance(OtherFragment()))
        assertTrue(CommentFragment::class.java.isInstance(CommentFragment()))
    }
}
