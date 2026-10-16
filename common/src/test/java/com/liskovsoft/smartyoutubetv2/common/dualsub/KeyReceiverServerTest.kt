package com.liskovsoft.smartyoutubetv2.common.dualsub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class KeyReceiverServerTest {
    @Volatile
    private var received: String? = null
    private val server = KeyReceiverServer(onKey = { received = it })
    private val port = server.start()

    @After
    fun tearDown() = server.stop()

    private fun request(path: String, form: String? = null): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        if (form != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(form.toByteArray()) }
        }
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream).bufferedReader().readText()
        return code to body
    }

    private fun form(token: String, key: String) = "t=${URLEncoder.encode(token, "UTF-8")}&key=${URLEncoder.encode(key, "UTF-8")}"

    @Test
    fun pageNeedsToken() {
        assertEquals(403, request("/").first)
        assertEquals(403, request("/?t=wrong").first)
        assertEquals(404, request("/favicon.ico").first)

        val (code, body) = request("/?t=${server.token}")
        assertEquals(200, code)
        assertTrue(body.contains("name=\"t\" value=\"${server.token}\""))
    }

    @Test
    fun postWithWrongTokenIsRejected() {
        assertEquals(403, request("/", form("wrong", "sk-ant-123")).first)
        assertNull(received)
        assertTrue(server.isRunning)
    }

    @Test
    fun badKeyIsRejected() {
        assertEquals(400, request("/", form(server.token, "sk-ant 123")).first)
        assertEquals(400, request("/", form(server.token, "")).first)
        assertNull(received)
    }

    @Test
    fun keyIsSavedOnceThenServerStops() {
        val (code, body) = request("/", form(server.token, "  sk-ant-api03-abcd1234  "))

        assertEquals(200, code)
        assertEquals("sk-ant-api03-abcd1234", received)
        assertTrue(body.contains("••••1234"))
        assertFalse(body.contains("sk-ant-api03")) // never echo the full key

        // One shot: the server is gone
        val deadline = System.currentTimeMillis() + 3_000
        while (server.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(server.isRunning)
        assertTrue(runCatching { request("/?t=${server.token}") }.isFailure)
    }

    @Test
    fun tokenIsRandom() {
        assertEquals(32, server.token.length)
        assertTrue(server.token != KeyReceiverServer(onKey = {}).token)
    }
}
