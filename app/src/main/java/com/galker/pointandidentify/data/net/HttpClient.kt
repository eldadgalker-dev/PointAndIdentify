// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.data.net

import com.galker.pointandidentify.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Minimal HTTPS client without third-party dependencies. All calls run on Dispatchers.IO. */
class HttpClient {

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            checkStatus(conn, url)
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > AppConfig.HTTP_MAX_BYTES) throw IOException("Response too large: $url")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getText(url: String): String = String(getBytes(url), Charsets.UTF_8)

    /** Streams a response to a file and reports progress in percent (-1 when length is unknown). */
    suspend fun download(url: String, target: File, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            checkStatus(conn, url)
            val length = conn.contentLengthLong
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    var lastPct = -2
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > AppConfig.HTTP_MAX_BYTES) throw IOException("Download too large: $url")
                        out.write(buf, 0, n)
                        val pct = if (length > 0) ((total * 100) / length).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Cannot finalize download: ${target.path}")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = AppConfig.HTTP_CONNECT_TIMEOUT_MS
        conn.readTimeout = AppConfig.HTTP_READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true // GitHub release downloads redirect https -> https
        conn.setRequestProperty("User-Agent", "PointAndIdentify-Android")
        conn.setRequestProperty("Cache-Control", "no-cache")
        return conn
    }

    private fun checkStatus(conn: HttpURLConnection, url: String) {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code for $url")
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
