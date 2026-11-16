// Kotlin · HCommentFeedFragment.kt · Android 7+ / Xposed · 评论图片与附带视频保存
package io.github.fplus.core.hook

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
import com.freegang.extension.asOrNull
import com.freegang.extension.dip2px
import com.freegang.ktutils.log.KLogCat
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.ugc.aweme.ImageUrlStruct
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.CommentImageState
import io.github.fplus.core.helper.CommentImageStateStore
import io.github.fplus.core.helper.CommentMedia
import io.github.fplus.core.helper.CommentMediaResolver
import io.github.fplus.core.helper.HookInstallGuard
import io.github.fplus.core.hook.logic.SaveCommentNewLogic
import io.github.fplus.core.hook.logic.animatedVideoUrlList
import io.github.fplus.drawable.selectorDrawable
import io.github.fplus.drawable.shapeDrawable
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.EmptyHook
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

class HCommentFeedFragment : BaseHook() {
    private data class BrowserBinding(var owner: WeakReference<Any>?, var state: CommentImageState)
    private data class PendingMedia(val key: String, val images: List<CommentMedia>)

    private val config get() = ConfigV1.get()
    private val states = CommentImageStateStore()
    private val browsers = WeakHashMap<ViewGroup, BrowserBinding>()
    private val page_text_pattern = Regex("^\\s*\\d+\\s*[/／].*$")

    override fun setTargetClass(): Class<*> = EmptyHook::class.java

    override fun onInit() {
        // 三个注册单独保护；可选页码类消失不会阻断图片观察和保存按钮。
        install_hook("图片观察") { install_image_observer() }
        install_hook("保存按钮") { install_save_button() }
        install_hook("页码增强") { install_page_observer() }
    }

    private fun install_hook(name: String, block: () -> Unit) {
        HookInstallGuard.install(name, { label, error ->
            KLogCat.tagI("HCommentFeedFragment", "$label 注册失败: ${error.javaClass.simpleName}")
            XplerLog.e(error)
        }, block)
    }

    private fun install_image_observer() {
        lparam.hookClass("com.ss.android.ugc.aweme.commentfeed.CommentFeedFragmentObserver")
            .methodAllByParamTypes(Aweme::class.java) {
                onAfter {
                    val aweme = args[0]?.asOrNull<Aweme>()
                    val comment = aweme?.commentFeedOuterComment
                    val raw_images: List<Any> = comment?.imageList?.takeIf { it.isNotEmpty() }
                        ?: aweme?.images ?: emptyList()
                    val images = raw_images.map { image ->
                        val video_urls = if (image is ImageUrlStruct) {
                            runCatching { image.animatedVideoUrlList() }.getOrDefault(emptyList())
                        } else CommentMediaResolver.comment_video_urls(image)
                        CommentMedia(CommentMediaResolver.image_urls(image), video_urls)
                    }
                    val comment_id = if (comment != null) {
                        (CommentMediaResolver.named_value(comment, "cid") as? String)?.takeIf { it.isNotBlank() }
                            ?: "comment@${System.identityHashCode(comment)}"
                    } else runCatching { aweme?.aid }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: "aweme@${System.identityHashCode(aweme)}"
                    val media = PendingMedia(comment_id, images)
                    val owner = find_browser_owner(thisObject)
                    if (owner != null) {
                        states.for_owner(owner).bind(media.key, media.images)
                    } else {
                        // 不把无法定位来源的事件广播给多个浏览器，避免跨实例串图。
                        val visible = browsers.keys.filter { it.isShown }
                        if (visible.size == 1) state_for_view(visible.single()).bind(media.key, media.images)
                        else KLogCat.tagI("HCommentFeedFragment", "媒体来源未定位，跳过歧义事件")
                    }
                    KLogCat.tagI("HCommentFeedFragment", "图片数=${images.size}, 已定位浏览器=${owner != null}")
                }
            }
    }

    private fun install_page_observer() {
        lparam.hookClass("com.ss.android.ugc.aweme.commentfeed.uimodule.fragment.CommentFeedTopPagerNumberPresenter")
            .methodAllByParamTypes(String::class.java) {
                onAfter {
                    val owner = find_browser_owner(thisObject) ?: return@onAfter
                    states.for_owner(owner).update_page(args[0] as? String)
                }
            }
    }

    private fun install_save_button() {
        lparam.hookClass("com.ss.android.ugc.aweme.commentfeed.uimodule.fragment.CommentFeedTopUIModule")
            .constructorAll {
                onAfter {
                    val top_view = args[0]?.asOrNull<ViewGroup>() ?: return@onAfter
                    state_for_view(top_view)
                    val params = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.CLIP_VERTICAL or Gravity.END
                        updateMargins(right = 8.dip2px(), top = 8.dip2px())
                    }
                    top_view.addView(TextView(top_view.context).apply {
                        text = "保存"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        updatePadding(left = 24.dip2px(), right = 24.dip2px(), top = 8.dip2px(), bottom = 8.dip2px())
                        background = selectorDrawable {
                            normal = shapeDrawable { corner(8f); solid("#FFEDA664") }
                            pressed = shapeDrawable { corner(8f); solid("#FFBD8B59") }
                        }
                        setOnClickListener { save_current_media(top_view, context) }
                    }, params)
                }
            }
    }

    private fun state_for_view(view: ViewGroup): CommentImageState {
        val owner = find_browser_owner(view)
        val old_binding = browsers[view]
        if (old_binding != null && (owner == null || old_binding.owner?.get() === owner)) return old_binding.state
        var state = states.for_owner(owner ?: view)
        // View 创建时尚未挂到 Fragment，允许随后把同一 View 的状态关联给它的真实 owner。
        if (owner != null && old_binding != null && old_binding.owner == null && state.image_count == 0) {
            state = old_binding.state
            states.associate(owner, state)
        }
        browsers[view] = BrowserBinding(owner?.let { WeakReference(it) }, state)
        return state
    }

    private fun save_current_media(view: ViewGroup, context: Context) {
        val state = state_for_view(view)
        val page_texts = visible_page_texts(view)
        if (state.image_count <= 1) state.update_page("1/1")
        else if (page_texts.isNotEmpty()) state.update_page(page_texts.distinct().singleOrNull())
        // 点击时优先读取此浏览器真实显示的页码；异常或越界都回到第一张。
        val media = state.current_media()
        if (media == null) {
            showToast(context, "未获取到图片信息")
            return
        }
        SaveCommentNewLogic.onSaveCommentImage(this, context, media.image_urls)
        if (config.isLivePhotoVideo && media.video_urls.isNotEmpty()) {
            SaveCommentNewLogic.onSaveCommentVideo(this, context, media.video_urls)
        }
    }

    private fun visible_page_texts(root: View): List<String> {
        val queue = ArrayDeque<View>()
        val texts = mutableListOf<String>()
        queue.add(root)
        var inspected = 0
        while (queue.isNotEmpty() && inspected++ < 256) {
            val view = queue.removeFirst()
            if (!view.isShown) continue
            if (view is TextView) {
                val text = view.text?.toString().orEmpty()
                if (page_text_pattern.matches(text)) texts += text
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
        }
        return texts
    }

    private fun owner_from_view(view: View): Any? = runCatching {
        // 从宿主类加载器查 FragmentManager，避免模块与宿主的 AndroidX 类型身份不同。
        Class.forName("androidx.fragment.app.FragmentManager", false, lparam.classLoader)
            .getMethod("findFragment", View::class.java).invoke(null, view)
    }.getOrNull()

    private fun is_fragment(value: Any): Boolean {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            if (type.name == "androidx.fragment.app.Fragment" || type.name == "android.app.Fragment") return true
            type = type.superclass
        }
        return false
    }

    private fun find_browser_owner(source: Any?): Any? {
        if (source == null) return null
        val queue = ArrayDeque<Pair<Any, Int>>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        queue.add(source to 0)
        while (queue.isNotEmpty() && visited.size < 128) {
            val (value, depth) = queue.removeFirst()
            if (!visited.add(value)) continue
            if (is_fragment(value)) return value
            if (value is View) { owner_from_view(value)?.let { return it }; continue }
            if (depth >= 3 || value is Context || value is Collection<*> || value is Map<*, *> ||
                value is CharSequence || value is Number || value is Boolean || value is Aweme) continue
            for (field in CommentMediaResolver.fields_of(value).take(64)) {
                if (field.type.isPrimitive) continue
                CommentMediaResolver.value_of(value, field)?.let { queue.add(it to depth + 1) }
            }
        }
        return null
    }
}
