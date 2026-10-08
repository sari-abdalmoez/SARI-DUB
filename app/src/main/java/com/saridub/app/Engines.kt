package com.saridub.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PipelineError(val code: String, message: String) : Exception(message)

suspend fun <T> Task<T>.awaitT(): T? = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

/** On-device translation (ML Kit). Models are downloaded only on explicit user request. */
object TranslationModels {
    private val mgr get() = RemoteModelManager.getInstance()
    private fun model(tag: String) = TranslateRemoteModel.Builder(tag).build()
    suspend fun isDownloaded(tag: String): Boolean =
        tag == TranslateLanguage.ENGLISH || mgr.isModelDownloaded(model(tag)).awaitT() == true
    suspend fun download(tag: String) { mgr.download(model(tag), DownloadConditions.Builder().build()).awaitT() }
    suspend fun delete(tag: String) { mgr.deleteDownloadedModel(model(tag)).awaitT() }
    fun valid(tag: String) = TranslateLanguage.fromLanguageTag(tag) != null
}

class SegTranslator(src: String, dst: String) {
    private val client = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(src) ?: throw PipelineError("LANG", "Unsupported source language '$src'"))
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(dst) ?: throw PipelineError("LANG", "Unsupported target language '$dst'"))
            .build()
    )
    suspend fun translate(text: String): String = client.translate(text).awaitT() ?: ""
    fun close() = client.close()
}

/** Android system TTS: offline voices only (network voices are rejected to keep processing local). */
class TtsSynth(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private val waiting = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

    suspend fun init(): Boolean = suspendCancellableCoroutine<Boolean> { c ->
        tts = TextToSpeech(ctx) { st -> if (c.isActive) c.resume(st == TextToSpeech.SUCCESS) }
        c.invokeOnCancellation { shutdown() }
    }.also { ok ->
        if (ok) tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { waiting.remove(it) }?.resume(true) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { id?.let { waiting.remove(it) }?.resume(false) }
            override fun onError(id: String?, code: Int) { id?.let { waiting.remove(it) }?.resume(false) }
        })
    }

    fun voices(locale: Locale): List<Voice> =
        (tts?.voices ?: emptySet()).filter {
            it.locale.language == locale.language && !it.isNetworkConnectionRequired && !it.features.contains("notInstalled")
        }.sortedBy { it.name }

    suspend fun synth(text: String, voice: Voice?, pitch: Float, rate: Float, out: File): Boolean {
        val t = tts ?: return false
        out.parentFile?.mkdirs(); out.delete()
        if (voice != null) t.voice = voice
        t.setPitch(pitch); t.setSpeechRate(rate)
        val id = "u" + System.nanoTime()
        val ok = suspendCancellableCoroutine<Boolean> { c ->
            waiting[id] = c
            c.invokeOnCancellation { waiting.remove(id); t.stop() }
            if (t.synthesizeToFile(text, null, out, id) != TextToSpeech.SUCCESS) { waiting.remove(id); c.resume(false) }
        }
        return ok && out.length() > 44
    }

    fun preview(text: String, voice: Voice?, pitch: Float, rate: Float) {
        val t = tts ?: return
        if (voice != null) t.voice = voice
        t.setPitch(pitch); t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
