// Kotlin · FeedFilterSafety.kt · JVM 11 / Android · 源列表快照与异常边界
package io.github.fplus.core.hook.logic

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/** 所有筛选先构建新列表；原列表及原参数数组始终不作原地修改。 */
internal object FeedFilterSafety {
    data class Selection(val kept: List<Any?>, val removed: Int, val total: Int)
    data class ArgumentSelection(val args: Array<Any?>, val selection: Selection)

    private val flag_fields = ConcurrentHashMap<Class<*>, Field>()
    private val wrapper_fields = ConcurrentHashMap<Class<*>, Field>()

    /** 只隔离可恢复错误；反射包装中的致命错误也必须继续抛出。 */
    fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        val seen = IdentityHashMap<Throwable, Boolean>()
        var cause: Throwable? = error
        while (cause != null && seen.put(cause, true) == null) {
            if (cause is VirtualMachineError || cause is ThreadDeath) throw cause
            cause = if (cause is InvocationTargetException) cause.targetException else cause.cause
        }
        null
    }

    fun fields_of(clazz: Class<*>): List<Field> {
        val result = ArrayList<Field>()
        var cursor: Class<*>? = clazz
        while (cursor != null && cursor != Any::class.java) {
            result.addAll(cursor.declaredFields)
            cursor = cursor.superclass
        }
        return result
    }

    private fun flag_field(clazz: Class<*>): Field? {
        flag_fields[clazz]?.let { return it }
        val field = guarded {
            clazz.getField("isAd").takeIf {
                !Modifier.isStatic(it.modifiers) &&
                    (it.type == Boolean::class.javaPrimitiveType || it.type == java.lang.Boolean::class.java)
            }?.apply { isAccessible = true }
        } ?: return null
        flag_fields[clazz] = field
        return field
    }

    private fun wrapper_field(clazz: Class<*>): Field? {
        wrapper_fields[clazz]?.let { return it }
        val fields = fields_of(clazz).filter { !Modifier.isStatic(it.modifiers) }
        val named = fields.filter { it.name == "aweme" }
        val field = if (named.size == 1) {
            named.single()
        } else {
            fields.filter { it.type.name.endsWith(".Aweme") && flag_field(it.type) != null }.singleOrNull()
        } ?: return null
        field.isAccessible = true
        wrapper_fields[clazz] = field
        return field
    }

    fun has_item_shape(clazz: Class<*>, wrapped: Boolean): Boolean =
        if (wrapped) wrapper_field(clazz)?.let { flag_field(it.type) != null } == true
        else flag_field(clazz) != null

    fun has_item_shape(item: Any, wrapped: Boolean): Boolean {
        if (!wrapped) return flag_field(item.javaClass) != null
        val field = wrapper_field(item.javaClass) ?: return false
        val payload = field.get(item) ?: return flag_field(field.type) != null
        return flag_field(payload.javaClass) != null
    }

    fun is_target(item: Any?, wrapped: Boolean): Boolean {
        if (item == null) return false
        val payload = if (wrapped) {
            val field = wrapper_field(item.javaClass) ?: return false
            field.get(item) ?: return false
        } else item
        val field = flag_field(payload.javaClass) ?: return false
        return field.get(payload) == true
    }

    /** 坏迭代器、反射读取异常、全广告表均放行，不把部分结果写回。 */
    fun select(source: List<*>, wrapped: Boolean): Selection? = guarded {
        val total = source.size
        val kept = ArrayList<Any?>(total)
        var removed = 0
        for (item in source) {
            if (is_target(item, wrapped)) removed++ else kept.add(item)
        }
        if (removed > 0 && kept.isEmpty()) return@guarded null
        Selection(kept, removed, total)
    }

    /** 保留完整参数数组与附加 boolean，仅把第 0 项替换成新列表。 */
    fun select_arguments(args: Array<Any?>, wrapped: Boolean = false): ArgumentSelection? {
        val source = args.firstOrNull() as? List<*> ?: return null
        val selection = select(source, wrapped) ?: return null
        if (selection.removed == 0) return null
        val replacement = args.copyOf()
        replacement[0] = selection.kept
        return ArgumentSelection(replacement, selection)
    }
}
