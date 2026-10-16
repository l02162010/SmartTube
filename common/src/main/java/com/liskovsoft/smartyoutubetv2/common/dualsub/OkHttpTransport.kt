package com.liskovsoft.smartyoutubetv2.common.dualsub

import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Reuses SmartTube's shared client, so the user's proxy and DNS settings apply here too.
 */
class OkHttpTransport : HttpTransport {
    private val client: OkHttpClient by lazy {
        OkHttpManager.instance().client.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS) // translation of a big batch may take a while
            .build()
    }

    override fun get(url: String): HttpResult {
        return execute(Request.Builder().url(url).get().build())
    }

    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult {
        val builder = Request.Builder().url(url).post(RequestBody.create(JSON, body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return execute(builder.build())
    }

    private fun execute(request: Request): HttpResult {
        client.newCall(request).execute().use { response: Response ->
            return HttpResult(
                response.code(),
                response.body()?.string() ?: "",
                response.header("retry-after")?.toLongOrNull()
            )
        }
    }

    private companion object {
        val JSON: MediaType? = MediaType.parse("application/json; charset=utf-8")
    }
}
