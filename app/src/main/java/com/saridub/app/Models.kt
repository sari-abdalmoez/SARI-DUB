package com.saridub.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TreeSet

const val PIPE_RATE = 24000

data class Segment(
    val id: Int,
    var startMs: Long,
    var endMs: Long,
    val srtStartMs: Long,
    val srtEndMs: Long,
    var speaker: Int = 0,
    var original: String,
    var translated: String = "",
    var pitchHz: Float = 0f
)

data class Speaker(
    val id: Int,
    var name: String,
    var pitchHz: Float,
    var voiceIndex: Int,
    var pitchAdjust: Float = 1f,
    var rate: Float = 1f
)

class Project(val id: String) {
    var title = ""
    var sourceUri = ""
    var durationMs = 0L
    var info = ""
    var srcLang = "en"
    var dstLang = "ar"
    var mode = "FULL"          // SUBTITLES | VOICE | FULL
    var chunkMin = 10
    var quality = "BALANCED"   // FAST | BALANCED | HIGH
    var duck = true
    var audioTrack = 0
    var rawRate = 0
    var rawCh = 0
    var extractDone = false
    var analysisDone = false
    var translateDone = false
    var createdAt = System.currentTimeMillis()
    val ready: TreeSet<Int> = TreeSet()
    val failed: TreeSet<Int> = TreeSet()
    val segments = mutableListOf<Segment>()
    val speakers = mutableListOf<Speaker>()
    val glossary = linkedMapOf<String, String>()
    val tm = linkedMapOf<String, String>()   // translation memory: normalised source -> target
    val log = mutableListOf<String>()

    val chunkMs get() = chunkMin * 60_000L
    val chunkCount get() = if (durationMs <= 0) 0 else ((durationMs + chunkMs - 1) / chunkMs).toInt()
    val hasDub get() = mode != "SUBTITLES"

    fun addLog(s: String) { log.add(s); while (log.size > 200) log.removeAt(0) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("sourceUri", sourceUri); put("durationMs", durationMs)
        put("info", info); put("srcLang", srcLang); put("dstLang", dstLang); put("mode", mode)
        put("chunkMin", chunkMin); put("quality", quality); put("duck", duck)
        put("audioTrack", audioTrack); put("rawRate", rawRate); put("rawCh", rawCh)
        put("extractDone", extractDone); put("analysisDone", analysisDone); put("translateDone", translateDone)
        put("createdAt", createdAt)
        put("ready", JSONArray(ready.toList())); put("failed", JSONArray(failed.toList()))
        put("segments", JSONArray().also { a ->
            segments.forEach { s ->
                a.put(JSONObject().put("id", s.id).put("s", s.startMs).put("e", s.endMs)
                    .put("ss", s.srtStartMs).put("se", s.srtEndMs).put("sp", s.speaker)
                    .put("o", s.original).put("t", s.translated).put("p", s.pitchHz.toDouble()))
            }
        })
        put("speakers", JSONArray().also { a ->
            speakers.forEach { s ->
                a.put(JSONObject().put("id", s.id).put("n", s.name).put("p", s.pitchHz.toDouble())
                    .put("v", s.voiceIndex).put("pa", s.pitchAdjust.toDouble()).put("r", s.rate.toDouble()))
            }
        })
        put("glossary", JSONObject(glossary as Map<*, *>))
        put("tm", JSONObject(tm as Map<*, *>))
        put("log", JSONArray(log))
    }

    companion object {
        fun fromJson(j: JSONObject): Project = Project(j.getString("id")).apply {
            title = j.optString("title"); sourceUri = j.optString("sourceUri"); durationMs = j.optLong("durationMs")
            info = j.optString("info"); srcLang = j.optString("srcLang", "en"); dstLang = j.optString("dstLang", "ar")
            mode = j.optString("mode", "FULL"); chunkMin = j.optInt("chunkMin", 10)
            quality = j.optString("quality", "BALANCED"); duck = j.optBoolean("duck", true)
            audioTrack = j.optInt("audioTrack"); rawRate = j.optInt("rawRate"); rawCh = j.optInt("rawCh")
            extractDone = j.optBoolean("extractDone"); analysisDone = j.optBoolean("analysisDone")
            translateDone = j.optBoolean("translateDone"); createdAt = j.optLong("createdAt")
            j.optJSONArray("ready")?.let { for (i in 0 until it.length()) ready.add(it.getInt(i)) }
            j.optJSONArray("failed")?.let { for (i in 0 until it.length()) failed.add(it.getInt(i)) }
            j.optJSONArray("segments")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                    segments.add(Segment(o.getInt("id"), o.getLong("s"), o.getLong("e"), o.getLong("ss"), o.getLong("se"),
                        o.optInt("sp"), o.optString("o"), o.optString("t"), o.optDouble("p", 0.0).toFloat()))
                }
            }
            j.optJSONArray("speakers")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                    speakers.add(Speaker(o.getInt("id"), o.optString("n"), o.optDouble("p", 0.0).toFloat(), o.optInt("v"),
                        o.optDouble("pa", 1.0).toFloat(), o.optDouble("r", 1.0).toFloat()))
                }
            }
            j.optJSONObject("glossary")?.let { g -> g.keys().forEach { glossary[it] = g.getString(it) } }
            j.optJSONObject("tm")?.let { g -> g.keys().forEach { tm[it] = g.getString(it) } }
            j.optJSONArray("log")?.let { for (i in 0 until it.length()) log.add(it.getString(i)) }
        }
    }
}

/** File-based project store. Large audio lives in files; only small metadata is in JSON. */
class ProjectStore(ctx: Context) {
    private val root = File(ctx.filesDir, "projects").also { it.mkdirs() }

    fun dir(id: String): File = File(root, id).also { it.mkdirs() }

    fun list(): List<Project> =
        (root.listFiles() ?: emptyArray()).mapNotNull { d -> runCatching { load(d.name) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    @Synchronized
    fun load(id: String): Project = Project.fromJson(JSONObject(File(dir(id), "project.json").readText()))

    @Synchronized
    fun save(p: Project) {
        val f = File(dir(p.id), "project.json")
        val tmp = File(dir(p.id), "project.json.tmp")
        tmp.writeText(p.toJson().toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun delete(id: String) { File(root, id).deleteRecursively() }
}
