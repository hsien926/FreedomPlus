package io.github.fplus.core.hook

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
import com.freegang.extension.asOrNull
import com.freegang.extension.dip2px
import com.freegang.ktutils.log.KLogCat
import com.ss.android.ugc.aweme.feed.model.Aweme
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.hook.logic.SaveCommentNewLogic
import io.github.fplus.drawable.selectorDrawable
import io.github.fplus.drawable.shapeDrawable
import io.github.xpler.core.entity.EmptyHook
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

class HCommentFeedFragment : BaseHook() {
    /** 当前这条评论的全部图片（CommentImageStruct；取不到时退回 Aweme.images），顺序与翻页一致 */
    private var imageStructs: List<Any> = emptyList()

    /** 当前看到第几张（0 基），来自 ViewPager2 的 onPageSelected */
    private var currentPage: Int = 0

    /**
     * 从 CommentImageStruct 里取出图片 URL 列表。
     *
     * 40.6.0 把该类的字段名混淆成单字母（a/b/d/e/f/i），`originUrl` 取不到；
     * 但真正承载 URL 的 `UrlModel.urlList` 字段名未被混淆，所以这里不依赖外层字段名 ——
     * 遍历该对象的全部字段，从中挑出 UrlModel 实例，取第一个 urlList 非空的。
     */
    private fun resolveImageUrlList(imageStruct: Any): List<String> {
        return imageStruct.javaClass.declaredFields
            .mapNotNull { f ->
                runCatching {
                    f.isAccessible = true
                    f.get(imageStruct)
                }.getOrNull()
            }
            .firstNotNullOfOrNull { candidate ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (candidate.javaClass.getField("urlList").get(candidate) as? List<String>)
                        ?.takeIf { it.isNotEmpty() }
                }.getOrNull()
            } ?: emptyList()
    }


    /** 要保存的那张：翻到第几页就取第几张（越界则退回第一张） */
    private fun currentImageUrlList(): List<String> {
        if (imageStructs.isEmpty()) return emptyList()
        val index = currentPage.coerceIn(0, imageStructs.size - 1)
        return resolveImageUrlList(imageStructs[index])
    }

    override fun setTargetClass(): Class<*> {
        return EmptyHook::class.java
    }

    override fun onInit() {
        // 当前是第几张：直接读抖音自己显示的页码文本「k/N」。
        //
        // 不能按方法名挂：该类里负责渲染页码的是 setText(String)（39.9.0），
        // 由 e2/f2/g2 传入拼好的 "curPosition/totalNumber"，而 curPosition 来自
        // CommentFeedViewModel.t，也就是「当前图片下标 + 1」——这正是我们要的值。
        //
        // 曾经试过挂 CommentFeedViewPagerPresenter 的页码回调（39.9.0 叫 j2，
        // 40.8.0 叫 LLLLLLL），那个值是 ViewPager2 的页序号：同一条评论内翻图时
        // 它不变，导致索引恒为 0、永远保存第一张。故改为解析页码文本。
        lparam.hookClass("com.ss.android.ugc.aweme.commentfeed.uimodule.fragment.CommentFeedTopPagerNumberPresenter")
            .methodAllByParamTypes(String::class.java) {
                onBefore {
                    val text = args[0] as? String ?: return@onBefore
                    val cur = Regex("^\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (cur != null && cur > 0) {
                        currentPage = cur - 1
                    }
                }
            }

        lparam.hookClass("com.ss.android.ugc.aweme.commentfeed.CommentFeedFragmentObserver")
            .methodAllByParamTypes(Aweme::class.java) {
                onAfter {
                    val aweme = args[0]?.asOrNull<Aweme>()
                    // 40.6.0 适配：CommentImageStruct 的 5 个图片字段（crop/download/medium/origin/thumb）
                    // 在 40.6.0 全被混淆成单字母（a/b/d/e/f/i），`originUrl` 按名取必然为 null。
                    // 这里只缓存整组图片对象，具体 URL 在保存时按当前页下标解（见 resolveImageUrlList）。
                    val commentImages = aweme?.commentFeedOuterComment?.imageList
                    imageStructs = when {
                        !commentImages.isNullOrEmpty() -> commentImages
                        else -> aweme?.images ?: emptyList()
                    }
                    KLogCat.tagI(
                        "HCommentFeedFragment",
                        "图片数=${imageStructs.size}, 当前第 ${currentPage + 1} 张",
                    )
                }
            }

        lparam.hookClass("com.ss.android.ugc.aweme.commentfeed.uimodule.fragment.CommentFeedTopUIModule")
            .constructorAll {
                onAfter {
                    val topView = args[0]?.asOrNull<ViewGroup>() ?: return@onAfter
                    val params = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.CLIP_VERTICAL or Gravity.END
                        updateMargins(
                            right = 8.dip2px(),
                            top = 8.dip2px(),
                        )
                    }
                    topView.addView(
                        TextView(topView.context).apply {
                            text = "保存"
                            textSize = 18f
                            setTextColor(Color.WHITE)
                            updatePadding(
                                left = 24.dip2px(),
                                right = 24.dip2px(),
                                top = 8.dip2px(),
                                bottom = 8.dip2px(),
                            )

                            background = selectorDrawable {
                                normal = shapeDrawable {
                                    corner(8f)
                                    solid("#FFEDA664")
                                }

                                pressed = shapeDrawable {
                                    corner(8f)
                                    solid("#FFBD8B59")
                                }
                            }

                            setOnClickListener {
                                val index = currentPage.coerceIn(0, (imageStructs.size - 1).coerceAtLeast(0))
                                KLogCat.tagI(
                                    "HCommentFeedFragment",
                                    "点击保存: 图片数=${imageStructs.size}, currentPage=$currentPage",
                                )

                                val urlList = currentImageUrlList()
                                KLogCat.tagI(
                                    "HCommentFeedFragment",
                                    "实际保存第 ${index + 1} 张, url=${urlList.firstOrNull()?.take(96)}",
                                )
                                SaveCommentNewLogic.onSaveCommentImage(
                                    this@HCommentFeedFragment,
                                    context,
                                    urlList,
                                )
                            }

                        },
                        params
                    )
                }
            }
    }
}
