package io.github.fplus.core.hook.logic

import com.freegang.extension.format
import com.freegang.extension.getStringOrDefault
import com.freegang.extension.parseJSON
import com.freegang.ktutils.log.KLogCat
import com.freegang.ktutils.net.KHttpUtils
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.feed.model.Video
import com.ss.ugc.aweme.ImageUrlStruct
import io.github.xpler.core.XplerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Field
import java.util.Date

fun Aweme.createDate(): String {
    return Date(this.createTime * 1000).format()
}

suspend fun Aweme.cityInfo(): String {
    return withContext(Dispatchers.IO) {
        runCatching {
            val result = KHttpUtils.get("http://api.ip33.com/Area_Code/GetArea/?code=$city")
            val areaJson = result.parseJSON()
            val province = areaJson.getStringOrDefault("province")
            val city = areaJson.getStringOrDefault("city")
            val county = areaJson.getStringOrDefault("county")

            "$province $city $county".trim()
        }.getOrElse {
            XplerLog.e(it)
            "获取失败"
        }
    }
}

/**
 * 按"原始 JSON 键"从 [Video] 上取播放地址列表。
 *
 * 背景（40.6.0 字段混淆）：
 * `Video` 的播放地址字段被整体混淆成单字母 ——
 * `playAddr` -> `a`、`playAddrH265` -> `b`、`h264PlayAddr` -> `d`。
 * 任何按原字段名的访问（编译期字段引用 / 反射）都会抛
 * `NoSuchFieldError: No instance field playAddr ...`，导致下载入口整条链路崩溃。
 *
 * 稳定锚点是 Gson 的 `@SerializedName` 注解：注解值保存着原始 JSON 键
 * （`play_addr` / `play_addr_265` / `play_addr_h264`），字段名怎么混淆都不受影响；
 * 命中的字段值仍是 `UrlModel` 子类，而 `UrlModel.urlList` 未被混淆，可直接反射读取。
 *
 * 查找顺序：
 *   1. `@SerializedName` 注解值匹配（40.6.0 及以后）；
 *   2. 原字段名匹配（字段未混淆的旧版本）；
 *   3. 兜底：声明类型含 `VideoUrlModel` 且 urlList 非空的字段。
 *
 * @param jsonKeys    按优先级排列的原始 JSON 键
 * @param legacyNames 按优先级排列的旧版本字段名
 */
fun Video.findVideoUrlList(
    jsonKeys: List<String>,
    legacyNames: List<String> = emptyList(),
): List<String>? {
    val fields = allFields()

    for (key in jsonKeys) {
        val field = fields.firstOrNull { serializedNameOf(it) == key } ?: continue
        val urlList = urlListOf(field, this)?.takeIf { it.isNotEmpty() } ?: continue
        return urlList
    }

    for (name in legacyNames) {
        val field = fields.firstOrNull { it.name == name } ?: continue
        val urlList = urlListOf(field, this)?.takeIf { it.isNotEmpty() } ?: continue
        return urlList
    }

    // 兜底：字段结构再次变化时，只认 VideoUrlModel 类型的字段，
    // 避免误把 cover / background 等其它 UrlModel 当成播放地址。
    val fallback = fields.firstNotNullOfOrNull { field ->
        if (!field.type.name.contains("VideoUrlModel")) return@firstNotNullOfOrNull null
        urlListOf(field, this)?.takeIf { it.isNotEmpty() }
    }

    if (fallback == null) {
        KLogCat.tagI("VideoUrl", "未命中播放地址字段, jsonKeys=$jsonKeys, fields=${fields.size}")
    }
    return fallback
}

/** [Video] 及其父类链上的全部字段（字段名被混淆，只能按注解/类型筛选） */
private fun Video.allFields(): List<Field> {
    val fields = mutableListOf<Field>()
    var clazz: Class<*>? = javaClass
    while (clazz != null && clazz != Any::class.java) {
        val current = clazz
        runCatching { fields += current.declaredFields }
        clazz = current.superclass
    }
    return fields
}

/** 读字段上的 `@SerializedName` 值；按注解类名比对，不引入编译期 gson 依赖 */
private fun serializedNameOf(field: Field): String? {
    return runCatching {
        field.annotations
            .firstOrNull { it.annotationClass.java.name == "com.google.gson.annotations.SerializedName" }
            ?.let { it.annotationClass.java.getMethod("value").invoke(it) as? String }
    }.getOrNull()
}

/** 读取字段值，并从其 UrlModel 中取出 urlList（`urlList` 字段名未被混淆） */
private fun urlListOf(field: Field, target: Any): List<String>? {
    return runCatching {
        field.isAccessible = true
        val value = field.get(target)
        if (value == null) {
            null
        } else {
            @Suppress("UNCHECKED_CAST")
            value.javaClass.getField("urlList").get(value) as? List<String>
        }
    }.getOrNull()
}

fun Aweme.getH265VideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.findVideoUrlList(
        jsonKeys = listOf("play_addr_265", "play_addr_h264", "play_addr"),
        legacyNames = listOf("playAddrH265", "h264PlayAddr", "playAddr"),
    ) ?: emptyList()
}

fun Aweme.getH264VideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.findVideoUrlList(
        jsonKeys = listOf("play_addr_h264", "play_addr"),
        legacyNames = listOf("h264PlayAddr", "playAddr"),
    ) ?: emptyList()
}

fun Aweme.getAutoVideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.findVideoUrlList(
        jsonKeys = listOf("play_addr", "play_addr_h264", "play_addr_265"),
        legacyNames = listOf("playAddr", "h264PlayAddr", "playAddrH265"),
    ) ?: emptyList()
}

fun Aweme.getImageUrlList(): List<ImageUrlStruct> {
    return images ?: return emptyList()
}

/**
 * 图文动图的视频地址（空 = 静态图）。
 *
 * 图文动图在数据层是「静态图 + 一段短视频」：`ImageUrlStruct.video` 非空即动图
 * （旁边还有 `imgDuration`、`livePhotoType` 佐证）。该字段名未混淆，
 * 39.9.0（jadx）与 40.8.0（MT）都确认存在。
 */
fun ImageUrlStruct.animatedVideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.findVideoUrlList(
        jsonKeys = listOf("play_addr", "play_addr_h264", "play_addr_265"),
        legacyNames = listOf("playAddr", "h264PlayAddr", "playAddrH264", "playAddrH265"),
    ) ?: emptyList()
}

fun Aweme.getMusicUrlList(): List<String> {
    val music = music ?: return emptyList()
    return music.playUrl?.urlList ?: emptyList()
}

fun Aweme.sortString(): String {
    val desc = "$desc".replace(Regex("\\s"), "")
    return "awemeType=${awemeType}, desc=$desc"
}