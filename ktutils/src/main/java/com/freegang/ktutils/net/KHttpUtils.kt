// Kotlin · KHttpUtils.kt · Android API 21+ · HTTP 获取与完整媒体下载
package com.freegang.ktutils.net

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object KHttpUtils {
    val ANDROUD_UA get() = System.getProperty("http.agent")

    @Throws(IOException::class)
    private fun commonConnection(url: URL): HttpURLConnection {
        val connect: HttpURLConnection = url.openConnection() as HttpURLConnection
        connect.requestMethod = "GET"
        connect.connectTimeout = 5000
        connect.readTimeout = 5000
        connect.instanceFollowRedirects = true
        connect.useCaches = false
        connect.doInput = true
        ANDROUD_UA?.takeIf { it.isNotBlank() }?.let { connect.setRequestProperty("User-Agent", it) }
        connect.setRequestProperty("Accept", "*/*")
        connect.setRequestProperty("Accept-Encoding", "identity")
        return connect
    }

    /**
     * GET请求
     *
     * @param sourceUrl 目标URL地址
     * @param params    参数
     * @return 文本内容
     */
    @JvmStatic
    fun get(sourceUrl: String, params: String = ""): String {
        val sourceUrl = if (params.isBlank()) {
            sourceUrl
        } else {
            if (sourceUrl.contains("?")) "$sourceUrl&$params" else "$sourceUrl?$params"
        }

        var body = ""
        var connect: HttpURLConnection? = null
        try {
            connect = commonConnection(URL(sourceUrl))
            val inputStream =
                if (connect.responseCode == HttpURLConnection.HTTP_OK || connect.responseCode == HttpURLConnection.HTTP_CREATED) {
                    InputStreamReader(connect.inputStream, StandardCharsets.UTF_8)
                } else {
                    InputStreamReader(connect.errorStream, StandardCharsets.UTF_8)
                }
            body = inputStream.readText()
            inputStream.close()
            connect.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                connect?.disconnect()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return body
    }

    /** 下载完整响应；不会覆盖已有目标，实际类型在完整文件写入后探测。 */
    @JvmStatic
    fun download(sourceUrl: String, file: File, listener: DownloadListener? = null): File? {
        var connect: HttpURLConnection? = null
        var total = -1L
        var realCount = 0L
        try {
            connect = commonConnection(URL(sourceUrl))
            DownloadIntegrity.requireSuccessfulStatus(connect.responseCode)
            val lengthHeader = connect.getHeaderField("Content-Length")
            if (lengthHeader != null && connect.getHeaderField("Transfer-Encoding") != null) {
                throw IOException("下载响应同时声明两种长度机制")
            }
            total = DownloadIntegrity.declaredLength(lengthHeader)
            return DownloadIntegrity.save(connect.inputStream.buffered(), file, total) { count ->
                realCount = count
                listener?.downloading(count, total, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runCatching { listener?.downloading(realCount, total, e) }
            return null
        } finally {
            connect?.disconnect()
        }
    }

    fun interface DownloadListener {
        fun downloading(real: Long, total: Long, e: Throwable?)
    }
}