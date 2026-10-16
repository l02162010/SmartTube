package com.liskovsoft.smartyoutubetv2.common.dualsub

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Fake Claude API: answers with "<line>-tr" for every input line unless [respond] says otherwise.
 */
class FakeClaude(private val respond: (FakeClaude, List<String>) -> HttpResult? = { _, _ -> null }) : HttpTransport {
    val requests = ArrayList<List<String>>()
    val contexts = ArrayList<List<String>>()
    val headers = ArrayList<Map<String, String>>()
    var subtitleBody = ""

    override fun get(url: String): HttpResult = HttpResult(200, subtitleBody)

    @Synchronized
    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult {
        val request = JsonParser().parse(body).asJsonObject
        val input = JsonParser().parse(request.getAsJsonArray("messages")[0].asJsonObject.get("content").asString).asJsonObject
        val linesObj = input.getAsJsonObject("lines")
        val lines = (1..linesObj.size()).map { linesObj.get(it.toString()).asString }
        requests.add(lines)
        contexts.add(input.getAsJsonArray("context_only").map { it.asString })
        this.headers.add(headers)

        return respond(this, lines) ?: ok(lines.map { "$it-tr" })
    }

    companion object {
        fun ok(lines: List<String>): HttpResult {
            val obj = JsonObject().also { o -> lines.forEachIndexed { i, line -> o.addProperty((i + 1).toString(), line) } }
            return okText(obj.toString())
        }

        fun okText(text: String): HttpResult {
            val content = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                })
            }
            return HttpResult(200, JsonObject().apply {
                add("content", content)
                addProperty("stop_reason", "end_turn")
            }.toString())
        }
    }
}

class ClaudeTranslatorTest {
    private val sleeps = ArrayList<Long>()

    private fun translator(http: HttpTransport) =
        ClaudeTranslator(http, "sk-test", ClaudeTranslator.MODEL_HAIKU, "zh-Hant") { sleeps.add(it) }

    @Test
    fun translatesBatchAndSendsHeaders() {
        val http = FakeClaude()

        val result = translator(http).translateWithSplit(listOf("a", "b"), listOf("ctx"))

        assertEquals(listOf("a-tr", "b-tr"), result)
        assertEquals(listOf("ctx"), http.contexts[0])
        assertEquals("sk-test", http.headers[0]["x-api-key"])
        assertEquals("2023-06-01", http.headers[0]["anthropic-version"])
    }

    @Test
    fun toleratesCodeFence() {
        val http = FakeClaude { _, _ -> FakeClaude.okText("```json\n{\"1\": \"x\"}\n```") }

        assertEquals(listOf("x"), translator(http).translateWithSplit(listOf("a"), emptyList()))
    }

    @Test
    fun wrongLineCountSplitsBatchDownToSingleLines() {
        // Only single line requests succeed, except line "c" which always comes back broken
        val http = FakeClaude { _, lines ->
            when {
                lines.size > 1 -> FakeClaude.ok(listOf("merged"))
                lines[0] == "c" -> FakeClaude.okText("sorry")
                else -> null
            }
        }

        val result = translator(http).translateWithSplit(listOf("a", "b", "c", "d"), emptyList())

        assertEquals(listOf("a-tr", "b-tr", null, "d-tr"), result)
        assertEquals(listOf(listOf("a", "b", "c", "d"), listOf("a", "b"), listOf("a"), listOf("b"), listOf("c", "d"), listOf("c"), listOf("d")), http.requests)
        // The second half gets the first half as context
        assertEquals(listOf("a", "b"), http.contexts[4])
    }

    @Test
    fun shiftedOutputIsRejectedEvenWithRightCount() {
        // The model translated a context line as id 1 and dropped the real line 2
        val translator = translator(FakeClaude())

        try {
            translator.parseLines("""{"1": "ctx-tr", "3": "b-tr"}""", 2)
            fail()
        } catch (e: BadOutputException) {
            assertTrue(e.message!!.contains("Missing line 2"))
        }
    }

    @Test
    fun simplifiedChineseIsRejectedForTraditionalTarget() {
        val translator = translator(FakeClaude())

        assertEquals(listOf("為了大局著想"), translator.parseLines("""{"1": "為了大局著想"}""", 1))
        try {
            translator.parseLines("""{"1": "为了大局着想"}""", 1)
            fail()
        } catch (e: BadOutputException) {
            assertTrue(e.message!!.contains("Simplified"))
        }

        // Fine when Simplified is the target
        val hans = ClaudeTranslator(FakeClaude(), "k", ClaudeTranslator.MODEL_HAIKU, "zh-Hans")
        assertEquals(listOf("为了大局着想"), hans.parseLines("""{"1": "为了大局着想"}""", 1))
    }

    @Test
    fun retriesOverloadAndRateLimit() {
        var calls = 0
        val http = FakeClaude { _, _ ->
            calls++
            when (calls) {
                1 -> HttpResult(529, """{"error":{"message":"Overloaded"}}""")
                2 -> HttpResult(429, "{}", retryAfterSec = 7)
                else -> null
            }
        }

        assertEquals(listOf("a-tr"), translator(http).translate(listOf("a"), emptyList()))
        assertEquals(listOf(1_000L, 7_000L), sleeps)
    }

    @Test
    fun givesUpAfterMaxRetries() {
        val http = FakeClaude { _, _ -> throw IOException("offline") }

        try {
            translator(http).translate(listOf("a"), emptyList())
            fail()
        } catch (e: TransientException) {
            assertEquals(ClaudeTranslator.MAX_RETRIES + 1, http.requests.size)
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), sleeps)
        }
    }

    @Test
    fun authErrorStopsImmediately() {
        val http = FakeClaude { _, _ -> HttpResult(401, """{"error":{"message":"invalid x-api-key"}}""") }

        try {
            translator(http).translateWithSplit(listOf("a", "b"), emptyList())
            fail()
        } catch (e: FatalApiException) {
            assertTrue(e.isAuthError)
            assertTrue(e.message!!.contains("invalid x-api-key"))
            assertEquals(1, http.requests.size)
        }
    }
}
