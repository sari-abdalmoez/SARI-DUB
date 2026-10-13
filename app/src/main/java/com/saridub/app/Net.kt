package com.saridub.app

import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** Every provider failure is classified so the UI can show the real reason. */
enum class ErrKind { MISSING_KEY, INVALID_KEY, RATE_LIMITED, TIMEOUT, NETWORK, MALFORMED, PROVIDER_DOWN, HTTP, OK }

class ApiException(val kind: ErrKind, message: String, val code: Int = 0) : IOException(message)

object Net {
    fun kindFor(code: Int): ErrKind = when (code) {
        401, 403 -> ErrKind.INVALID_KEY
        429 -> ErrKind.RATE_LIMITED
        in 500..599 -> ErrKind.PROVIDER_DOWN
        else -> ErrKind.HTTP
    }

    /** GET with one retry on HTTP 429. Never returns partial data. */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 8000): String {
        var last: ApiException? = null
        for (attempt in 0..1) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeout
                c.readTimeout = timeout
                c.setRequestProperty("User-Agent", "SariDub/2.2")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val code = c.responseCode
                if (code == 429 && attempt == 0) {
                    Thread.sleep(minOf(2000L, (c.getHeaderField("Retry-After")?.toLongOrNull() ?: 1L) * 1000L))
                    continue
                }
                if (code !in 200..299) throw ApiException(kindFor(code), "HTTP $code", code)
                return c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: SocketTimeoutException) {
                throw ApiException(ErrKind.TIMEOUT, "timeout")
            } catch (e: ApiException) {
                last = e
                if (e.kind != ErrKind.RATE_LIMITED) throw e
            } catch (e: IOException) {
                throw ApiException(ErrKind.NETWORK, e.message ?: "network")
            } finally {
                c.disconnect()
            }
        }
        throw last ?: ApiException(ErrKind.RATE_LIMITED, "rate limited", 429)
    }

    fun getBytes(url: String, timeout: Int = 10000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "SariDub/2.2")
            val code = c.responseCode
            if (code !in 200..299) throw ApiException(kindFor(code), "HTTP $code", code)
            return c.inputStream.use { it.readBytes() }
        } catch (e: SocketTimeoutException) {
            throw ApiException(ErrKind.TIMEOUT, "timeout")
        } catch (e: IOException) {
            if (e is ApiException) throw e
            throw ApiException(ErrKind.NETWORK, e.message ?: "network")
        } finally {
            c.disconnect()
        }
    }

    /** POST JSON. Returns the HTTP code and body, including error bodies, so callers can read the provider message. */
    fun post(url: String, body: String, headers: Map<String, String>, timeout: Int = 150000): Pair<Int, String> {
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
        } catch (e: SocketTimeoutException) {
            throw ApiException(ErrKind.TIMEOUT, "timeout")
        } catch (e: IOException) {
            throw ApiException(ErrKind.NETWORK, e.message ?: "network")
        } finally {
            c.disconnect()
        }
    }

    /** Checks that a media URL actually answers before the player opens it. */
    fun isReachable(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "HEAD"
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        false
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Path segment encoding (spaces become %20, not +). */
    fun pathEnc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun describeKind(k: ErrKind): String = when (k) {
        ErrKind.MISSING_KEY -> "المفتاح غير مضبوط، أدخله في الإعدادات"
        ErrKind.INVALID_KEY -> "المفتاح غير صالح أو غير مصرّح به"
        ErrKind.RATE_LIMITED -> "تجاوزت الحد المسموح من الطلبات، حاول لاحقاً"
        ErrKind.TIMEOUT -> "انتهت مهلة الاتصال"
        ErrKind.NETWORK -> "لا يوجد اتصال بالإنترنت"
        ErrKind.MALFORMED -> "استجابة غير متوقعة من الخدمة"
        ErrKind.PROVIDER_DOWN -> "الخدمة متوقفة مؤقتاً"
        ErrKind.HTTP -> "خطأ في الطلب"
        ErrKind.OK -> "يعمل"
    }

    fun describe(e: Throwable): String = when (e) {
        is ApiException -> describeKind(e.kind)
        is JSONException -> describeKind(ErrKind.MALFORMED)
        else -> e.message ?: "خطأ غير معروف"
    }
}
