package com.dominion.assistant.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

data class PcAttachment(val name: String, val mime: String, val bytes: ByteArray) {
    fun asBody(focus: String): JSONObject {
        require(PcAttachmentPolicy.valid(name, mime, bytes))
        require(focus.toByteArray(Charsets.UTF_8).size <= 6000)
        return JSONObject().put("name", name).put("mime", mime)
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("focus", focus)
    }
}

/** Cloud RPC limits messages to 850KB; 512KiB raw fits with Base64 and JSON. */
object PcAttachmentPolicy {
    const val MAX_RELAY_BYTES = 512 * 1024
    private const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    private val textExtensions = setOf(
        "txt", "md", "csv", "json", "log", "py", "rs", "js", "ts", "yaml", "yml"
    )
    fun valid(name: String, mime: String, bytes: ByteArray): Boolean {
        if (name.isBlank() || name.length > 160 ||
            name.any { it == '/' || it == '\\' || it.isISOControl() } ||
            bytes.size !in 1..MAX_RELAY_BYTES || mime.length !in 1..120) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        val start = bytes.take(12).map { it.toInt() and 0xff }
        return when {
            mime == "image/jpeg" && ext in setOf("jpg", "jpeg") ->
                start.take(3) == listOf(255,216,255)
            mime == "image/png" && ext == "png" ->
                start.take(8) == listOf(137,80,78,71,13,10,26,10)
            mime == "image/webp" && ext == "webp" ->
                bytes.size >= 12 && String(bytes.copyOfRange(0, 4)) == "RIFF" &&
                    String(bytes.copyOfRange(8, 12)) == "WEBP"
            ext == "pdf" && mime == "application/pdf" ->
                bytes.size >= 5 && String(bytes.copyOfRange(0,5)) == "%PDF-"
            ext == "docx" && mime ==
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                start.take(4) == listOf(80,75,3,4)
            ext in textExtensions -> try {
                val decoded = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                !decoded.contains('\u0000')
            } catch (_: CharacterCodingException) { false }
            else -> false
        }
    }

    suspend fun read(context: Context, uri: Uri): PcAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        val suggested = name?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("선택한 파일 이름을 확인할 수 없습니다.")
        require(suggested.length <= 160 && suggested.none {
            it == '/' || it == '\\' || it.isISOControl()
        }) { "지원하지 않는 파일 이름입니다." }
        val ext = suggested.substringAfterLast('.', "").lowercase()
        var mime = resolver.getType(uri) ?: "application/octet-stream"
        val isImage = mime in setOf("image/jpeg","image/png","image/webp") ||
            ext in setOf("jpg","jpeg","png","webp")
        val buffer = ByteArrayOutputStream()
        val input = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("선택한 파일을 읽을 수 없습니다.")
        input.use {
            val chunk = ByteArray(8192)
            val limit = if(isImage) MAX_SOURCE_BYTES else MAX_RELAY_BYTES
            while(true) {
                val n = it.read(chunk)
                if(n < 0) break
                if(buffer.size() + n > limit) {
                    throw IllegalArgumentException(
                        if(isImage) "이미지 크기가 16MB를 초과합니다."
                        else "PC 문서 첨부는 현재 512KB 이하로 지원합니다."
                    )
                }
                buffer.write(chunk,0,n)
            }
        }
        var bytes = buffer.toByteArray()
        var finalName = suggested
        if(isImage && bytes.size > MAX_RELAY_BYTES) {
            // Compress on-device before transferring to the owner PC.
            val bounds = BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000) {
                "이미지를 읽을 수 없습니다."
            }
            val longest = maxOf(bounds.outWidth,bounds.outHeight)
            var sample=1
            while(longest / sample > 1600)sample*=2
            val options=BitmapFactory.Options().apply{inSampleSize=sample}
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                ?: throw IllegalArgumentException("이미지를 변환할 수 없습니다.")
            try{
                var candidate=ByteArray(0)
                for(quality in listOf(85,72,60,45)) {
                    val out=ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out)
                    candidate=out.toByteArray()
                    if(candidate.size<=MAX_RELAY_BYTES) break
                }
                require(candidate.size<=MAX_RELAY_BYTES) {
                    "이미지를 512KB 이하로 줄일 수 없습니다."
                }
                bytes=candidate
                finalName=suggested.substringBeforeLast('.')+".jpg"
                mime="image/jpeg"
            }finally{bitmap.recycle()}
        } else if(isImage && mime=="application/octet-stream") {
            mime=when(ext) {
                "jpg","jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> mime
            }
        }
        if(mime=="application/octet-stream") {
            mime=when(ext) {
                "pdf" -> "application/pdf"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "json" -> "application/json"
                else -> mime
            }
        }
        PcAttachment(finalName,mime,bytes).also {
            require(valid(it.name,it.mime,it.bytes)) {
                "PC가 지원하는 사진·PDF·DOCX·텍스트 파일인지 확인해 주세요."
            }
        }
    }
}
