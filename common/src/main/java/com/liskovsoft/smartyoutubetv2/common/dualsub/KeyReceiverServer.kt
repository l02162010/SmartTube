package com.liskovsoft.smartyoutubetv2.common.dualsub

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Tiny one-shot LAN web page that lets a phone paste the API key (opened from a QR code).
 *
 * - Every request needs the random one-time [token] from the QR code
 * - Stops after the first accepted key, on [stop] or after [timeoutMs]
 * - Plain HTTP: fine on a home Wi-Fi for the few seconds it runs
 */
class KeyReceiverServer(
    private val onKey: (String) -> Unit,
    private val timeoutMs: Long = 5 * 60_000L,
    private val port: Int = 0
) {
    companion object {
        private const val MAX_REQUEST_BYTES = 8 * 1024
        private const val MAX_KEY_LENGTH = 512
    }

    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile
    var isRunning = false
        private set

    private var socket: ServerSocket? = null

    /** Returns the bound port */
    fun start(): Int {
        val server = ServerSocket(port)
        server.soTimeout = 1_000
        socket = server
        isRunning = true

        Thread({ loop(server, System.currentTimeMillis() + timeoutMs) }, "DualSubKeyServer").apply {
            isDaemon = true
            start()
        }

        return server.localPort
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (e: Exception) {
            // already closed
        }
    }

    private fun loop(server: ServerSocket, deadline: Long) {
        try {
            while (isRunning && System.currentTimeMillis() < deadline) {
                val client = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                }
                client.use { handle(it) }
            }
        } catch (e: Exception) {
            // closed by stop()
        } finally {
            stop()
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val request = try {
            readRequest(BufferedInputStream(client.getInputStream()))
        } catch (e: Exception) {
            null
        }

        val response = when {
            request == null -> respond(400, "Bad request")
            request.path.substringBefore('?') != "/" -> respond(404, "Not found")
            request.method == "GET" && query(request.path)["t"] == token -> respond(200, formPage())
            request.method == "POST" -> {
                val form = parseForm(request.body)
                val key = form["key"]?.trim().orEmpty()
                when {
                    form["t"] != token -> respond(403, page("連結已失效", "請在電視上重新開啟 QR code。"))
                    key.isEmpty() || key.length > MAX_KEY_LENGTH || key.any { it.isWhitespace() } ->
                        respond(400, page("格式不對", "請貼上完整的 API key（不含空白）。<p><a href=\"/?t=$token\">返回</a></p>"))
                    else -> {
                        onKey(key)
                        isRunning = false // one shot
                        respond(200, page("已更新 ✓", "電視上的 API key 已經換成 ••••${escape(key.takeLast(4))}，可以關閉這個頁面了。"))
                    }
                }
            }
            else -> respond(403, page("連結已失效", "請在電視上重新開啟 QR code。"))
        }

        client.getOutputStream().apply {
            write(response)
            flush()
        }
    }

    private class Request(val method: String, val path: String, val body: String)

    private fun readRequest(input: InputStream): Request {
        val head = ByteArrayOutputStream()
        // Read until the blank line that ends the headers
        while (!head.toByteArray().endsWith("\r\n\r\n")) {
            val b = input.read()
            require(b != -1 && head.size() < MAX_REQUEST_BYTES)
            head.write(b)
        }

        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val (method, path) = lines[0].split(" ").let { it[0] to it[1] }
        val length = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        require(length in 0..MAX_REQUEST_BYTES)

        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            require(n != -1)
            read += n
        }

        return Request(method, path, String(body, Charsets.UTF_8))
    }

    private fun ByteArray.endsWith(suffix: String): Boolean {
        val s = suffix.toByteArray()
        return size >= s.size && (s.indices).all { this[size - s.size + it] == s[it] }
    }

    private fun query(path: String): Map<String, String> = parseForm(path.substringAfter('?', ""))

    private fun parseForm(text: String): Map<String, String> {
        return text.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }

    private fun respond(code: Int, body: String): ByteArray {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + bytes
    }

    private fun formPage() = page("設定 Claude API key", """
        <form method="post" action="/">
          <input type="hidden" name="t" value="$token">
          <input name="key" type="password" placeholder="sk-ant-..." autocomplete="off" autocapitalize="off" spellcheck="false" required autofocus>
          <label><input type="checkbox" onclick="this.form.key.type=this.checked?'text':'password'"> 顯示</label>
          <button type="submit">送到電視</button>
        </form>
        <p class="note">Key 只會傳到同一個 Wi-Fi 裡的電視，不會經過其他伺服器。</p>
    """.trimIndent())

    private fun page(title: String, content: String) = """
        <!doctype html><html lang="zh-Hant"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>SmartTube 雙語字幕</title>
        <style>
          body{font-family:system-ui,sans-serif;margin:0;padding:24px 16px;background:#111;color:#eee}
          main{max-width:480px;margin:auto} h1{font-size:22px}
          input[name=key]{box-sizing:border-box;width:100%;font-size:16px;padding:12px;border-radius:8px;border:1px solid #555;background:#222;color:#eee}
          label{display:block;margin:12px 0;color:#aaa}
          button{width:100%;font-size:17px;padding:14px;border:0;border-radius:8px;background:#e53935;color:#fff}
          .note{color:#888;font-size:13px} a{color:#8ab4f8}
        </style></head>
        <body><main><h1>$title</h1>$content</main></body></html>
    """.trimIndent()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
