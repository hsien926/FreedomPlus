// Kotlin · IsoBmffProbe.kt · JVM / Android API 24+ · 保留 core 探测 API 的工具层委托
package io.github.fplus.core.hook.logic

import com.freegang.ktutils.io.IsoBmffInspector
import java.io.File

/** 只读取严格的 moov/trak/mdia/hdlr；视频和图像轨优先于音频轨。 */
object IsoBmffProbe {
    fun handlerType(file: File): String? = IsoBmffInspector.probe(file)?.handlerType
}
