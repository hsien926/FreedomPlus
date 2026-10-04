package io.github.fplus.core.hook

import com.freegang.ktutils.app.KAppUtils
import com.freegang.ktutils.log.KLogCat
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.entity.EmptyHook
import io.github.xpler.core.lparam
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 广告拦截
 *
 * 本项目自研的 7 组广告 hook，全部基于运行时反射取目标类与方法。
 *
 * 实现上的三条原则：
 * 1. 具名调用，不做「遍历 methods 挑第一个参数能凑上的方法」式伪造回调。
 *    后者依赖 JVM 决定的方法顺序，一旦命中同名接口就会静默误调。
 * 2. 不写死任何混淆类名，只以未混淆类作为锚点，锚点缺失就记日志跳过。
 *    抖音的混淆名逐版本漂移（38.7.0 与 40.6.0 已多次改名），硬编码必然失效。
 * 3. 字段与方法的查找策略统一为沿继承链查找，避免 declaredField 与父类链两套策略互相打架。
 *
 * 技术上直接使用 XposedBridge 原生 API：目标类全部由运行时反射取得，拿到的就是 Method 对象，
 * 原生 API 正好直接接受 Method，省掉一层「类名 → Class → Method」的再解析。
 */
class HAdBlock : BaseHook() {
    companion object {
        private const val TAG = "HAdBlock"

        /** 需要拦截的广告类型（抖音广告 SDK 的类型名，比混淆类名稳定得多） */
        val BLOCKED_AD_TYPES = setOf(
            "APP_EXCITING_VIDEO", "APP_INTERSTITIAL", "APP_DRAW_AD",
            "APP_FLOW_AD", "APP_FLOW_AD_FORCE", "APP_OPEN_SCREEN",
            "APP_VIDEO_PATCH_AD_PRE", "APP_VIDEO_PATCH_AD_POST",
            "APP_BANNER", "APP_FEED",
            "GAME_INTERSTITIAL", "GAME_BANNER",
        )

        private const val KEY_EFFECTIVE_INSPIRE_TIME = "effective_inspire_time"
    }

    private val config get() = ConfigV1.get()

    /**
     * 广告类不依赖 DexKit 搜索结果，锚点类在抖音进程内直接可见。
     *
     * 注意这里必须返回 [EmptyHook] 而不是 NoneHook：
     * xpler 的 `HookEntity.init` 里，NoneHook 与 Nothing 会直接 `return@runCatching`，
     * 连 `onInit()` 都不会被调用 —— 那样本类的手动注册逻辑一行都不会执行
     * （真机实测：用 NoneHook 时日志里完全没有 HAdBlock 输出）。
     * 而 EmptyHook 会跳过注解式扫描，但**仍会走到 `onInit()`**，正是这里想要的。
     */
    override fun setTargetClass(): Class<*> = EmptyHook::class.java

    override fun onInit() {
        KLogCat.tagI(
            TAG,
            "host version=${hostVersionName()} " +
                "process=${lparam.processName} " +
                "first=${lparam.isFirstApplication} " +
                "64bit=${KAppUtils.is64BitDalvik()}",
        )

        if (!config.isAdBlock) {
            KLogCat.tagI(TAG, "ad block disabled by config")
            return
        }

        listOf(
            "L1" to { installAdSdkSwitch() },
            "L1-mini" to { installMinigameAdSdkSwitch() },
            "T1" to { installBaseAdParser() },
            "T3" to { installRewardLynxFragment() },
            "L2-interstitial" to { installInterstitialBlock() },
            "L2-reward" to { installRewardVideoAutoClose() },
            "T5" to { installMiniappSkipShow() },
        ).forEach { (name, block) ->
            val ok = runCatching(block).onFailure { KLogCat.tagE(TAG, it) }.isSuccess
            KLogCat.tagI(TAG, "[group $name] ${if (ok) "ok" else "throw"}")
        }

        KLogCat.tagI(TAG, "ad block done (共 7 组，看上方 [group ...] 行判断各组结果)")
    }

    // ==================== 1. 关闭广告 SDK 总开关 ====================

    private fun installAdSdkSwitch() {
        hookIsSupportAd("com.tt.miniapphost.ad.AbsBdpAdDependService", "L1")
    }

    private fun installMinigameAdSdkSwitch() {
        hookIsSupportAd("com.minigame.merge.miniapphost.ad.AbsBdpAdDependService", "L1-minigame")
    }

    private fun hookIsSupportAd(className: String, label: String) {
        val clazz = loadHostClass(className) ?: run {
            KLogCat.tagI(TAG, "$label: class not found, skipped")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "isSupportAd" && it.returnType == Boolean::class.javaPrimitiveType
        } ?: run {
            KLogCat.tagI(TAG, "$label: isSupportAd not found, skipped")
            return
        }

        KLogCat.tagI(
            TAG,
            "$label hook isSupportAd${method.parameterTypes.joinToString(", ", "(", ")") { it.simpleName }}",
        )
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val arg = param.args.firstOrNull { isBlockedAdIdentifier(it) } ?: return
                KLogCat.tagI(TAG, "$label blocked ${arg.javaClass.simpleName}")
                param.result = false
            }
        })
    }

    /**
     * 判断某个参数是否代表被拦截的广告类型。
     *
     * 实机核对（抖音 38.7.0）的结果：
     *   isSupportAd(com.bytedance.bdp.appbase.context.BdpAppContext, com.tt.miniapp.ad.model.AdType)
     * 广告类型是第二个参数的 **枚举对象**，不是字符串。
     * 早期实现只取 args[0].toString() 去比对字符串，在这里恒不匹配 —— 等于 L1 完全没生效。
     * 因此这里改为：枚举取 name()、其它类型退化为 toString()，逐个参数判断。
     */
    private fun isBlockedAdIdentifier(value: Any?): Boolean {
        if (value == null) return false
        val text = if (value.javaClass.isEnum) {
            runCatching { value.javaClass.getMethod("name").invoke(value) as? String }.getOrNull()
        } else {
            value.toString()
        }
        return text != null && text in BLOCKED_AD_TYPES
    }

    // ==================== 2. 归零广告解析计时 ====================

    /**
     * 从 BaseAd 的 adParser 字段反查解析器类，再找 `LIZ()` 空参方法。
     * 以未混淆的 BaseAd 作为锚点，不写死解析器类名。
     */
    private fun installBaseAdParser() {
        val baseAdClass = loadHostClass("com.ss.android.excitingvideo.model.BaseAd") ?: run {
            KLogCat.tagI(TAG, "T1: BaseAd not found, skipped")
            return
        }

        val parserField = findFieldAlongSuper(baseAdClass, "adParser") ?: run {
            KLogCat.tagI(TAG, "T1: adParser field not found, skipped")
            return
        }
        val parserClass = parserField.type
        if (parserClass == Any::class.java) return

        val target = parserClass.declaredMethods.firstOrNull {
            it.name == "LIZ" && it.returnType == Void.TYPE && it.parameterTypes.isEmpty()
        } ?: run {
            KLogCat.tagI(TAG, "T1: no void LIZ() in ${parserClass.simpleName}, skipped")
            return
        }

        KLogCat.tagI(TAG, "T1 hook ${parserClass.simpleName}.LIZ()")
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val obj = param.thisObject ?: return
                zeroJsonField(obj, "adGsonObj", KEY_EFFECTIVE_INSPIRE_TIME)
                zeroJsonField(obj, "params", KEY_EFFECTIVE_INSPIRE_TIME)
            }
        })
    }

    /**
     * 沿继承链找到 [fieldName]，若其值是 JSONObject 或 Gson JsonObject，则把 [key] 置 0。
     * 早期实现只处理 adGsonObj 且只查 declaredField，此处统一策略并补上 params。
     */
    private fun zeroJsonField(target: Any, fieldName: String, key: String) {
        val value = findFieldValueAlongSuper(target.javaClass, target, fieldName) ?: return

        if (value is JSONObject) {
            val patched = patchJsonField(value, key)
            if (patched > 0) KLogCat.tagI(TAG, "$fieldName: $key x$patched -> 0")
            return
        }

        // Gson 的 JsonObject 走 addProperty 路径
        runCatching {
            val entries = value.javaClass.getMethod("entrySet").invoke(value) as Set<*>
            val addProperty = value.javaClass.getMethod(
                "addProperty", String::class.java, Number::class.java
            )
            var patched = 0
            entries.forEach { entry ->
                val entryObj = entry as Map.Entry<*, *>
                if (entryObj.key == key) {
                    addProperty.invoke(value, key, 0)
                    patched++
                }
            }
            if (patched > 0) KLogCat.tagI(TAG, "$fieldName: $key x$patched -> 0 (gson)")
        }
    }

    private fun patchJsonField(json: JSONObject, key: String): Int {
        var count = 0
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == key) {
                json.put(k, 0)
                count++
            }
            json.optJSONObject(k)?.let { count += patchJsonField(it, key) }
        }
        return count
    }

    // ==================== 3. 激励视频计时归零 ====================

    /**
     * AbsRewardLynxFragment 是未混淆类名，可跨版本引用。
     * 早期实现硬编码 args[1] 为计时值（仅凭注释），此处改为按值域判定：
     * 凡是大于 0 的 int 参数一并归零，避免 int 顺序变化导致改错参数或漏改。
     */
    private fun installRewardLynxFragment() {
        val clazz = loadHostClass("com.bytedance.android.ad.reward.dynamicad.AbsRewardLynxFragment") ?: run {
            KLogCat.tagI(TAG, "T3: AbsRewardLynxFragment not found, skipped")
            return
        }

        val target = clazz.declaredMethods.firstOrNull { m ->
            m.returnType == Void.TYPE &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes.all { it == Int::class.javaPrimitiveType }
        } ?: run {
            KLogCat.tagI(TAG, "T3: no (int,int,int) method, skipped")
            return
        }

        KLogCat.tagI(TAG, "T3 hook ${target.name}(int,int,int)")
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args.forEachIndexed { index, arg ->
                    val value = (arg as? Int) ?: return@forEachIndexed
                    if (value > 0) {
                        KLogCat.tagI(TAG, "T3 inspireTime args[$index] $value -> 0")
                        param.args[index] = 0
                    }
                }
            }
        })
    }

    // ==================== 4. 阻断插屏广告 ====================

    private fun installInterstitialBlock() {
        val clazz = loadHostClass("com.ss.android.excitingvideo.interstitial.InterstitialAdFactory") ?: run {
            KLogCat.tagI(TAG, "L2: InterstitialAdFactory not found, skipped")
            return
        }
        val createMethods = clazz.declaredMethods.filter { it.name == "create" }
        if (createMethods.isEmpty()) {
            KLogCat.tagI(TAG, "L2: no create method, skipped")
            return
        }

        createMethods.forEach { method ->
            KLogCat.tagI(TAG, "L2 block InterstitialAdFactory.create(${method.parameterTypes.size})")
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    KLogCat.tagI(TAG, "L2 intercepted interstitial")
                    // 实机核对：create 的第三参是 InterstitialAdLoadCallback（含 onError(int,String)）。
                    // 先告知调用方失败，再阻断，避免调用方等不到回调。
                    param.args.lastOrNull()?.let { notifyAdFailure(it) }
                    param.result = null
                }
            })
        }
    }

    // ==================== 5. 激励视频自动关闭（保留奖励） ====================

    /**
     * 早期实现以 postDelayed(800ms) 反射调 closeFragment 实现“秒关”。
     * 此处把延迟收敛到 300ms，且只在真能找到 closeFragment 时才调用；
     * 找不到就明确记日志，不再像早期实现那样只打一行 debug 后静默。
     */
    private fun installRewardVideoAutoClose() {
        val clazz = loadHostClass("com.ss.android.excitingvideo.ExcitingVideoAd") ?: run {
            KLogCat.tagI(TAG, "L2: ExcitingVideoAd not found, skipped")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "createRewardVideoAdFragment" && Modifier.isStatic(it.modifiers)
        } ?: run {
            KLogCat.tagI(TAG, "L2: createRewardVideoAdFragment not found, skipped")
            return
        }

        KLogCat.tagI(TAG, "L2 hook createRewardVideoAdFragment")
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val fragment = param.result ?: return
                handler.postDelayed({
                    val closeMethod = findMethodAlongSuper(fragment.javaClass, "closeFragment")
                    if (closeMethod == null) {
                        KLogCat.tagI(TAG, "L2 closeFragment not found on ${fragment.javaClass.simpleName}")
                        return@postDelayed
                    }
                    runCatching {
                        closeMethod.isAccessible = true
                        closeMethod.invoke(fragment)
                        KLogCat.tagI(TAG, "L2 closed ${fragment.javaClass.simpleName}")
                    }.onFailure { KLogCat.tagE(TAG, it) }
                }, 300L)
            }
        })
    }

    // ==================== 6. 小程序 / 小游戏广告跳过展示 ====================

    private fun installMiniappSkipShow() {
        hookSkipShow(
            className = "com.tt.miniapphost.ad.manager.impl.MiniAppAdManagerServiceImpl",
            label = "T5-miniapp",
        )
        hookSkipShow(
            className = "com.minigame.merge.miniapp.ad.GameAdManagerService",
            label = "T5-game",
        )
    }

    /**
     * 拦截小程序 / 小游戏的广告操作 API。
     *
     * 早期版本的假设（实机核对后不成立）：
     *   - 通过事件对象的 `LIZIZ` 字段判断状态
     *   - 小程序方法 2 参、游戏方法 1 参
     * 抖音 38.7.0 的真实情况：
     *   - `AdCallback`（第二参）与 `MiniAppAdModel` / `GameAdModel` 都没有 `LIZIZ` 字段
     *   - 小程序是 operateInterstitialAd / operateVideoAd（2 参）
     *   - 游戏是 operateVideoAd（2 参与 3 参重载）/ operateInterstitialAd / operateBannerView
     * 因此改为：拦截 operate* 调用本身，并回调 onFailure 告知调用方，
     * 不再依赖任何字段名假设。
     */
    private fun hookSkipShow(className: String, label: String) {
        val clazz = loadHostClass(className) ?: run {
            KLogCat.tagI(TAG, "$label: class not found, skipped")
            return
        }

        val targets = clazz.declaredMethods.filter { m ->
            m.returnType == Void.TYPE && m.name.startsWith("operate")
        }
        if (targets.isEmpty()) {
            KLogCat.tagI(TAG, "$label: no operate* method, skipped")
            return
        }

        targets.forEach { method ->
            KLogCat.tagI(TAG, "$label hook ${method.name}(${method.parameterTypes.size})")
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 先告知调用方失败，再阻断，避免其拿不到回调
                    param.args.lastOrNull()?.let { notifyAdFailure(it) }
                    KLogCat.tagI(TAG, "$label blocked ${method.name}")
                    param.result = null
                }
            })
        }
    }

    /**
     * 通知回调对象"广告失败"。
     *
     * 抖音里存在多种回调类型，且都是抽象类/接口、方法还有重载：
     *   - 插屏：`InterstitialAdLoadCallback.onError(int, String)`
     *   - 小程序/小游戏：`AdCallback.onFailure(...)`（签名随端不同）
     * 这里统一取「参数为 (int, String) 的那一个」精确匹配调用；
     * 找不到就静默放弃 —— 阻断本身不依赖它成功。
     */
    private fun notifyAdFailure(obj: Any) {
        val methods = generateSequence<Class<*>>(obj.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == "onFailure" || it.name == "onError" }
            .sortedBy { it.parameterTypes.size }
            .toList()

        val method = methods.firstOrNull { m ->
            m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                m.parameterTypes[1] == String::class.java
        } ?: return

        runCatching {
            method.isAccessible = true
            method.invoke(obj, 16, "blocked by FreedomPlus")
        }.onFailure { KLogCat.tagE(TAG, it) }
    }

    // ==================== 工具 ====================

    /**
     * 读取宿主（抖音）的版本名。
     * 不能走 ApplicationInfo.versionName —— 该字段在 API 34 已从 SDK 中移除。
     * 这里经 ActivityThread 取宿主 Application，再交给 ktutils 解析 PackageInfo。
     */
    private fun hostVersionName(): String = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val app = activityThread.getMethod("currentApplication").invoke(null) as? android.app.Application
        app?.let { KAppUtils.getVersionName(it) } ?: "?"
    }.getOrDefault("?")

    /**
     * 经 lparam.classLoader（xpler 的 XplerClassloader）加载宿主类，
     * 该 ClassLoader 已处理宿主与模块之间的类冲突。
     */
    private fun loadHostClass(name: String): Class<*>? = try {
        Class.forName(name, false, lparam.classLoader)
    } catch (_: Throwable) {
        null
    }

    private fun findFieldAlongSuper(clazz: Class<*>, name: String): Field? {
        var cursor: Class<*>? = clazz
        while (cursor != null && cursor != Any::class.java) {
            cursor.declaredFields.firstOrNull { it.name == name }?.let { return it }
            cursor = cursor.superclass
        }
        return null
    }

    private fun findFieldValueAlongSuper(clazz: Class<*>, target: Any, name: String): Any? {
        val field = findFieldAlongSuper(clazz, name) ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(target)
        }.getOrNull()
    }

    private fun findMethodAlongSuper(clazz: Class<*>, name: String): Method? {
        var cursor: Class<*>? = clazz
        while (cursor != null && cursor != Any::class.java) {
            cursor.declaredMethods.firstOrNull { it.name == name }?.let { return it }
            cursor = cursor.superclass
        }
        return null
    }
}
