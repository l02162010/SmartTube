package com.liskovsoft.smartyoutubetv2.common.dualsub

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/** Retrying won't help: bad API key (401/403), unknown model, malformed request... */
class FatalApiException(val code: Int, message: String) : Exception(message) {
    val isAuthError: Boolean get() = code == 401 || code == 403
}

/** The request failed even after retries (rate limit, overload, network). */
class TransientException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The model answered, but not with a JSON array of the expected size. */
class BadOutputException(message: String) : Exception(message)

/**
 * Translates subtitle lines with the Claude Messages API.
 * The prompt and batching rules are ported from the Go `ytsub` reference implementation.
 */
class ClaudeTranslator(
    private val http: HttpTransport,
    private val apiKey: String,
    val model: String,
    private val targetLanguage: String,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    companion object {
        const val API_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val MODEL_HAIKU = "claude-haiku-5-5"
        const val MODEL_SONNET = "claude-sonnet-5-5"
        /**
         * Bump it when the prompt or the sentence splitting (Json3Parser) changes:
         * the cache file name contains it, so old cues and translations expire.
         */
        const val PROMPT_VERSION = 4
        const val MAX_RETRIES = 4
        const val CONTEXT_LINES = 6
        private const val MAX_TOKENS = 8192

        @JvmStatic
        fun languageName(code: String): String = when (LangUtil.normalize(code)) {
            "zh-Hant" -> "Traditional Chinese (Taiwan)"
            "zh-Hans" -> "Simplified Chinese"
            "en" -> "English"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            else -> code
        }
    }

    private val systemPrompt = """
        You are a professional subtitle translator. Translate video subtitles into ${languageName(targetLanguage)}.${scriptRule()}
        The user sends JSON: {"context_only": [...], "lines": {"1": "...", "2": "...", ...}}.
        "context_only" holds the subtitle lines that come right before. Read them to understand the situation, but NEVER translate or output them.
        Rules:
        - Reply with ONLY a JSON object that maps every id of "lines" to its translation: {"1": "...", "2": "...", ...}. Same ids, nothing else.
        - The translation under an id must render exactly the text of that id. Never merge, split, drop or move lines, even if a sentence spans several ids.
        - Keep names, terms and the way people address each other consistent across lines.
        - Write natural, concise subtitles. Keep the meaning; do not add explanations, notes or quotes around the text.
        - If a line is only a sound tag such as [Music] or (Laughter), translate the tag.
    """.trimIndent()

    private fun scriptRule(): String = when (LangUtil.normalize(targetLanguage)) {
        "zh-Hant" -> "\n" + """
            Write Taiwanese Mandarin (台灣用語) in Traditional Chinese characters. Never use Simplified characters,
            Hong Kong/Cantonese wording or Mainland China wording. Prefer the Taiwan term, e.g.
            影片 (not 視頻), 軟體 (not 軟件), 網路 (not 網絡), 資訊 (not 信息), 品質 (not 質量), 程式 (not 程序),
            計程車 (not 的士/出租車), 公車 (not 巴士/公交), 簡訊 (not 短信), 部落格 (not 博客), 冰淇淋 (not 雪糕),
            馬鈴薯 (not 土豆/薯仔), 鳳梨 (not 菠蘿), 滑鼠 (not 鼠標), 隨身碟 (not U盤), 早安 (not 早晨).
            Never use Cantonese words such as 嘅, 咗, 唔, 嘢, 啲, 冇.
        """.trimIndent().replace("\n", " ")
        "zh-Hans" -> "\nUse Simplified Chinese characters only."
        else -> ""
    }

    /**
     * Translates [lines], splitting the batch in half whenever the model output can't be used.
     * A single line that still fails comes back as null (the UI shows the original instead).
     */
    @Throws(FatalApiException::class, TransientException::class)
    fun translateWithSplit(lines: List<String>, context: List<String>): List<String?> {
        return try {
            translate(lines, context)
        } catch (e: BadOutputException) {
            if (lines.size == 1) {
                return listOf(null)
            }
            val mid = lines.size / 2
            val head = lines.subList(0, mid)
            val tail = lines.subList(mid, lines.size)
            translateWithSplit(head, context) + translateWithSplit(tail, (context + head).takeLast(CONTEXT_LINES))
        }
    }

    @Throws(FatalApiException::class, TransientException::class, BadOutputException::class)
    fun translate(lines: List<String>, context: List<String>): List<String> {
        val input = JsonObject().apply {
            add("context_only", context.toJsonArray())
            add("lines", JsonObject().also { obj -> lines.forEachIndexed { i, line -> obj.addProperty((i + 1).toString(), line) } })
        }
        val request = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", MAX_TOKENS)
            addProperty("system", systemPrompt)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", input.toString())
                })
            })
        }

        val response = postWithRetry(request.toString())

        return parseResponse(response.body, lines.size)
    }

    private fun postWithRetry(body: String): HttpResult {
        val headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to API_VERSION,
            "content-type" to "application/json"
        )
        var lastError = ""
        var retryAfterMs: Long? = null

        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                sleeper(retryAfterMs ?: (1_000L shl (attempt - 1))) // 1s, 2s, 4s, 8s
                retryAfterMs = null
            }

            val result = try {
                http.postJson(API_URL, headers, body)
            } catch (e: IOException) {
                lastError = e.toString()
                continue
            }

            when {
                result.isSuccessful -> return result
                result.code == 429 || result.code >= 500 -> { // 529 = overloaded
                    lastError = "HTTP ${result.code}: ${errorMessage(result.body)}"
                    retryAfterMs = result.retryAfterSec?.times(1_000)
                }
                else -> throw FatalApiException(result.code, "HTTP ${result.code}: ${errorMessage(result.body)}")
            }
        }

        throw TransientException(lastError)
    }

    private fun parseResponse(body: String, expectedSize: Int): List<String> {
        val text = try {
            val root = JsonParser().parse(body).asJsonObject
            if (root.get("stop_reason")?.asString == "max_tokens") {
                throw BadOutputException("Output truncated")
            }
            root.getAsJsonArray("content")
                .filter { it.asJsonObject.get("type")?.asString == "text" }
                .joinToString("") { it.asJsonObject.get("text").asString }
        } catch (e: BadOutputException) {
            throw e
        } catch (e: RuntimeException) {
            throw BadOutputException("Unexpected response: ${e.message}")
        }

        return parseLines(text, expectedSize)
    }

    /**
     * Extracts the {"id": "translation"} object from the model reply (tolerates code fences or stray text around it).
     * Every id must be there, so a shifted or partial answer is caught even when the count looks right.
     */
    internal fun parseLines(text: String, expectedSize: Int): List<String> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')

        if (start == -1 || end <= start) {
            throw BadOutputException("No JSON object in reply")
        }

        val obj = try {
            JsonParser().parse(text.substring(start, end + 1)).asJsonObject
        } catch (e: RuntimeException) {
            throw BadOutputException("Bad JSON object: ${e.message}")
        }

        if (obj.size() != expectedSize) {
            throw BadOutputException("Expected $expectedSize lines, got ${obj.size()}")
        }

        val lines = (1..expectedSize).map { id ->
            val value = obj.get(id.toString())
            if (value == null || !value.isJsonPrimitive) {
                throw BadOutputException("Missing line $id")
            }
            value.asString.trim()
        }

        if (LangUtil.normalize(targetLanguage) == "zh-Hant") {
            lines.firstOrNull { LangUtil.hasSimplifiedOnlyChars(it) }?.let {
                throw BadOutputException("Simplified Chinese in a Traditional translation: $it")
            }
            lines.firstOrNull { LangUtil.hasCantoneseChars(it) }?.let {
                throw BadOutputException("Cantonese in a Taiwanese translation: $it")
            }
        }

        return lines
    }

    private fun errorMessage(body: String): String {
        return try {
            JsonParser().parse(body).asJsonObject.getAsJsonObject("error").get("message").asString
        } catch (e: RuntimeException) {
            body.take(200)
        }
    }

    private fun List<String>.toJsonArray() = JsonArray().also { array -> forEach { array.add(it) } }
}
