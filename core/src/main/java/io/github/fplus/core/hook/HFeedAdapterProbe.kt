package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.helper.DexkitBuilder
import java.lang.reflect.Modifier

/**
 * Read-only probe for the feed adapter of the target host version.
 *
 * The whole-table hook used on 40.x (`LJLILLLLZI` / `LJLJI`) does not exist on 31.7.1, so the
 * real method that receives the item list has to be discovered instead of guessed. This probe
 * only reflects over the class once at startup — it hooks nothing and modifies nothing, and it
 * will be removed once the correct entry point is confirmed.
 */
class HFeedAdapterProbe {
    companion object {
        private const val TAG = "FeedAdapterProbe"

        /** Keep each log line short so nothing gets truncated by the log driver. */
        private const val CHUNK = 8
    }

    init {
        probe()
    }

    /**
     * Kept out of the `init` block: Kotlin forbids `return` inside an initializer, and without
     * an early return the resolved class would stay nullable for the callers below.
     */
    private fun probe() {
        val clazz = DexkitBuilder.abstractFeedAdapterClazz ?: run {
            KLogCat.tagI(TAG, "probe unavailable: adapter class was not resolved by DexKit")
            return
        }

        runCatching {
            KLogCat.tagI(TAG, "probe for ${clazz.name}")
            logMethods(clazz)
            logListFields(clazz)
        }.onFailure {
            KLogCat.tagI(TAG, "probe failed: ${it.javaClass.simpleName}: ${it.message}")
        }
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
