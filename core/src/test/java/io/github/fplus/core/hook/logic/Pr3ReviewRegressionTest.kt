// Kotlin · Pr3ReviewRegressionTest.kt · JUnit 4 / JVM 11 · PR #3 隔离审查用例
package io.github.fplus.core.hook.logic

import com.freegang.ktutils.io.FileKind
import com.freegang.ktutils.io.KFileUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class Pr3ReviewRegressionTest {
    class Item(@JvmField val isAd: Boolean)
    class Data(@JvmField var items: List<Item>)
    class Model(@JvmField var mData: Data?)
    class LateHost(@JvmField var mModel: Model?)
    class RejectingList : ArrayList<Any?>() {
        override fun addAll(elements: Collection<Any?>): Boolean {
            throw UnsupportedOperationException("模拟宿主列表批量写入失败")
        }
    }
    class TypedHolder(@JvmField val items: RejectingList)

    @Test fun unresolved_path_should_retry_after_model_becomes_available() {
        val method = SourceFilterLogic.javaClass.getDeclaredMethod("resolvePath", Any::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        val host = LateHost(null)
        val initial = method.invoke(SourceFilterLogic, host, false)
        val resolved_getter = initial.javaClass.getDeclaredMethod("getResolved").apply { isAccessible = true }
        assertEquals(false, resolved_getter.invoke(initial))
        host.mModel = Model(Data(listOf(Item(false), Item(true))))
        val retry = method.invoke(SourceFilterLogic, host, false)
        assertEquals("模型初始化后应重新解析，而不是永久缓存失败", true, resolved_getter.invoke(retry))
    }

    @Test fun failed_write_back_should_preserve_original_list() {
        val ordinary = Item(false)
        val flagged = Item(true)
        val original = RejectingList().apply { add(ordinary); add(flagged) }
        val holder = TypedHolder(original)
        val method = SourceFilterLogic.javaClass.getDeclaredMethod("writeBack", Any::class.java, java.lang.reflect.Field::class.java, List::class.java)
        method.isAccessible = true
        val result = method.invoke(SourceFilterLogic, holder, TypedHolder::class.java.getField("items"), arrayListOf<Any?>(ordinary))
        assertEquals(false, result)
        assertEquals("fail-open 不得先清空源列表再返回失败", listOf(ordinary, flagged), original)
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()

    private fun probe(vararg boxes: ByteArray): String? {
        val directory = File(System.getProperty("java.io.tmpdir"), "pr3-review-media").apply { mkdirs() }
        val file = File.createTempFile("container-", ".bin", directory)
        file.writeBytes(boxes.fold(ByteArray(0)) { previous, next -> previous + next })
        return IsoBmffProbe.handlerType(file)
    }

    @Test fun handler_probe_should_ignore_fourcc_inside_unrelated_metadata() {
        val metadata = box("udta", "hdlr".toByteArray() + ByteArray(8) + "pict".toByteArray())
        val handler = box("hdlr", ByteArray(8) + "vide".toByteArray() + ByteArray(12))
        val track = box("trak", box("mdia", handler))
        assertEquals("应解析 trak/mdia/hdlr，不能匹配任意元数据字符串", "vide", probe(box("moov", metadata + track)))
    }

    @Test fun common_mp4_brand_should_be_identified_without_url_suffix() {
        val head = ByteBuffer.allocate(16).putInt(24).put("ftypisom".toByteArray()).putInt(0).array()
        assertEquals("无后缀视频直链应识别为 MP4", FileKind.MP4, KFileUtils.getFileKind(head))
    }

    @Test fun valid_video_handler_is_detected() {
        val handler = box("hdlr", ByteArray(8) + "vide".toByteArray() + ByteArray(12))
        assertEquals("vide", probe(box("moov", box("trak", box("mdia", handler)))))
    }

    @Test fun gif_header_does_not_depend_on_sample_dimensions() {
        val head = "GIF89a".toByteArray() + byteArrayOf(2, 0, 7, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(FileKind.GIF, KFileUtils.getFileKind(head))
    }
}
