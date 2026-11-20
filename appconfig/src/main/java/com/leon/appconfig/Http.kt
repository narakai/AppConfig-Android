package com.leon.appconfig

import java.net.HttpURLConnection
import java.net.URL

/** Tiny HTTP helper so the library adds no networking dependency. Call off the main thread. */
internal object Http {
    private const val TIMEOUT_MS = 8_000

    class Response(val code: Int, val body: String) {
        val ok get() = code in 200..299
    }

    fun get(url: String): Response = request("GET", url, null)

    fun post(url: String, json: String): Response = request("POST", url, json)

    private fun request(method: String, url: String, json: String?): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("accept", "application/json")
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
