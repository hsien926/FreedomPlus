// Kotlin · SourceFilterLogic.kt · JVM 11 / Android · 非破坏性响应列表过滤
package io.github.fplus.core.hook.logic

import com.freegang.ktutils.log.KLogCat
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 仅替换已确认的响应列表；不原地改写列表，不接触适配器计数或通知。 */
object SourceFilterLogic {
    private const val TAG = "FeedSourceFilter"
    private data class CacheKey(val host_class: Class<*>, val wrapped: Boolean)
    private class Path(val steps: List<Field>, val owner_classes: List<Class<*>> = emptyList()) {
        val resolved: Boolean get() = steps.isNotEmpty()
    }

    private val NO_PATH = Path(emptyList())
    private val path_cache = ConcurrentHashMap<CacheKey, Path>()
    private val path_logged = Collections.newSetFromMap(ConcurrentHashMap<CacheKey, Boolean>())
    private val failure_logged = Collections.newSetFromMap(ConcurrentHashMap<CacheKey, Boolean>())
    private val removed_total = AtomicInteger()

    /**
     * 返回剔除数；未知路径、不可替换或全广告表返回 -1，未命中返回 0。
     * 先完整读取、筛选并校验实例链，再做唯一一次字段引用替换。
     */
    fun filter(host: Any?, wrapped: Boolean, label: String): Int {
        if (host == null) return -1
        val result = FeedFilterSafety.guarded {
            val path = resolvePath(host, wrapped)
            if (!path.resolved) return@guarded fail(host, wrapped, label, "no verified access path")
            log_path(host.javaClass, wrapped, path)
            val holder = holder_of(host, path)
                ?: return@guarded fail(host, wrapped, label, "holder changed or missing")
            val field = path.steps.last()
            val source = read(field, holder) as? List<*>
                ?: return@guarded fail(host, wrapped, label, "list missing")
            val selection = FeedFilterSafety.select(source, wrapped)
                ?: return@guarded fail(host, wrapped, label, "snapshot failed or would empty source")
            if (selection.removed == 0) return@guarded 0

            // 回调读取期间若模型或列表引用换代，不能用旧快照覆盖新数据。
            if (holder_of(host, path) !== holder || read(field, holder) !== source) {
                path_cache.remove(CacheKey(host.javaClass, wrapped), path)
                return@guarded fail(host, wrapped, label, "source changed while filtering")
            }
            if (!writeBack(holder, field, selection.kept)) {
                return@guarded fail(host, wrapped, label, "list reference not safely writable")
            }
            val total = removed_total.addAndGet(selection.removed)
            log_safely("[$label] removed ${selection.removed}/${selection.total} at source (total $total)")
            selection.removed
        }
        return result ?: fail(host, wrapped, label, "reflection or iteration failed")
    }

    /** 保留签名供兼容回归调用；只有成功路径被缓存，缓存随具体实例链验证。 */
    private fun resolvePath(host: Any, wrapped: Boolean): Path {
        val key = CacheKey(host.javaClass, wrapped)
        path_cache[key]?.let { cached ->
            val valid = FeedFilterSafety.guarded { valid_path(host, cached, wrapped) } == true
            if (valid) return cached
            path_cache.remove(key, cached)
        }
        val path = FeedFilterSafety.guarded { do_resolve(host, wrapped) } ?: return NO_PATH
        if (!path.resolved) return NO_PATH
        path_cache[key] = path
        return path
    }

    private fun do_resolve(host: Any, wrapped: Boolean): Path? {
        val model_field = find_field(host.javaClass, "mModel")
        if (model_field != null) {
            // 已声明模型链但当前尚未初始化，不转而筛宿主上其它旧列表。
            val model = read(model_field, host) ?: return null
            val data_field = find_field(model.javaClass, "mData")
            if (data_field != null) {
                val data = read(data_field, model) ?: return null
                val list_field = list_field_of(data, wrapped) ?: return null
                return Path(listOf(model_field, data_field, list_field),
                    listOf(host.javaClass, model.javaClass, data.javaClass))
            }
            val list_field = list_field_of(model, wrapped) ?: return null
            return Path(listOf(model_field, list_field), listOf(host.javaClass, model.javaClass))
        }

        val candidates = ArrayList<Path>()
        for (field in FeedFilterSafety.fields_of(host.javaClass)) {
            if (Modifier.isStatic(field.modifiers) || List::class.java.isAssignableFrom(field.type)) continue
            val container = read(field, host) ?: continue
            if (!looks_like_container(container.javaClass, wrapped)) continue
            val list_field = list_field_of(container, wrapped) ?: continue
            candidates.add(Path(listOf(field, list_field), listOf(host.javaClass, container.javaClass)))
        }
        if (candidates.isNotEmpty()) return candidates.singleOrNull()
        val direct = list_field_of(host, wrapped) ?: return null
        return Path(listOf(direct), listOf(host.javaClass))
    }

    private fun valid_path(host: Any, path: Path, wrapped: Boolean): Boolean {
        val holder = holder_of(host, path) ?: return false
        // 数据类型或列表结构漂移时，重新走唯一候选验证，不能复用旧 Field。
        return list_field_of(holder, wrapped) == path.steps.last()
    }

    private fun holder_of(host: Any, path: Path): Any? {
        var current = host
        for ((index, field) in path.steps.withIndex()) {
            if (current.javaClass != path.owner_classes.getOrNull(index)) return null
            if (!field.declaringClass.isInstance(current)) return null
            if (index == path.steps.lastIndex) return current
            current = read(field, current) ?: return null
        }
        return null
    }

    /** 名称只定位容器，不作为列表身份的充分依据。 */
    private fun looks_like_container(clazz: Class<*>, wrapped: Boolean): Boolean =
        if (wrapped) clazz.name.contains("FollowFeed")
        else clazz.name.contains("FeedItemList") || clazz.name.contains("FeedList")

    /** 多个已验证列表也有歧义；不靠 items 名称或“唯一 List 字段”猜测。 */
    private fun list_field_of(holder: Any, wrapped: Boolean): Field? =
        FeedFilterSafety.fields_of(holder.javaClass).filter { field ->
            !Modifier.isStatic(field.modifiers) && List::class.java.isAssignableFrom(field.type) &&
                verified_list(field, holder, wrapped)
        }.singleOrNull()

    private fun verified_list(field: Field, holder: Any, wrapped: Boolean): Boolean {
        val list = read(field, holder) as? List<*> ?: return false
        val argument = (field.genericType as? ParameterizedType)?.actualTypeArguments?.singleOrNull()
        val element_class = as_class(argument)
        val declared_shape = element_class?.let { FeedFilterSafety.has_item_shape(it, wrapped) } == true
        // 明确声明为 String 等其它类型的 items，不能用字段名或采样巧合强行认定。
        if (element_class != null && element_class != Any::class.java && !declared_shape) return false
        var has_payload = false
        for (item in list) {
            if (item == null) continue
            if (!FeedFilterSafety.has_item_shape(item, wrapped)) return false
            has_payload = true
        }
        return declared_shape || has_payload
    }

    private fun as_class(type: Type?): Class<*>? = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as? Class<*>
        is WildcardType -> type.upperBounds.singleOrNull()?.let { as_class(it) }
        else -> null
    }

    private fun find_field(clazz: Class<*>, name: String): Field? =
        FeedFilterSafety.fields_of(clazz).firstOrNull { it.name == name && !Modifier.isStatic(it.modifiers) }

    private fun read(field: Field, holder: Any): Any? {
        field.isAccessible = true
        return field.get(holder)
    }

    /**
     * 保留兼容签名。final、静态、具体集合类型不兼容或反射失败时直接放行。
     * 不调用 clear/addAll/retainAll，也不试图写入其它平行列表。
     */
    private fun writeBack(holder: Any, field: Field, kept: List<Any?>): Boolean {
        if (Modifier.isFinal(field.modifiers) || Modifier.isStatic(field.modifiers) ||
            !field.declaringClass.isInstance(holder) || !field.type.isInstance(kept)) return false
        return FeedFilterSafety.guarded {
            field.isAccessible = true
            field.set(holder, kept)
            true
        } == true
    }

    private fun log_safely(message: String) {
        FeedFilterSafety.guarded { KLogCat.tagI(TAG, message) }
    }

    private fun log_path(clazz: Class<*>, wrapped: Boolean, path: Path) {
        if (path_logged.add(CacheKey(clazz, wrapped))) {
            log_safely("path for ${clazz.name}: ${path.steps.joinToString(">") { it.name }}")
        }
    }

    private fun fail(host: Any, wrapped: Boolean, label: String, reason: String): Int {
        if (failure_logged.add(CacheKey(host.javaClass, wrapped))) {
            log_safely("[$label] skipped ($reason), source data left untouched")
        }
        return -1
    }
}
