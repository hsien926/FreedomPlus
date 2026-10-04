package io.github.fplus.core.hook

import android.view.View
import android.view.ViewGroup
import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.proxy.MethodParam

/**
 * 运行时 View 树探针（诊断用）
 *
 * ## 为什么要它
 * `bottomCtrlBar` 的 DexKit matcher 在 40.6.0 无法定位，且不是"改个特征就能修"：
 *   - 它依赖的注解字符串 `IPauseCtrlAction` 在 **38.7.0 与 40.6.0 都不存在**（原 matcher 照搬了更早版本）
 *   - 40.6.0 里 `dalvik.annotation.Signature` 注解实例数为 **0**，"字段注解匹配"这条路径整体是死的
 *   - 兜底也不收敛：`LX/` 包下 superclass=FrameLayout 的类有 **863 个**，叠加 Progress/SeekBar 字段后
 *     命中的都是 POI 详情页、电商锚点、下载、loading 等无关控件
 *   - 且它已从"容器 View 持有"改为 **Presenter 驱动**（`LineProgressBar` 的持有者变成
 *     `FeedBottomProgressPresenter`），原"移除整个控制栏 ViewGroup"的模型本身不再匹配
 *
 * ## 做法
 * 静态定位不了就运行时看。挂在已验证的主 feed 入口 `VideoViewHolder.bind` 上，拿到该
 * ViewHolder 的根 View 后**延迟一帧**（等布局完成）打印整棵 View 树的类名/层级/可见性，
 * 据此再写确定性 matcher 或直接按类名钩。
 *
 * 默认关闭；需要排查时把 [enabled] 置 true，抓一次日志即可（[dumped] 保证只打印一次）。
 */
class HViewTreeDump : BaseHook() {
    companion object {
        private const val TAG = "HViewTreeDump"

        /**
         * 诊断开关，默认关闭（定位 bottomCtrlBar 已完成，见类注释结论）。
         */
        @Volatile
        var enabled: Boolean = false

        /** 最大打印深度 */
        private const val MAX_DEPTH = 16

        private var dumped = false

        /** 首次 bind 日志（区分"bind 没被调用"与"字段取不到"） */
        private var firstCallLogged = false
    }

    override fun setTargetClass(): Class<*> =
        DexkitBuilder.videoViewHolderClazz ?: NoneHook::class.java

    override fun onInit() {
        KLogCat.tagI(TAG, "View 树探针已注册, target=${targetClass.name}, enabled=$enabled")
    }

    @OnAfter("bind")
    fun bindAfter(params: MethodParam) {
        if (!enabled) return
        val holder = params.thisObject ?: return

        if (!firstCallLogged) {
            firstCallLogged = true
            KLogCat.tagI(TAG, "bind 首次触发, holder=${holder.javaClass.name}")
        }
        if (dumped) return

        // 关键：必须遍历**整个继承链**的字段。`declaredFields` 只给本类自己的字段，
        // 而 VideoViewHolder 的 View 字段多在父类（BaseFeedVideoViewHolder / BaseFeedViewHolder）上——
        // 上一轮探针无输出就是踩了这个坑（与 xpler 只查 declaredMethods 是同一类陷阱）。
        val views = allFields(holder.javaClass).mapNotNull { f ->
            runCatching {
                f.isAccessible = true
                (f.get(holder) as? View)?.let { f.name to it }
            }.getOrNull()
        }

        KLogCat.tagI(
            TAG,
            "继承链字段总数=${allFields(holder.javaClass).size}, 其中 View 型=${views.size}",
        )
        if (views.isEmpty()) return   // 不设 dumped，留待后续 bind 重试

        dumped = true
        val anchor = views.first().second
        // 延迟 800ms：bind 当下 ViewHolder 的子树还没填充完（实测只有 3 层骨架），
        // 底部控制栏这类子视图是随后才 inflate 进来的。
        anchor.postDelayed({
            views.take(8).forEachIndexed { i, (name, v) ->
                val children = (v as? ViewGroup)?.childCount ?: -1
                KLogCat.tagI(TAG, "===== [$i] 字段 $name = ${v.javaClass.name} (children=$children) =====")
                dump(v, 0)
            }
        }, 800L)
    }

    /** 收集整个继承链上的字段（子类 → 父类） */
    private fun allFields(clazz: Class<*>): List<java.lang.reflect.Field> {
        val out = mutableListOf<java.lang.reflect.Field>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            out.addAll(c.declaredFields)
            c = c.superclass
        }
        return out
    }

    /**
     * 取 ViewHolder 的根 View。
     * 优先挑「子 View 数量最多的 View 型字段」—— 视频项的根容器必然是 ViewHolder 持有
     * 且子节点最多的那个；`getPlayViewContainer()` 只覆盖播放区，看不到底部控制栏。
     */
    private fun findAnchorView(holder: Any): View? {
        val views = runCatching {
            holder.javaClass.declaredFields.mapNotNull { f ->
                f.isAccessible = true
                f.get(holder) as? View
            }
        }.getOrDefault(emptyList())

        // 子节点最多的优先；都没有子节点时退回第一个
        views.maxByOrNull { (it as? ViewGroup)?.childCount ?: 0 }?.let { return it }

        // 兜底：用播放容器
        return runCatching {
            holder.javaClass.getMethod("getPlayViewContainer").invoke(holder)
        }.getOrNull() as? View
    }

    private fun dump(view: View, depth: Int) {
        if (depth > MAX_DEPTH) return
        val indent = "  ".repeat(depth)
        val extra = buildString {
            append(" vis=").append(view.visibility)
            append(" ").append(view.width).append("x").append(view.height)
            if (view.id != View.NO_ID) append(" id=").append(view.id)
        }
        KLogCat.tagI(TAG, "$indent${view.javaClass.name}$extra")

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dump(view.getChildAt(i), depth + 1)
            }
        }
    }
}
