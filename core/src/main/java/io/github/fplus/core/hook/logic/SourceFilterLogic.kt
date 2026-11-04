package io.github.fplus.core.hook.logic

import com.freegang.ktutils.log.KLogCat
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Filtering at the data source, before anything reaches the adapter.
 *
 * Everything here runs inside the feed response callback, so an entry removed here was
 * never part of the host's positional bookkeeping (counts, page indices, exposure cursors).
 * The adapter and the pager are never touched.
 *
 * Access path resolution is adaptive because field names differ between host versions:
 *  1. `mModel -> mData -> <list field>`
 *  2. `mModel -> <list field>`
 *  3. any host field holding a `FeedItemList` / `FollowFeedList` -> `<list field>`
 *  4. any host field that is itself a `List`
 * The first successful path is cached per presenter class; nothing is re-resolved afterwards.
 *
 * Every failure mode returns -1 and leaves the data untouched (fail open):
 * path not found, list missing, field not writable, filtering would empty the list,
 * or any exception along the way.
 *
 * Diagnostics (temporary, removed once the 31.7.1 path is confirmed):
 *  - the resolved access path is logged once per presenter class;
 *  - every callback logs one summary line (size / removed / first element), throttled to 1s;
 *  - when the resolved list holds at most one element, all list fields of the container are
 *    dumped once, which tells us immediately whether the wrong field was picked.
 */
object SourceFilterLogic {
    private const val TAG = "FeedSourceFilter"

    /** Sentinel: no usable path was found for this presenter class. */
    private val NO_PATH = Path(emptyList())

    private val pathCache = ConcurrentHashMap<Class<*>, Path>()

    /** Classes that never expose an isAd flag — avoid a getField walk per item. */
    private val noAdField: MutableSet<Class<*>> =
        Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    private val adFieldCache = ConcurrentHashMap<Class<*>, Field>()

    /** FollowFeed-like wrappers that hold the real item in a field. */
    private val wrappedFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val noWrappedField: MutableSet<Class<*>> =
        Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    /** Diagnostics: classes already described in the log. */
    private val pathLogged: MutableSet<Class<*>> =
        Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    private val dumpedContainers: MutableSet<Class<*>> =
        Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    private val failureLogged: MutableSet<Class<*>> =
        Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    private var removedTotal = 0
    private var lastLogAt = 0L

    /** One resolved access path: intermediate fields, the last one being the list. */
    private class Path(val steps: List<Field>) {
        val resolved: Boolean get() = steps.isNotEmpty()
    }

    /**
     * Removes matching entries from the list reachable from [host].
     *
     * @param host the object the callback runs on (`MethodParam.thisObject`)
     * @param wrapped whether each element wraps the real item (follow feed) or is the item itself
     * @param label log prefix, so recommend and follow callbacks can be told apart
     * @return number of removed entries, or -1 when nothing was changed
     */
    fun filter(host: Any?, wrapped: Boolean, label: String): Int {
        if (host == null) return -1

        val path = resolvePath(host, wrapped)
        if (!path.resolved) return fail(label, host, "no access path")
        logPath(host.javaClass, path)

        val holder = holderOf(host, path) ?: return fail(label, host, "holder is null")
        val listField = path.steps.last()
        val list = read(listField, holder) as? List<*> ?: return fail(label, host, "field is not a list")
        if (list.isEmpty()) {
            summarize(label, holder, list, removed = 0, wrapped = wrapped)
            return 0
        }

        val kept = ArrayList<Any?>(list.size)
        var removed = 0
        for (item in list) {
            if (isTargetItem(item, wrapped)) removed++ else kept.add(item)
        }

        if (removed == 0) {
            summarize(label, holder, list, removed = 0, wrapped = wrapped)
            return 0
        }

        // Never hand an empty list back: the host has branches for "no data" that skip notifying.
        if (kept.isEmpty()) return fail(label, host, "would empty the list (${list.size} entries)")

        if (!writeBack(holder, listField, kept)) return fail(label, host, "list field is not writable")

        removedTotal += removed
        summarize(label, holder, list, removed, wrapped)
        return removed
    }

    // ------------------------------------------------------------------ matching

    /**
     * @param wrapped true when the list holds wrappers (follow feed) whose item sits in a field
     */
    private fun isTargetItem(item: Any?, wrapped: Boolean): Boolean {
        if (item == null) return false
        if (!wrapped) return isFlagged(item)

        val inner = wrappedItemOf(item) ?: return false
        return isFlagged(inner)
    }

    /** Reads the public `isAd` flag; unreadable means "not a target", never a crash. */
    private fun isFlagged(item: Any): Boolean {
        val cls = item.javaClass
        if (noAdField.contains(cls)) return false

        val field = adFieldCache[cls] ?: runCatching {
            cls.getField("isAd").apply { isAccessible = true }
        }.getOrNull()?.also { adFieldCache[cls] = it } ?: run {
            noAdField.add(cls)
            return false
        }

        return runCatching { field.getBoolean(item) }.getOrDefault(false)
    }

    /** Follow feed wraps the payload, so look for a field whose type ends with `.Aweme`. */
    private fun wrappedItemOf(item: Any): Any? {
        val cls = item.javaClass
        if (noWrappedField.contains(cls)) return null

        val field = wrappedFieldCache[cls] ?: runCatching {
            allFields(cls).firstOrNull { f ->
                !Modifier.isStatic(f.modifiers) &&
                    (f.name == "aweme" || f.type.name.endsWith(".Aweme"))
            }?.apply { isAccessible = true }
        }.getOrNull()?.also { wrappedFieldCache[cls] = it } ?: run {
            noWrappedField.add(cls)
            return null
        }

        return runCatching { field.get(item) }.getOrNull()
    }

    // ------------------------------------------------------------------ path resolution

    private fun resolvePath(host: Any, wrapped: Boolean): Path {
        pathCache[host.javaClass]?.let { return it }

        val path = doResolve(host, wrapped) ?: NO_PATH
        pathCache[host.javaClass] = path
        return path
    }

    private fun doResolve(host: Any, wrapped: Boolean): Path? {
        // 1) mModel -> mData -> list   /   2) mModel -> list
        findField(host.javaClass, "mModel")?.let { modelField ->
            val model = read(modelField, host)
            if (model != null) {
                findField(model.javaClass, "mData")?.let { dataField ->
                    val data = read(dataField, model)
                    if (data != null) {
                        listFieldOf(data.javaClass, wrapped)?.let {
                            return Path(listOf(modelField, dataField, it))
                        }
                    }
                }
                listFieldOf(model.javaClass, wrapped)?.let {
                    return Path(listOf(modelField, it))
                }
            }
        }

        // 3) a host field holds the list container
        for (field in allFields(host.javaClass)) {
            if (Modifier.isStatic(field.modifiers)) continue
            if (List::class.java.isAssignableFrom(field.type)) continue
            val value = read(field, host) ?: continue
            if (!looksLikeContainer(value.javaClass, wrapped)) continue
            listFieldOf(value.javaClass, wrapped)?.let {
                return Path(listOf(field, it))
            }
        }

        // 4) a host field is the list itself
        listFieldOf(host.javaClass, wrapped)?.let {
            return Path(listOf(it))
        }

        return null
    }

    private fun looksLikeContainer(clazz: Class<*>, wrapped: Boolean): Boolean {
        val name = clazz.name
        return if (wrapped) {
            name.contains("FollowFeedList") || name.contains("FollowFeed")
        } else {
            name.contains("FeedItemList") || name.contains("FeedList")
        }
    }

    /**
     * Picks the list field on the container.
     * Preference: well-known names, then the one whose element type looks like an item,
     * then the only list field.
     */
    private fun listFieldOf(clazz: Class<*>, wrapped: Boolean): Field? {
        val fields = allFields(clazz).filter {
            !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
        }
        if (fields.isEmpty()) return null

        val preferredNames = if (wrapped) arrayOf("mItems", "items") else arrayOf("items", "mItems")
        fields.firstOrNull { it.name in preferredNames }?.let { return it }

        fields.firstOrNull { elementLooksLikeItem(it, wrapped) }?.let { return it }

        return fields.singleOrNull()
    }

    private fun elementLooksLikeItem(field: Field, wrapped: Boolean): Boolean {
        val argument = (field.genericType as? ParameterizedType)
            ?.actualTypeArguments?.firstOrNull() as? Class<*> ?: return false

        return if (wrapped) {
            allFields(argument).any { it.name == "aweme" || it.type.name.endsWith(".Aweme") }
        } else {
            runCatching { argument.getField("isAd") }.isSuccess
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    private fun holderOf(host: Any, path: Path): Any? {
        var current: Any = host
        for (index in 0 until path.steps.size - 1) {
            current = read(path.steps[index], current) ?: return null
        }
        return current
    }

    private fun read(field: Field, target: Any): Any? = runCatching {
        field.isAccessible = true
        field.get(target)
    }.getOrNull()

    /**
     * Replaces the list reference. If the field is final, falls back to rewriting the
     * contents of the existing mutable list — at this point the list has not been handed
     * to the adapter yet, so both forms are consistent for the host.
     */
    private fun writeBack(holder: Any, field: Field, kept: List<Any?>): Boolean {
        val replaced = runCatching {
            field.isAccessible = true
            field.set(holder, kept)
            true
        }.getOrDefault(false)
        if (replaced) return true

        return runCatching {
            val mutable = field.get(holder) as? MutableList<Any?> ?: return false
            mutable.clear()
            mutable.addAll(kept)
            true
        }.getOrDefault(false)
    }

    private fun findField(clazz: Class<*>, name: String): Field? =
        allFields(clazz).firstOrNull { it.name == name }

    private fun allFields(clazz: Class<*>): List<Field> {
        val result = ArrayList<Field>()
        var cursor: Class<*>? = clazz
        while (cursor != null && cursor != Any::class.java) {
            Collections.addAll(result, *cursor.declaredFields)
            cursor = cursor.superclass
        }
        return result
    }

    // ------------------------------------------------------------------ diagnostics

    /** Logs the resolved access path once per presenter class. */
    private fun logPath(clazz: Class<*>, path: Path) {
        if (!pathLogged.add(clazz)) return
        KLogCat.tagI(TAG, "path for ${clazz.name}: ${path.steps.joinToString(">") { it.name }}")
    }

    /** One summary line per callback (throttled), plus a container dump when the list looks wrong. */
    private fun summarize(label: String, holder: Any, list: List<*>, removed: Int, wrapped: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastLogAt >= 1000L) {
            lastLogAt = now

            val first = list.firstOrNull()
            val readable = if (first == null) {
                false
            } else {
                val target = if (wrapped) wrappedItemOf(first) else first
                target != null && runCatching { target.javaClass.getField("isAd") }.isSuccess
            }

            KLogCat.tagI(
                TAG,
                "[$label] ${list.size} entries, removed=$removed, " +
                    "first=${first?.javaClass?.simpleName ?: "null"}, flagReadable=$readable",
            )
        }

        if (list.size <= 1) dumpContainerLists(label, holder)
    }

    /** Lists every list field on the container once — exposes whether the wrong field was picked. */
    private fun dumpContainerLists(label: String, holder: Any) {
        if (!dumpedContainers.add(holder.javaClass)) return

        val lists = allFields(holder.javaClass)
            .filter { !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type) }
            .joinToString(", ") { field ->
                val size = (read(field, holder) as? List<*>)?.size ?: -1
                "${field.name}=$size"
            }

        KLogCat.tagI(
            TAG,
            "[$label] container ${holder.javaClass.simpleName} lists: ${lists.ifEmpty { "(none)" }}",
        )
    }

    private fun fail(label: String, host: Any, reason: String): Int {
        if (failureLogged.add(host.javaClass)) {
            KLogCat.tagI(TAG, "[$label] skipped ($reason), source data left untouched")
        }
        return -1
    }
}
