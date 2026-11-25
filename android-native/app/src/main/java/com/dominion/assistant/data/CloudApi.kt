package com.dominion.assistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class CloudError(val statusCode: Int, message: String) : Exception(message)

/** One trusted Cloud origin. Provider keys and arbitrary endpoint URLs never enter the app. */
class CloudApi {
    companion object {
        const val ORIGIN = "https://dominion-cloud.dominion-hushmind.workers.dev"
    }

    suspend fun get(path: String, token: String? = null): JSONObject = call(path, token, null)
    suspend fun post(path: String, body: JSONObject, token: String? = null): JSONObject =
        call(path, token, body)

    private suspend fun call(path: String, token: String?, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            require(path.startsWith("/") && !path.startsWith("//") && !path.contains("://")) {
                "Invalid Cloud path"
            }
            val connection = URL(ORIGIN + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 90_000
                connection.setRequestProperty("Accept", "application/json")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use {
                        it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                // PDF retrieval returns up to 2 MiB binary as base64 JSON.
                val maxChars = if (path == "/v1/assistant/browser/document")
                    3_200_000 else 2_000_000
                val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                    val buffer = CharArray(8192)
                    val result = StringBuilder()
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (result.length + count > maxChars)
                            throw CloudError(502, "Cloud response is too large")
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                } ?: "{}"
                val data = try { JSONObject(raw) } catch (_: Exception) {
                    throw CloudError(502, "Cloud returned invalid JSON")
                }
                if (code !in 200..299) {
                    throw CloudError(code, data.optString("error", "Cloud request failed").take(250))
                }
                data
            } finally {
                connection.disconnect()
            }
        }
}
