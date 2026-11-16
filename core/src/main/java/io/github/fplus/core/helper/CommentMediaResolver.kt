// Kotlin · CommentMediaResolver.kt · JVM 11 / Android 7+ · 评论媒体字段解析
package io.github.fplus.core.helper

import java.lang.reflect.Field
import java.lang.reflect.Modifier

object CommentMediaResolver {
    fun fields_of(target: Any): List<Field> {
        val fields = mutableListOf<Field>()
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            val current = type
            fields += runCatching { current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) } }
                .getOrDefault(emptyList())
            type = current.superclass
        }
        return fields
    }

    fun value_of(target: Any, field: Field): Any? = runCatching {
        field.isAccessible = true
        field.get(target)
    }.getOrNull()

    fun named_value(target: Any, name: String): Any? = fields_of(target)
        .firstOrNull { it.name == name }?.let { value_of(target, it) }

    fun urls(value: Any?): List<String> = (value as? List<*>)?.filterIsInstance<String>()
        ?.map { it.trim() }?.filter { it.startsWith("https://") || it.startsWith("http://") }
        ?.distinct() ?: emptyList()

    private fun named_urls(target: Any, names: List<String>): List<String> = names
        .firstNotNullOfOrNull { name -> urls(named_value(target, name)).takeIf { it.isNotEmpty() } }
        ?: emptyList()

    fun image_urls(image: Any): List<String> {
        // ImageUrlStruct 直接持有 List；CommentImageStruct 则包裹 UrlModel。
        named_urls(image, listOf("downloadUrlList", "urlList")).takeIf { it.isNotEmpty() }?.let { return it }
        for (name in listOf("originUrl", "downloadUrl", "mediumUrl", "cropUrl", "thumbUrl")) {
            val model = named_value(image, name) ?: continue
            named_urls(model, listOf("urlList", "url_list")).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return fields_of(image).firstNotNullOfOrNull { field ->
            val candidate = value_of(image, field) ?: return@firstNotNullOfOrNull null
            if (candidate.javaClass.name.endsWith("CommentImageLiveVideoStruct")) return@firstNotNullOfOrNull null
            named_urls(candidate, listOf("urlList", "url_list")).takeIf { it.isNotEmpty() }
        } ?: emptyList()
    }

    fun comment_video_urls(image: Any): List<String> {
        val video = fields_of(image).firstNotNullOfOrNull { field ->
            value_of(image, field)?.takeIf { it.javaClass.name.endsWith("CommentImageLiveVideoStruct") }
        } ?: return emptyList()
        named_urls(video, listOf("urlList", "url_list")).takeIf { it.isNotEmpty() }?.let { return it }
        // 混淆字段只接受 HTTP 地址列表，不能让其它非空列表挡住真实视频列表。
        return fields_of(video).firstNotNullOfOrNull { field ->
            urls(value_of(video, field)).takeIf { it.isNotEmpty() }
        } ?: emptyList()
    }
}
