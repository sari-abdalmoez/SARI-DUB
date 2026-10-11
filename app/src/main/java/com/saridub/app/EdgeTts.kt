package com.saridub.app

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft Edge read-aloud voices (the same service used by edge-tts).
 * Returns MP3 bytes for one utterance. Voice, rate, pitch and style are per character and per emotion.
 */
object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM = "130.0.2849.68"
    private const val CONFIG_JSON = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Sec-MS-GEC token: SHA-256 of the current 5-minute window (Windows file time) plus the trusted token. */
    private fun secMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000 + 11644473600L
        ticks -= ticks % 300
        ticks *= 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256").digest("$ticks$TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun timestamp(): String =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun ssml(text: String, voice: String, rate: String, pitch: String, style: String?): String {
        val lang = voice.take(5)
        val prosody = "<prosody pitch='$pitch' rate='$rate'>${esc(text)}</prosody>"
        val inner = if (style != null) "<mstts:express-as style='$style'>$prosody</mstts:express-as>" else prosody
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
                "xmlns:mstts='https://www.w3.org/2001/mstts' xml:lang='$lang'>" +
                "<voice name='$voice'>$inner</voice></speak>"
    }

    suspend fun synthesize(
        text: String,
        voice: String,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        style: String? = null
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TOKEN&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=1-$CHROMIUM"
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$CHROMIUM Safari/537.36 Edg/$CHROMIUM")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").take(32).uppercase()};")
            .build()

        val audio = ByteArrayOutputStream()
        val reqId = UUID.randomUUID().toString().replace("-", "")
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("X-Timestamp:${timestamp()}\r\nContent-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" + CONFIG_JSON)
                webSocket.send("X-RequestId:$reqId\r\nContent-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${timestamp()}Z\r\nPath:ssml\r\n\r\n" +
                        ssml(text, voice, rate, pitch, style))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                val headers = String(data, 2, minOf(headerLen, data.size - 2), Charsets.UTF_8)
                if (headers.contains("Path:audio") && data.size > 2 + headerLen) {
                    audio.write(data, 2 + headerLen, data.size - 2 - headerLen)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    if (cont.isActive) cont.resume(audio.toByteArray())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }
}
