package com.liskovsoft.smartyoutubetv2.common.dualsub

import java.io.IOException

class HttpResult(val code: Int, val body: String, val retryAfterSec: Long? = null) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Minimal HTTP abstraction so the dualsub logic can be tested without Android or a network.
 */
interface HttpTransport {
    @Throws(IOException::class)
    fun get(url: String): HttpResult

    @Throws(IOException::class)
    fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult
}
