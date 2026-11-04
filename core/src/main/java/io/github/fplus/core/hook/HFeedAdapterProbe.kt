package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.proxy.MethodParam
import java.lang.reflect.Modifier

/**
 * Read-only diagnostics for the feed adapter of the target host version.
 *
 * 31.7.1 exposes the data entry points under their original names (`setData`, `replace`, `LIZ`),
 * which the 40.x hook names never matched. This probe reports what actually flows through those
 * methods so the real filtering point can be chosen with evidence:
 *
 *  - `setData`  : the whole-table path, logs list size and how many entries look targeted;
 *  - `replace`  : single-item replacement during pagination;
 *  - `LIZ`      : overloads that take a list plus position arguments.
 *
 * Nothing is modified here. Every hook only reads arguments and logs a single line, and all of
 * it is throttled; the probe is removed once the filtering point is confirmed.
 */
class HFeedAdapterProbe : BaseHook() {
    companion object {
        private const val TAG = "FeedAdapterProbe"

        /** Keep each log line short so nothing gets truncated by the log driver. */
        private const val CHUNK = 8

        private var lastSummaryAt = 0L
    }

    override fun setTargetClass(): Class<*> =
        DexkitBuilder.abstractFeedAdapterClazz ?: NoneHook::class.java

    override fun onInit() {
        runCatching {
            KLogCat.tagI(TAG, "probe for ${targetClass.name}")
            logMethods(targetClass)
            logListFields(targetClass)
        }.onFailure {
            KLogCat.tagI(TAG, "probe failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** Whole-table path: `setData(java.util.List)` and `setData(java.util.List, boolean)`. */
    @OnBefore("setData")
    fun setDataBefore(params: MethodParam) {
        val list = params.args.firstOrNull() as? List<*> ?: return
        summarize("setData", list)
    }

    /** Single-slot path used while paginating. */
    @OnBefore("replace")
    fun replaceBefore(params: MethodParam) {
        val item = params.args.firstOrNull() ?: return
        val index = params.args.getOrNull(1)
        val now = System.currentTimeMillis()
        if (now - lastSummaryAt < 1000L) return
        lastSummaryAt = now

        KLogCat.tagI(
            TAG,
            "replace item=${item.javaClass.simpleName}, flagged=${isFlagged(item)}, " +
                "raw=${hasRawAd(item)}, index=$index",
        )
    }

    /** Overloads taking a list plus position arguments; unrelated `LIZ` overloads are ignored. */
    @OnBefore("LIZ")
    fun lizBefore(params: MethodParam) {
        val list = params.args.firstOrNull { it is List<*> } as? List<*> ?: return
        val ints = params.args.filterIsInstance<Int>()
        val now = System.currentTimeMillis()
        if (now - lastSummaryAt < 1000L) return
        lastSummaryAt = now

        KLogCat.tagI(TAG, "LIZ list=${list.size}, flagged=${countFlagged(list)}, ints=$ints")
    }

    // ------------------------------------------------------------------ helpers

    private fun summarize(method: String, list: List<*>) {
        val now = System.currentTimeMillis()
        if (now - lastSummaryAt < 1000L) return
        lastSummaryAt = now

        KLogCat.tagI(
            TAG,
            "$method ${list.size} entries, flagged=${countFlagged(list)}, " +
                "raw=${countRawAd(list)}, first=${list.firstOrNull()?.javaClass?.simpleName ?: "null"}",
        )
    }

    private fun countFlagged(list: List<*>): Int = list.count { isFlagged(it) }

    /** `awemeRawAd != null` is a second signal: some ad-like entries keep the flag false. */
    private fun countRawAd(list: List<*>): Int = list.count { hasRawAd(it) }

    private fun isFlagged(item: Any?): Boolean {
        if (item == null) return false
        return runCatching { item.javaClass.getField("isAd").getBoolean(item) }.getOrDefault(false)
    }

    private fun hasRawAd(item: Any?): Boolean {
        if (item == null) return false
        return runCatching {
            item.javaClass.getField("awemeRawAd").get(item) != null
        }.getOrDefault(false)
    }

    private fun logMethods(clazz: Class<*>) {
        val methods = clazz.declaredMethods
            .sortedBy { it.name }
            .map { method ->
                val params = method.parameterTypes.joinToString(",") { it.simpleName }
                "${method.name}($params)->${method.returnType.simpleName}"
            }

        KLogCat.tagI(TAG, "${methods.size} declared methods")
        methods.chunked(CHUNK).forEachIndexed { index, chunk ->
            KLogCat.tagI(TAG, "[${index * CHUNK}] ${chunk.joinToString(", ")}")
        }
    }

    private fun logListFields(clazz: Class<*>) {
        val fields = ArrayList<String>()

        var cursor: Class<*>? = clazz
        while (cursor != null && cursor != Any::class.java) {
            for (field in cursor.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!List::class.java.isAssignableFrom(field.type)) continue
                fields.add("${field.name}:${field.type.simpleName}")
            }
            cursor = cursor.superclass
        }

        KLogCat.tagI(TAG, "list fields: ${fields.joinToString(", ").ifEmpty { "(none)" }}")
    }
}
