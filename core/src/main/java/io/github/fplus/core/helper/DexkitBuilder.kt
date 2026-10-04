package io.github.fplus.core.helper

import android.app.Application
import com.freegang.extension.appLastUpdateTime
import com.freegang.extension.appVersionCode
import com.freegang.extension.appVersionName
import com.freegang.extension.getIntOrDefault
import com.freegang.extension.getJSONArrayOrDefault
import com.freegang.extension.getLongOrDefault
import com.freegang.extension.getStringOrDefault
import com.freegang.ktutils.log.KLogCat
import com.freegang.ktutils.text.KTextUtils
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.findClass
import io.github.xpler.core.findMethod
import io.github.xpler.core.lparam
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.ClassDataList
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.result.MethodDataList
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object DexkitBuilder {
    const val TAG = "DexkitBuilder"
    private var app: Application? = null

    private var cacheVersion: Int = 0
    private var cacheJson: JSONObject = JSONObject()
    private var classCacheJson: JSONObject = JSONObject()
    private var methodsCacheJson: JSONObject = JSONObject()

    // class
    var sideBarNestedScrollViewClazz: Class<*>? = null
    var cornerExtensionsPopupWindowClazz: Class<*>? = null
    var mainBottomTabViewClazz: Class<*>? = null
    var mainBottomPhotoTabClazz: Class<*>? = null
    var commentListPageFragmentClazz: Class<*>? = null
    var commentColorModeViewModeClazz: Class<*>? = null
    var conversationFragmentClazz: Class<*>? = null
    var seekBarSpeedModeBottomContainerClazz: Class<*>? = null
    var abstractFeedAdapterClazz: Class<*>? = null
    var recommendFeedFetchPresenterClazz: Class<*>? = null
    var fullFeedFollowFetchPresenterClazz: Class<*>? = null
    var detailPageFragmentClazz: Class<*>? = null
    var emojiPopupWindowClazz: Class<*>? = null
    var bottomCtrlBarClazz: Class<*>? = null
    var chatListRecyclerViewAdapterClazz: Class<*>? = null
    var chatListRecyclerViewAdapterNewClazz: Class<*>? = null
    var chatListRecalledHintClazz: Class<*>? = null
    var restartUtilsClazz: Class<*>? = null
    var longPressEventClazz: Class<*>? = null
    var doubleClickEventClazz: Class<*>? = null
    var autoPlayControllerClazz: Class<*>? = null

    var videoViewHolderClazz: Class<*>? = null

    var feedAvatarPresenterClazz: Class<*>? = null
    var livePhotoClazz: Class<*>? = null
    var tabLandingClazz: Class<*>? = null

    /**
     * 只是为了解决出现的各种稀奇古怪的情况。
     * 部分未作混淆的类在 Dexkit 搜索期间, 会跳过构造方法的勾子(勾不住构造方法), 但是它的普通方法却能勾住。
     * 还是没太想通, 而按照作者`韵の祈`的说法, 应该是搜索期间导致错过了构造方法勾子的时机。
     *
     * @param app 被搜索app的Application
     * @param version 类缓存版本号, 更改会触发更新
     * @param searchBefore 在Dexkit搜索之前做些什么, 建议在这里勾住`未混淆`的类
     * @param searchAfter 在Dexkit搜索之前做些什么, 建议在这里勾住`混淆`的类
     */
    fun running(
        app: Application,
        version: Int,
        searchBefore: Runnable,
        searchAfter: Runnable,
    ) {
        searchBefore.run()
        readCacheOrStartSearch(app, version)
        searchAfter.run()
    }

    /**
     * 如果缓存存在则读取缓存, 否则开启搜索并保存
     * @param app 被搜索app的Application
     * @param version 类缓存版本号, 更改会触发更新
     */
    private fun readCacheOrStartSearch(app: Application, version: Int) {
        this.app = app
        this.cacheVersion = version

        KLogCat.tagI(TAG, "当前进程: ${lparam.processName}")
        if (readCache()) {
            KLogCat.tagI(TAG, "缓存读取成功!")
            return
        }
        startSearch()
        saveCache()
    }

    /**
     * Dexkit开始搜索
     */
    private fun startSearch() {
        KLogCat.tagI(TAG, "Dexkit开始搜索: ${lparam.appInfo.sourceDir}")
        // System.loadLibrary("dexkit")
        DexKitBridge.create(lparam.appInfo.sourceDir).use { bridge ->
            // 40.6.0 起该类被移入 panel.ui 子包（旧版本在同级 sidebar 包），故两条路径都试
            val sideBarNestedScrollView = bridge.findClass {
                matcher {
                    className = "com.ss.android.ugc.aweme.sidebar.panel.ui.SideBarNestedScrollView"
                }
            }.let { found ->
                if (found.singleOrNull() != null) found
                else bridge.findClass {
                    matcher {
                        className = "com.ss.android.ugc.aweme.sidebar.SideBarNestedScrollView"
                    }
                }
            }
            sideBarNestedScrollViewClazz = sideBarNestedScrollView.instance("sideBarNestedScrollView")

            val cornerExtensionsPopupWindow = bridge.findClass {
                matcher {
                    superClass = "android.widget.PopupWindow"
                    fields {
                        add {
                            type = "android.view.LayoutInflater"
                        }
                        add {
                            type = "android.app.Dialog"
                        }
                    }
                    methods {
                        add {
                            paramTypes = listOf("android.widget.PopupWindow")
                        }
                        add {
                            paramTypes = listOf("boolean")
                        }
                        add {
                            returnType = "android.view.View"
                        }
                        add {
                            name = "dismiss"
                        }
                    }
                }
            }
            cornerExtensionsPopupWindowClazz = cornerExtensionsPopupWindow.instance("coenerExtendsionsPoupWindow")

            val mainBottomPhotoTab = bridge.findClass {
                matcher {
                    methods {
                        add {
                            name = "getNowImageRes"
                        }
                        add {
                            name = "getOperator"
                        }
                        add {
                            name = "getRefreshTab"
                            returnType = "android.view.View"
                        }
                    }
                }
            }
            mainBottomPhotoTabClazz = mainBottomPhotoTab.instance("mainBottomPhotoTab")

            // 40.6.0 适配：去掉已移除的 VideoCommentPageParam 字段
            // （该版本 com/.../comment/param/ 包只剩 SocialDialogViewModel），其余特征保留。
            // 类本身未混淆也未迁移，直接用类名匹配最稳。
            val commentListPageFragment = bridge.findClass {
                matcher {
                    className = "com.ss.android.ugc.aweme.comment.ui.CommentListPageFragment"
                    superClass = "com.ss.android.ugc.aweme.comment.arch.LifecycleDispatchFragment"

                    fields {
                        add {
                            type = "com.ss.android.ugc.aweme.comment.widget.CommentNestedLayout"
                        }
                    }

                    usingStrings = listOf("CommentListPageFragment")
                }
            }
            commentListPageFragmentClazz = commentListPageFragment.instance("commentListPageFragment")

            // 40.6.0 适配：CommentColorMode 不是被移除，而是**被混淆成 X/0dQI**（枚举，
            // 常量 MODE_LIGHT / MODE_DARK / MODE_LIGHT_OR_DARK）。按原类名匹配必然失败，
            // 改为直接用未混淆的 ViewModel 类名定位。
            val commentColorModeViewMode = bridge.findClass {
                matcher {
                    className = "com.ss.android.ugc.aweme.comment.viewmodel.CommentColorViewModel"
                    superClass = "androidx.lifecycle.ViewModel"
                    usingStrings = listOf("CommentColorViewModel")
                }
            }
            commentColorModeViewModeClazz = commentColorModeViewMode.instance("commentColorModeViewMode")

            // 40.6.0 适配：旧的 ConversationFragment / CommentConversationLayout / 
            // com/.../conversation 包在整树 0 命中，回复页已迁到 replytree/replylist/，
            // 且被 AB 开关分流为「双实现」，这里两个都试，取先命中的那个。
            val conversationFragment = run {
                val candidates = listOf(
                    "com.ss.android.ugc.aweme.replytree.replylist.ReplyTreeListFragment",
                    "com.ss.android.ugc.aweme.replytree.replylist.KmpReplyTreeListFragment",
                )
                var found = bridge.findClass {
                    matcher {
                        className = candidates[0]
                    }
                }
                if (found.singleOrNull() == null) {
                    found = bridge.findClass {
                        matcher {
                            className = candidates[1]
                        }
                    }
                }
                found
            }
            conversationFragmentClazz = conversationFragment.instance("conversationFragment")

            val abstractFeedAdapter = bridge.findClass {
                matcher {
                    fields {
                        add {
                            type = "android.view.LayoutInflater"
                        }
                        add {
                            type = "com.ss.android.ugc.aweme.feed.model.BaseFeedPageParams"
                        }
                    }

                    methods {
                        add {
                            name = "getItemPosition"
                        }
                        add {
                            name = "finishUpdate"
                        }
                    }

                    usingStrings {
                        add("AbstractFeedAdapter aweme.aid = ")
                    }
                }
            }
            abstractFeedAdapterClazz = abstractFeedAdapter.instance("abstractFeedAdapter")

            val recommendFeedFetchPresenter = bridge.findClass {
                matcher {
                    methods {
                        add {
                            name = "onSuccess"
                        }
                    }
                    addUsingString("com.ss.android.ugc.aweme.feed.presenter.RecommendFeedFetchPresenter")
                    addUsingString("enter_from")
                    addUsingString("homepage_hot")
                }
            }
            recommendFeedFetchPresenterClazz =
                recommendFeedFetchPresenter.instance("recommendFeedFetchPresenter")

            val fullFeedFollowFetchPresenter = bridge.findClass {
                matcher {
                    methods {
                        add {
                            name = "onSuccess"
                        }
                    }
                    addUsingString("com.ss.android.ugc.aweme.feed.presenter.FullFeedFollowFetchPresenter")
                    addUsingString("enter_from")
                    addUsingString("homepage_follow")
                }
            }
            fullFeedFollowFetchPresenterClazz =
                fullFeedFollowFetchPresenter.instance("fullFeedFollowFetchPresenter")

            // 40.6.0 适配：原条件全失效 —— 5 个方法里要求的修饰符是 PRIVATE，而 40.6.0 里
            // 实际是 public final；且该类已不再引用 RemoteImageView（弹窗大图改用 SimpleDraweeView）。
            // 目标类为 X/0yoc（classes30），日志 tag "BigEmojiPopHelper" 在全部 55 个 dex 中
            // 仅被 2 处引用（自身 + 内联 lambda），是唯一稳定锚点。
            val emojiPopupWindow = bridge.findClass {
                searchPackages("X")
                matcher {
                    methods {
                        add {
                            paramTypes = listOf(
                                "com.ss.android.ugc.aweme.emoji.base.BaseEmoji",
                                "com.ss.android.ugc.aweme.emoji.emojichoose.EmojiChooseParams",
                                "com.bytedance.ies.dmt.ui.widget.DmtTextView",
                            )
                        }
                        add {
                            returnType = "com.ss.android.ugc.aweme.emoji.views.EmojiPopWindow"
                        }
                        add {
                            returnType = "com.bytedance.ies.dmt.ui.widget.DmtTextView"
                        }
                        add {
                            paramTypes = listOf("android.content.Context")
                        }
                    }
                    usingStrings {
                        add("BigEmojiPopHelper")
                    }
                }
            }
            emojiPopupWindowClazz = emojiPopupWindow.instance("emojiPopupWindow")

            val seekBarSpeedModeBottomContainer = bridge.findClass {
                // findFirst = true
                matcher {
                    methods {
                        add {
                            name = "getMSpeedText"
                            returnType = "android.widget.TextView"
                        }
                        add {
                            name = "getMBottomLayout"
                            returnType = "android.view.View"
                        }
                        add {
                            name = "getLoadingProgressBar"
                            returnType = "com.ss.android.ugc.aweme.feed.widget.LineProgressBar"
                        }
                    }
                }
            }
            seekBarSpeedModeBottomContainerClazz =
                seekBarSpeedModeBottomContainer.instance("seekBarSpeedModeBottomContainer")

            // 40.6.0 适配：目标类为 X/1Br3（classes37，super=FrameLayout）。
            // 原条件已全失效：getBottomColor 在 40.6.0 全库 0 命中；三条 usingStrings 的字面量
            // 虽仍在 dex 字符串池里，但**没有任何类的字节码引用它们**（DexKit 的 usingStrings
            // 匹配的是「类内引用」，不是「池中存在」）。
            // 改用 40.6.0 新增的未混淆 getter/setter 组合作为锚点。
            val mainBottomTabView = bridge.findClass {
                matcher {
                    superClass = "android.widget.FrameLayout"

                    fields {
                        add {
                            type = "com.bytedance.dux.image.DuxImageView"
                        }
                    }

                    methods {
                        add {
                            name = "setMaskAlpha"
                        }
                        add {
                            name = "setMaskVisibility"
                        }
                        add {
                            name = "getMaskAlpha"
                        }
                    }
                }
            }
            mainBottomTabViewClazz = mainBottomTabView.instance("mainBottomTabView")

            val bottomCtrlBar = bridge.findClass {
                searchPackages("X")
                matcher {
                    superClass = "android.widget.FrameLayout"
                    fields {
                        add {
                            annotations {
                                add {
                                    type = "dalvik.annotation.Signature"
                                    addElement {
                                        name = "value"
                                        arrayValue {
                                            addString("IPauseCtrlAction")
                                        }
                                    }
                                }
                            }
                        }
                    }

                }
            }
            bottomCtrlBarClazz = bottomCtrlBar.instance("bottomCtrlBar")

            // 40.6.0 适配：**故意保持原特征不动**。
            // 旧特征（RecyclerView$ItemAnimator 字段、RecyclerView 字段）在 40.6.0 已不存在
            // （RecyclerView 字段上移到父类 BaseHiddenSupportAdapter，ItemAnimator 只出现在方法体里），
            // 因此本 matcher 会返回 null。
            // 而 chatListRecyclerViewAdapterNew 已能命中同一目标类（CellComposeAdapter）——
            // 两者 hook 逻辑等价，若同时命中会重复 hook、导致 itemView 被插入两个 TextView。
            // 故保留旧特征让它自然失败，由 New 版承担。
            val chatListRecyclerViewAdapter = bridge.findClass {
                // searchPackages("X")
                matcher {
                    fields {
                        add {
                            type = "com.ss.android.ugc.aweme.im.sdk.chat.SessionInfo"
                        }
                        add {
                            type = "androidx.recyclerview.widget.RecyclerView"
                        }
                        add {
                            type = "androidx.recyclerview.widget.RecyclerView\$ItemAnimator"
                        }
                        add {
                            type = "java.util.Set"
                        }
                        add {
                            type = "java.util.Set"
                        }
                    }

                    methods {
                        add {
                            name = "onBindViewHolder"
                            paramTypes = listOf(
                                "androidx.recyclerview.widget.RecyclerView\$ViewHolder",
                                "int",
                                "java.util.List",
                            )
                        }
                    }
                }
            }
            chatListRecyclerViewAdapterClazz = chatListRecyclerViewAdapter.instance("chatListRecyclerViewAdapter")

            val chatListRecyclerViewAdapterNew = bridge.findClass {
                // searchPackages("X")
                matcher {
                    addField {
                        type = "com.ss.android.ugc.aweme.im.sdk.chat.SessionInfo"
                    }

                    // 40.6.0 适配：该方法的返回类型变成混淆类 LX/1Mas（不再是 InjectionAware），
                    // 但方法名未混淆 —— 改按名字匹配即可恢复。
                    addMethod {
                        name = "getInjectionAware"
                    }

                    addMethod {
                        name = "getItemId"
                    }

                    addMethod {
                        name = "getItemCount"
                    }

                    addMethod {
                        name = "onCreateViewHolder"
                    }
                }
            }
            chatListRecyclerViewAdapterNewClazz = chatListRecyclerViewAdapterNew.instance("chatListRecyclerViewAdapterNew")

            // 40.6.0 适配：目标类为 RecallCellUI（classes25，撤回消息 cell）。
            // 两条原特征已整体消失：(a) getFastEventBusSubscriberClass 被移除；
            // (b) InterceptTouchLinearLayout 从字段退化为局部变量（smali 里是 instance-of/check-cast）。
            // 改用 superClass(SystemCellUI) + TextView 字段 + ViewModel 子类字段 + (?,int,List) 方法重建；
            // superClass 同时用于排除另一子类 XPlanOeRecallCellUI（它没有这两个字段）。
            val chatListRecalledHint = bridge.findClass {
                matcher {
                    superClass = "com.ss.android.ugc.aweme.im.business.chat.msgcell.common.content.onlymigration.system.SystemCellUI"

                    fields {
                        add {
                            type = "android.widget.TextView"
                        }

                        add {
                            type {
                                superClass = "androidx.lifecycle.ViewModel"
                            }
                        }
                    }

                    methods {
                        add {
                            paramTypes = listOf(
                                null,
                                "int",
                                "java.util.List"
                            )
                        }
                    }
                }
            }
            chatListRecalledHintClazz = chatListRecalledHint.instance("chatListRecalledHint")

            val restartUtils = bridge.findClass {
                searchPackages("X")
                matcher {
                    methods {
                        add {
                            paramTypes = listOf("android.content.Context")
                            usingNumbers = listOf(0x10008000)
                        }
                    }
                    usingStrings {
                        add("System.exit returned normally, while it was supposed to halt JVM.")
                    }
                }
            }
            restartUtilsClazz = restartUtils.instance("restartUtils")

            // 40.6.0 适配：原条件匹配的是接口声明上的 @EnclosingClass 注解（旧版内部接口带此注解）。
            // 40.6.0 把 LongPressLayout 的内部监听接口提为顶层类 X/0w4F（仅 8 行、无任何注解），
            // 注解条件永不成立。改用「实现 X/0w4F + Aweme 字段 + Context 字段」定位 ——
            // 全库唯一命中 X/0UWT（旧版对应 X/0YRV，字段结构一致）。
            val longPressEvent = bridge.findClass {
                matcher {
                    interfaces {
                        add { className = "X.0w4F" }
                    }

                    addField {
                        type = "com.ss.android.ugc.aweme.feed.model.Aweme"
                    }
                    addField {
                        type = "android.content.Context"
                    }
                }
            }
            longPressEventClazz = longPressEvent.instance("longPressEvent")

            // 40.6.0 无法恢复：已穷举证明全库不存在 (View, MotionEvent, MotionEvent, MotionEvent)，
            // 也不存在 (View, MotionEvent, MotionEvent)。三连 MotionEvent 只出现在 classes28 的 6 个类
            // （AbsPressTapPresenter / SearchVideoView / LinearLayoutForSlidesPhotos / SearchTouchActionView
            // / X/0uR4 / ATListenerS…），均与双击监听无关。
            // 保持原条件使其自然失败；双击拦截改由 HLongPressLayout 的 onTouchEvent hook 承担
            // （那里模块本就在统计 numberOfTaps / lastTapTimeMs）。
            val doubleClickEvent = bridge.findClass {
                matcher {
                    fieldCount(1)
                    methods {
                        add {
                            paramTypes = listOf("boolean")
                        }
                        add {
                            paramTypes = listOf(
                                "android.view.View",
                                "android.view.MotionEvent",
                                "android.view.MotionEvent",
                                "android.view.MotionEvent",
                            )
                        }
                    }
                }
            }
            doubleClickEventClazz = doubleClickEvent.instance("doubleClickEvent")

            // 40.6.0 适配：字符串 "normal" 在目标类的字符串表中已消失（旧版有），
            // 这是原 matcher 唯一失效原因。去掉后仍可唯一定位到
            // com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayViewModel
            // （该类含 7 个 QLiveData 字段 + qA() 返回 QLiveData，且体内引用 auto_play_key / swipe）。
            val autoPlayController = bridge.findClass {
                matcher {
                    fields {
                        add {
                            type = "com.ss.android.ugc.aweme.kiwi.viewmodel.QLiveData"
                        }
                    }

                    methods {
                        add {
                            returnType = "com.ss.android.ugc.aweme.kiwi.viewmodel.QLiveData"
                        }
                    }

                    usingStrings {
                        add("swipe")
                        add("auto_play_key")
                    }
                }
            }
            autoPlayControllerClazz = autoPlayController.instance("autoPlayController")

            val videoViewHolder = bridge.findClass {
                matcher {
                    className = "com.ss.android.ugc.aweme.feed.adapter.VideoViewHolder"
                }
            }
            videoViewHolderClazz = videoViewHolder.instance("videoViewHolder")

            val livePhoto = bridge.findClass {
                matcher {
                    fields {
                        add {
                            type = "com.ss.android.ugc.aweme.feed.model.VideoItemParams"
                        }
                        add {
                            type = "com.bytedance.ies.dmt.ui.widget.DmtTextView"
                        }
                        add {
                            type = "android.widget.ImageView"
                        }
                    }
                    methods {
                        add {
                            paramTypes = listOf("com.ss.android.ugc.aweme.kiwi.model.QModel")
                        }
                        add {
                            paramTypes = listOf("com.ss.android.ugc.aweme.feed.model.Aweme")
                        }
                    }
                }
            }
            livePhotoClazz = livePhoto.instance("livePhoto")

            // 40.6.0 适配：类型 TabLandGuideTriggerEventType 已删除 → 改 TabLandGuideTriggerEventConsumer；
            // 方法签名 VideoItemParams → QModel（该类 22 个方法无一接收 VideoItemParams）；
            // 字符串 tabLandingActionBtn 全库不存在 → 改用独占锚点 TabLandGuideModule
            // 与 "showTabLandGuideLanding "（注意尾随空格）。
            val tabLanding = bridge.findClass {
                matcher {
                    fields {
                        add {
                            type =
                                "com.ss.android.ugc.aweme.feed.plato.business.mainarchitecture.tablandguide.ability.TabLandGuideTriggerEventConsumer"
                        }
                        add {
                            type = "com.bytedance.dux.image.DuxImageView"
                        }

                        add {
                            type = "com.ss.android.ugc.aweme.feed.model.VideoItemParams"
                        }
                    }
                    methods {
                        add {
                            paramTypes = listOf("com.ss.android.ugc.aweme.kiwi.model.QModel")
                        }
                    }
                    usingStrings = listOf("TabLandGuideModule", "showTabLandGuideLanding ")
                }
            }
            tabLandingClazz = tabLanding.instance("tabLanding")


            //
            // by using string
            val findMaps = bridge.batchFindClassUsingStrings {
                // 40.6.0 适配：原后两串已漂移（类名串作为常量已失效；DetailActOtherNitaView 只在别的类）。
                // 改用 a1128.b7947 + a1128.b7947.c398285.d0 —— 后者全库仅 DetailPageFragment 引用，AND 后唯一。
                addSearchGroup {
                    groupName = "detailPageFragment"
                    usingStrings = listOf(
                        "a1128.b7947",
                        "a1128.b7947.c398285.d0",
                    )
                }

                // 40.6.0 适配：原四串全都不能当锚点 —— 类名串作为常量已失效（只剩 L 型描述符）；
                // 「当前无网络，暂不可用」被至少 7 个类共用（且 smali 里是 \uXXXX 转义）；
                // 「follow」泛滥；「click_hea」根本不存在（只有 click_head，这是原 matcher 一直失败的隐患）。
                // 改用两个独占串的 AND 交集。
                addSearchGroup {
                    groupName = "feedAvatarPresenter"
                    usingStrings = listOf(
                        "loadAvatarViews:",
                        "jx_feed_avatar_show_live_opt",
                    )
                }
            }

            val detailPageFragment = findMaps["detailPageFragment"]
            detailPageFragmentClazz = detailPageFragment.instance("detailPageFragment")

            val feedAvatarPresenter = findMaps["feedAvatarPresenter"]
            feedAvatarPresenterClazz = feedAvatarPresenter.instance("feedAvatarPresenter")
        }
    }

    /**
     * 读取缓存
     */
    private fun readCache(): Boolean {
        val cache = ConfigV1.get().dexkitCache

        // version
        val version = cache.getIntOrDefault("version")
        val appVersionName = cache.getStringOrDefault("appVersionName")
        val appVersionCode = cache.getLongOrDefault("appVersionCode", 0)
        val appLastUpdateTime = cache.getLongOrDefault("appLastUpdateTime")

        if (appVersionName != app!!.appVersionName) {
            return false
        }

        if (appVersionCode != app!!.appVersionCode) {
            return false
        }

        if (appLastUpdateTime != app!!.appLastUpdateTime) {
            return false
        }

        if (version < cacheVersion) {
            return false
        }

        readClassCache(cache)

        KLogCat.tagI(TAG, cache.toString(2))

        return true
    }

    /**
     * 从缓存中读取类
     */
    private fun readClassCache(cache: JSONObject) {
        val classCache = cache.getJSONObject("class")

        //
        sideBarNestedScrollViewClazz = classCache.getStringOrDefault("sideBarNestedScrollView").loadOrFindClass()
        cornerExtensionsPopupWindowClazz = classCache.getStringOrDefault("coenerExtendsionsPoupWindow").loadOrFindClass()
        mainBottomTabViewClazz = classCache.getStringOrDefault("mainBottomTabView").loadOrFindClass()
        mainBottomPhotoTabClazz = classCache.getStringOrDefault("mainBottomPhotoTab").loadOrFindClass()
        commentListPageFragmentClazz = classCache.getStringOrDefault("commentListPageFragment").loadOrFindClass()
        commentColorModeViewModeClazz = classCache.getStringOrDefault("commentColorModeViewMode").loadOrFindClass()
        conversationFragmentClazz = classCache.getStringOrDefault("conversationFragment").loadOrFindClass()
        seekBarSpeedModeBottomContainerClazz = classCache.getStringOrDefault("seekBarSpeedModeBottomContainer").loadOrFindClass()
        abstractFeedAdapterClazz = classCache.getStringOrDefault("abstractFeedAdapter").loadOrFindClass()
        recommendFeedFetchPresenterClazz = classCache.getStringOrDefault("recommendFeedFetchPresenter").loadOrFindClass()
        fullFeedFollowFetchPresenterClazz = classCache.getStringOrDefault("fullFeedFollowFetchPresenter").loadOrFindClass()
        emojiPopupWindowClazz = classCache.getStringOrDefault("emojiPopupWindow").loadOrFindClass()
        detailPageFragmentClazz = classCache.getStringOrDefault("detailPageFragment").loadOrFindClass()
        bottomCtrlBarClazz = classCache.getStringOrDefault("bottomCtrlBar").loadOrFindClass()
        chatListRecyclerViewAdapterClazz = classCache.getStringOrDefault("chatListRecyclerViewAdapter").loadOrFindClass()
        chatListRecyclerViewAdapterNewClazz = classCache.getStringOrDefault("chatListRecyclerViewAdapterNew").loadOrFindClass()
        chatListRecalledHintClazz = classCache.getStringOrDefault("chatListRecalledHint").loadOrFindClass()
        restartUtilsClazz = classCache.getStringOrDefault("restartUtils").loadOrFindClass()
        longPressEventClazz = classCache.getStringOrDefault("longPressEvent").loadOrFindClass()
        doubleClickEventClazz = classCache.getStringOrDefault("doubleClickEvent").loadOrFindClass()
        videoViewHolderClazz = classCache.getStringOrDefault("videoViewHolder").loadOrFindClass()
        autoPlayControllerClazz = classCache.getStringOrDefault("autoPlayController").loadOrFindClass()
        livePhotoClazz = classCache.getStringOrDefault("livePhoto").loadOrFindClass()
        tabLandingClazz = classCache.getStringOrDefault("tabLanding").loadOrFindClass()
        feedAvatarPresenterClazz = classCache.getStringOrDefault("feedAvatarPresenter").loadOrFindClass()
    }

    /**
     * 保存缓存
     */
    private fun saveCache() {
        // version
        cacheJson.put("version", "$cacheVersion")
        cacheJson.put("appVersionName", app!!.appVersionName)
        cacheJson.put("appVersionCode", app!!.appVersionCode)
        cacheJson.put("appLastUpdateTime", app!!.appLastUpdateTime)

        // cache
        cacheJson.put("class", classCacheJson)
        cacheJson.put("methods", methodsCacheJson)
        ConfigV1.get().dexkitCache = cacheJson
    }

    // 拓展方法
    private fun ClassDataList?.instance(label: String): Class<*>? {
        return this?.singleOrNull().instance(label)
    }

    private fun ClassData?.instance(label: String): Class<*>? {
        KLogCat.tagI(TAG, "found-class[$label]: ${this?.name}")
        classCacheJson.put(label, "${this?.name}")
        return this?.getInstance(lparam.classLoader)
    }

    private fun MethodDataList.instanceAll(label: String): List<Method> {
        val array = JSONArray()
        methodsCacheJson.put(label, array)
        return this.filter {
            it.isMethod
        }.map {
            KLogCat.tagI(TAG, "found-method[$label]: $it")
            array.put(it.toJson())
            it.getMethodInstance(lparam.classLoader)
        }
    }

    private fun MethodData.toJson(): JSONObject {
        val json = JSONObject()
        json.put("className", className)
        json.put("methodName", methodName)
        json.put("paramTypeNames", paramTypeNames.joinToString())
        return json
    }

    private fun String.loadOrFindClass(): Class<*>? {
        if (KTextUtils.isEmpty(this)) {
            return null
        }

        return try {
            app?.classLoader?.loadClass(this) ?: lparam.findClass(this)
        } catch (e: Throwable) {
            null
        }
    }
}