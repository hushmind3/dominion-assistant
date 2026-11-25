package com.dominion.assistant.data

import java.net.URI

/** Pure input policy used by the native client; the Cloud repeats its own checks. */
object WorkInputPolicy {
    private val privateSuffixes = setOf(
        "localhost", "local", "internal", "test", "invalid", "onion", "lan", "home", "arpa"
    )
    fun officialHttps(value: String): String? {
        if (value.length !in 9..2048) return null
        return try {
            val uri = URI(value.trim())
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 ||
                uri.host!!.endsWith('.') || host.contains(':') || '.' !in host ||
                host.all { it.isDigit() || it == '.' } ||
                host.split('.').any { it.isBlank() } ||
                privateSuffixes.any { host == it || host.endsWith(".$it") }) null
            else uri.toASCIIString()
        } catch (_: Exception) {
            null
        }
    }

    fun validFile(name: String, mime: String, bytes: ByteArray): Boolean {
        if (name.length !in 1..120 || name.any { it == '\\' || it == '/' || it.isISOControl() } ||
            bytes.size !in 8..2 * 1024 * 1024) return false
        return when (mime) {
            "application/pdf" -> name.endsWith(".pdf", true) &&
                String(bytes.copyOfRange(0, 5), Charsets.US_ASCII) == "%PDF-"
            "image/jpeg" -> name.matches(Regex(".*\\.jpe?g", RegexOption.IGNORE_CASE)) &&
                bytes[0].toInt() and 0xff == 0xff &&
                bytes[1].toInt() and 0xff == 0xd8 &&
                bytes[2].toInt() and 0xff == 0xff
            "image/png" -> name.endsWith(".png", true) &&
                bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10)
                    .map { it.toByte() }
            else -> false
        }
    }
}
