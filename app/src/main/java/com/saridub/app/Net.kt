package com.saridub.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpException(val code: Int) : IOException("HTTP $code")

object Net {
    /** GET with one retry on HTTP 429 (rate limit). Throws on other failures. */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 8000): String {
        repeat(2) { attempt ->
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeout
                c.readTimeout = timeout
                c.setRequestProperty("User-Agent", "SariDub/2.0")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val code = c.responseCode
                if (code == 429 && attempt == 0) {
                    val wait = c.getHeaderField("Retry-After")?.toLongOrNull() ?: 1L
                    Thread.sleep(minOf(2000L, wait * 1000L))
                    return@repeat
                }
                if (code !in 200..299) throw HttpException(code)
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        throw HttpException(429)
    }

    fun getBytes(url: String, timeout: Int = 10000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "SariDub/2.0")
            if (c.responseCode !in 200..299) throw HttpException(c.responseCode)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** POST JSON. Returns HTTP code and body (body is returned even for errors). */
    fun post(url: String, body: String, headers: Map<String, String>, timeout: Int = 40000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = timeout
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Path segment encoding (spaces become %20, not +). */
    fun pathEnc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
