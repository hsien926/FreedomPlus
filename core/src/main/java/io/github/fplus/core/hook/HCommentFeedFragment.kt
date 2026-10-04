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
    private var urlList: List<String> = emptyList()

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

    override fun setTargetClass(): Class<*> {
        return EmptyHook::class.java
    }

    override fun onInit() {
        lparam.hookClass("com.ss.android.ugc.aweme.commentfeed.CommentFeedFragmentObserver")
            .methodAllByParamTypes(Aweme::class.java) {
                onAfter {
                    val aweme = args[0]?.asOrNull<Aweme>()
                    val commentFeedOuterComment = aweme?.commentFeedOuterComment
                    val imageList = commentFeedOuterComment?.imageList
                    val firstImage = imageList?.firstOrNull()
                    // 40.6.0 适配：CommentImageStruct 的 5 个图片字段（crop/download/medium/origin/thumb）
                    // 在 40.6.0 全被混淆成单字母（a/b/d/e/f/i），`originUrl` 按名取必然为 null，
                    // 导致保存时提示「未获取到图片信息」。UrlModel.urlList 未混淆，故改为
                    // 遍历该对象的所有字段、找 UrlModel 后取第一个非空的 urlList。
                    urlList = firstImage?.let { resolveImageUrlList(it) } ?: emptyList()
                    KLogCat.tagI("HCommentFeedFragment", "图片 URL 数=${urlList.size}")
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
                                SaveCommentNewLogic.onSaveCommentImage(this@HCommentFeedFragment, context, urlList)
                            }

                        },
                        params
                    )
                }
            }
    }
}