package com.liskovsoft.smartyoutubetv2.common.dualsub

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class CachedSubtitles(val cues: List<SubCue>, val translations: List<String?>)

/**
 * Finished translations, one file per video/track/target/model.
 * Files are written atomically (tmp + rename) and evicted by last use once the total exceeds [maxBytes].
 */
class TranslationCache(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    companion object {
        const val DEFAULT_MAX_BYTES = 50L * 1024 * 1024
        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9_-]")

        @JvmStatic
        fun fileName(videoId: String, trackLang: String, targetLang: String, model: String): String {
            fun safe(s: String) = s.replace(UNSAFE_CHARS, "_")
            return "${safe(videoId)}.${safe(trackLang)}.${safe(targetLang)}.${safe(model)}-p${ClaudeTranslator.PROMPT_VERSION}.json"
        }
    }

    @Synchronized
    fun read(name: String): CachedSubtitles? {
        val file = File(dir, name)

        if (!file.isFile) {
            return null
        }

        return try {
            val items = JsonParser().parse(file.readText()).asJsonObject.getAsJsonArray("cues")
            val cues = ArrayList<SubCue>(items.size())
            val translations = ArrayList<String?>(items.size())

            for (item in items) {
                val obj = item.asJsonObject
                cues.add(SubCue(obj.get("s").asLong, obj.get("e").asLong, obj.get("o").asString))
                translations.add(obj.get("t")?.takeUnless { it.isJsonNull }?.asString)
            }

            file.setLastModified(System.currentTimeMillis()) // LRU marker
            CachedSubtitles(cues, translations)
        } catch (e: Exception) {
            file.delete() // corrupted
            null
        }
    }

    @Synchronized
    fun write(name: String, cues: List<SubCue>, translations: List<String?>) {
        val array = JsonArray()

        cues.forEachIndexed { i, cue ->
            array.add(JsonObject().apply {
                addProperty("s", cue.startMs)
                addProperty("e", cue.endMs)
                addProperty("o", cue.text)
                addProperty("t", translations[i])
            })
        }

        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(JsonObject().apply { add("cues", array) }.toString())

        if (!tmp.renameTo(File(dir, name))) {
            tmp.delete()
            return
        }

        trim()
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }

        for (file in files) {
            if (total <= maxBytes) {
                break
            }
            total -= file.length()
            file.delete()
        }
    }
}
