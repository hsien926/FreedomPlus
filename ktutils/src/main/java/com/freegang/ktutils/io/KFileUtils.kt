package com.freegang.ktutils.io

import com.freegang.extension.superSplit
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

enum class FileKind(
    val suffix: String,
) {
    // image
    JPG(".jpg"),
    PNG(".png"),
    HEIC(".heic"),
    // 抖音"动态表情包"实测是 HEVC 编码的 ISO-BMFF 短视频(ftyp 主 brand = msf1,
    // moov/trak 里是 hvc1 视频轨), 不是 GIF/WebP 动图. 必须给 .mp4 才播得出来,
    // 否则任何看图工具只能解出首帧.
    MP4(".mp4"),
    GIF(".gif"),
    WEBP(".webp"),
    ;

    /**
     * 判断文件头是否属于该类型。
     *
     * 关键点: 只比对格式定义里**固定不变**的字段。早期实现把整段文件头写成常量
     * (如 GIF 带上了逻辑屏幕宽高、WEBP 带上了 RIFF 块长度), 那些字节随文件变化,
     * 导致除了当初取样那个文件之外永远匹配不上 —— 动图因此一直沿用调用方给的
     * 默认后缀, 被相册/微信按静态图片解码, 只剩首帧。
     *
     * @param bytes 文件头字节, 至少 12 字节
     */
    fun matches(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return when (this) {
            // SOI(FFD8) + 段标记起始(FF): APP0(JFIF) / APP1(EXIF) 等均适用
            JPG -> bytes.hexAt(0, 2) == "ffd8" && bytes.hexAt(2, 3) == "ff"
            PNG -> bytes.hexAt(0, 8) == "89504e470d0a1a0a"
            // ISO-BMFF 容器: [4,8) 为 "ftyp", [8,12) 为 brand
            // msf1 = HEIF image sequence / 抖音动态表情的 HEVC 短视频, 归到 MP4
            MP4 -> bytes.hexAt(4, 8) == "66747970" && bytes.hexAt(8, 12) == "6d736631"
            HEIC -> bytes.hexAt(4, 8) == "66747970" && bytes.hexAt(8, 12) in HEIF_BRANDS
            // GIF87a / GIF89a; 其后 6 字节是逻辑屏幕描述符(宽高/调色板均可能不同), 不参与比较
            GIF -> bytes.hexAt(0, 6) == "474946383761" || bytes.hexAt(0, 6) == "474946383961"
            // RIFF + 4 字节块长度(随文件大小变化) + WEBP
            WEBP -> bytes.hexAt(0, 4) == "52494646" && bytes.hexAt(8, 12) == "57454250"
        }
    }

    companion object {
        /** HEIF 常见 brand. 刻意不含 MP4 的 isom/mp42/mp41, 避免视频被误判成图片. */
        private val HEIF_BRANDS = setOf(
            "68656963", // heic
            "68656978", // heix
            "6865696d", // heim
            "68656973", // heis
            "68657663", // hevc
            "68657678", // hevx
            "6d696631", // mif1
            // 注意: 不要收 msf1 —— 那是 HEIF image sequence / 动态表情视频, 归 MP4
        )
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

/** 取 [from, to) 区间字节的小写十六进制串, 越界返回空串 */
private fun ByteArray.hexAt(from: Int, to: Int): String {
    if (from < 0 || to > size || from >= to) return ""
    val builder = StringBuilder((to - from) * 2)
    for (index in from until to) {
        val value = this[index].toInt() and 0xFF
        builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return builder.toString()
}

object KFileUtils {
    /**
     * 获取文件的类型
     *
     * @param bytes 文件的字节数组
     */
    @JvmStatic
    fun getFileKind(bytes: ByteArray): FileKind? {
        return FileKind.values().firstOrNull { it.matches(bytes) }
    }

    /**
     * 强制删除文件或目录，即使文件或目录不存在也不会抛出异常。
     *
     * @param file 要删除的文件或目录
     */
    @JvmStatic
    fun deleteForcefully(file: File): Boolean {
        if (!file.exists()) {
            return false
        }

        try {
            // 文件直接删除
            if (file.isFile) {
                return file.delete()
            }

            // 遍历删除文件夹
            file.listFiles()?.forEach {
                deleteForcefully(it)
            }

            // 删除文件夹本身
            file.delete()
        } catch (e: SecurityException) {
            // 处理没有权限删除的情况
            e.printStackTrace()
            return false
        }

        return true
    }

    /**
     * 递归删除文件或目录。
     *
     * @param file 要删除的文件或目录
     */
    @JvmStatic
    fun deleteRecursively(file: File): Boolean {
        return file.deleteRecursively()
    }

    /**
     * 清理文件名中的特殊字符，返回纯净的文件名。
     *
     * @param filename 原始文件名
     * @return 清理后的文件名
     */
    @JvmStatic
    fun pureFileName(filename: String): String {
        val replaceMap = mapOf(
            " " to "",
            "<" to "‹",
            ">" to "›",
            "\"" to "”",
            "\'" to "’",
            "\\" to "-",
            "$" to "¥",
            "/" to "-",
            "|" to "-",
            "*" to "-",
            ":" to "-",
            "?" to "？"
        )
        var pureName = filename.replace("\\s".toRegex(), "")
        for (entry in replaceMap) pureName = pureName.replace(entry.key, entry.value)
        return pureName
    }

    /**
     * 将文件名转换为安全的文件名，确保文件名不超过255个字节。
     *
     * @param filename 原始文件名
     * @param suffix 指定后缀名, 默认空字符串为文件名的自带后缀名
     * @return 安全的文件名
     */
    @JvmStatic
    @JvmOverloads
    fun secureFilename(filename: String, suffix: String = ""): String {
        var actualSuffix = suffix
        if (actualSuffix.isBlank()) {
            val lastIndexOf = filename.lastIndexOf(".")
            val hasSuffix = (lastIndexOf != -1) && (lastIndexOf != 0)
                    && (lastIndexOf != filename.length) //.开头的文件名不算
            actualSuffix = if (hasSuffix) filename.substring(lastIndexOf) else ""
        }

        val maxLength = 255
        val actualSuffixByteSize = actualSuffix.toByteArray().size
        val stringList = filename.removeSuffix(actualSuffix).superSplit()
        var countLength = actualSuffixByteSize
        var secureFilename = ""
        for (item in stringList) {
            if (item.isEmpty()) continue
            val itemSize = item.toByteArray().size
            countLength += itemSize
            if (countLength >= maxLength - itemSize) break
            secureFilename = secureFilename.plus(item)
        }

        return secureFilename.plus(actualSuffix)
    }

    /**
     * 将指定文本写入对某个文件。
     *
     * @param file 被操作的文件
     * @param text 被写入的文本内容
     * @param charset 编码方式
     */
    @JvmStatic
    @JvmOverloads
    fun writeText(file: File, text: String, charset: Charset = Charsets.UTF_8) {
        file.writeText(text, charset)
    }

    /**
     * 将指定字节数组写入某个文件。
     *
     * @param file 被操作的文件
     * @param array 被写入的字节数组
     */
    @JvmStatic
    fun writeBytes(file: File, array: ByteArray) {
        file.writeBytes(array)
    }

    /**
     * 将指定文件读入字节数组。
     *
     * @param file 被操作的文件
     */
    @JvmStatic
    fun readBytes(file: File): ByteArray {
        return file.readBytes()
    }

    /**
     * 将指定输入流读入字节数组。
     *
     * @param stream 被操作输入流
     */
    @JvmStatic
    fun readBytes(stream: InputStream): ByteArray {
        return stream.readBytes()
    }

    /**
     * 将指定文本内容追加到某个文件末尾。
     *
     * @param file 被操作的文件
     * @param text 被追加的文本内容
     * @param charset 编码方式
     */
    fun appendText(file: File, text: String, charset: Charset = Charsets.UTF_8) {
        file.appendText(text, charset)
    }

    /**
     * 将指定字节数组追加到某个文件末尾。
     *
     * @param file 被操作的文件
     * @param array 被追加的字节数组
     */
    fun appendBytes(file: File, array: ByteArray) {
        file.appendBytes(array)
    }

    /**
     * 获取某个文件的后缀名。
     *
     * @param file 被操作的文件
     * @return 后缀名, 如果是文件夹则为null
     */
    @JvmStatic
    fun getSuffix(file: File): String? {
        return if (file.isDirectory) {
            null
        } else {
            file.extension
        }
    }

    /**
     * 获取某个文件名的后缀名。
     *
     * @param filename 被操作的文件
     * @return 后缀名, 如果没有则返回空字符串
     */
    @JvmStatic
    fun getSuffix(filename: String): String {
        return filename.substringAfterLast(".", "")
    }

    /**
     * 重定义文件类型
     *
     * @param file 被操作的文件
     * @param suffix 新的后缀名
     * @return 重定义后的文件
     */
    @JvmStatic
    fun redefineSuffix(file: File, suffix: String): File {
        return File(file.parent, file.nameWithoutExtension.plus(suffix))
    }

}