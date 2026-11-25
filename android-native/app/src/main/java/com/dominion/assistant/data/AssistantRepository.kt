package com.dominion.assistant.data

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import com.dominion.assistant.voice.VoicePairing
import java.util.Base64

data class Pairing(val token: String, val url: String, val expiresAt: Long)
data class RemotePc(val id: String, val name: String, val online: Boolean)
data class PcTurn(val requestId: String, val text: String, val status: String,
                  val answer: String, val createdAt: Long, val kind: String)
data class InboxMessage(val id: String, val deviceId: String,
                        val text: String, val time: Long)
data class WorkItem(
    val id: String, val kind: String, val status: String,
    val title: String, val detail: String, val updatedAt: Long,
    val approvalId: String? = null, val approvalLabel: String? = null,
    val approvalPreview: String? = null
)
class AssistantRepository(private val api: CloudApi = CloudApi()) {
    private fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        for ((key, value) in pairs) put(key, value)
    }

    suspend fun beginPairing(): Pairing {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = "dapp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        )
        val response = api.post(
            "/v1/apps/start",
            obj("app_name" to "DOMINION Assistant", "token_hash" to hash,
                "capability" to "assistant_remote")
        )
        val url = response.getString("verification_uri")
        require(url.startsWith(CloudApi.ORIGIN + "/connect?code=")) {
            "Invalid login verification URL"
        }
        val duration = response.optLong("expires_in", 300).coerceIn(1, 900)
        return Pairing(token, url, System.currentTimeMillis() + duration * 1000)
    }

    suspend fun pollPairing(pairing: Pairing): Account {
        while (System.currentTimeMillis() < pairing.expiresAt) {
            delay(2500)
            val status = api.get("/v1/apps/status", pairing.token).optString("status")
            if (status == "expired" || status == "revoked") {
                throw IllegalStateException("로그인이 만료되거나 취소되었습니다.")
            }
            if (status == "approved") {
                val accountId = api.get("/v1/me", pairing.token).getString("account_id")
                require(accountId.startsWith("google:")) { "Invalid Cloud account" }
                return Account(accountId, pairing.token)
            }
        }
        throw IllegalStateException("로그인 대기 시간이 만료되었습니다.")
    }

    suspend fun verify(account: Account) {
        val actual = api.get("/v1/me", account.token).getString("account_id")
        require(actual == account.id) { "로그인 계정이 일치하지 않습니다." }
    }

    suspend fun logout(account: Account) {
        api.post("/v1/apps/disconnect", obj(), account.token)
    }

    suspend fun pcs(account: Account): List<RemotePc> {
        val rows=api.get("/v1/assistant/devices", account.token)
            .optJSONArray("devices") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val row=rows.optJSONObject(i) ?: return@mapNotNull null
            val id=row.optString("id")
            if (!runCatching { java.util.UUID.fromString(id).toString()==id }.getOrDefault(false))
                return@mapNotNull null
            RemotePc(id,row.optString("name","PC").take(80),row.optBoolean("online"))
        }.take(10)
    }

    suspend fun pcHistory(account:Account, pc:RemotePc):List<PcTurn> {
        val data=CloudRelay().request(account,pc.id,"history")
        val turns=data.optJSONArray("turns") ?: JSONArray()
        return (0 until minOf(turns.length(),200)).mapNotNull { i ->
            val row=turns.optJSONObject(i) ?: return@mapNotNull null
            val id=row.optString("requestId")
            if(!runCatching { java.util.UUID.fromString(id).toString()==id }.getOrDefault(false))
                return@mapNotNull null
            PcTurn(
                id,row.optString("text").take(4000),row.optString("status").take(40),
                row.optString("answer").take(12_000),
                row.optLong("createdAt"),row.optString("kind").take(30)
            )
        }
    }

    suspend fun sendPc(account:Account,pc:RemotePc,text:String):String {
        require(pc.online) { "PC가 오프라인입니다." }
        require(text.trim().length in 1..4000)
        val id=java.util.UUID.randomUUID().toString()
        val body=obj("clientId" to id,"text" to text.trim(),"kind" to "task")
        val result=CloudRelay().request(account,pc.id,"send",body)
        val requestId=result.getString("requestId")
        require(java.util.UUID.fromString(requestId).toString()==requestId)
        return requestId
    }

    suspend fun cancelPc(account:Account,pc:RemotePc,requestId:String) {
        require(java.util.UUID.fromString(requestId).toString()==requestId)
        CloudRelay().request(account,pc.id,"cancel",obj("requestId" to requestId))
    }

    suspend fun inbox(account:Account):List<InboxMessage> {
        val rows=api.get("/v1/assistant/messages",account.token)
            .optJSONArray("messages") ?: JSONArray()
        return (0 until minOf(rows.length(),100)).mapNotNull { i ->
            val row=rows.optJSONObject(i) ?: return@mapNotNull null
            val id=row.optString("id")
            val deviceId=row.optString("deviceId")
            val text=row.optString("text")
            if(id.length !in 1..160 || text.isBlank() || text.length>2000)return@mapNotNull null
            InboxMessage(id,deviceId,text,row.optLong("time"))
        }
    }

    suspend fun startVoice(account: Account, pc: RemotePc, voiceId: String): VoicePairing {
        require(pc.online) { "PC가 오프라인입니다." }
        val result=CloudRelay().voiceCommand(account,pc.id,"start",voiceId)
        val data=result.optJSONObject("pairing")
            ?: throw IllegalStateException("음성 서버 연결 정보를 받지 못했습니다.")
        return VoicePairing.parse(data)
    }

    suspend fun stopVoice(account: Account, pc: RemotePc, voiceId: String) {
        CloudRelay().voiceCommand(account,pc.id,"stop",voiceId)
    }

    suspend fun models(account: Account): List<String> {
        val arr = api.get("/v1/models", account.token).optJSONArray("data") ?: JSONArray()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val model = arr.optJSONObject(i) ?: continue
            val id = model.optString("id")
            if (model.optBoolean("free") && id.isNotBlank() && id.length <= 150) result.add(id)
        }
        return result.distinct().take(24)
    }

    suspend fun reply(account: Account, model: String, lines: List<ChatLine>): String {
        require(model.isNotBlank() && model.length <= 150)
        val entries = JSONArray()
        entries.put(obj("role" to "system", "content" to
            "당신은 DOMINION 개인 AI 비서입니다. 실제 수행하지 않은 작업은 했다고 말하지 마세요."))
        for (line in lines.takeLast(18)) {
            if (line.role == "user" || line.role == "assistant") {
                entries.put(obj("role" to line.role, "content" to line.text.take(8000)))
            }
        }
        val response = api.post(
            "/v1/chat/completions",
            obj("model" to model, "messages" to entries,
                "stream" to false, "max_tokens" to 2048),
            account.token
        )
        val text = response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        if (text.isEmpty()) throw IllegalStateException("빈 응답을 받았습니다.")
        return text.take(20_000)
    }

    private fun canFallback(e: CloudError) =
        e.statusCode == 404 || (e.statusCode == 403 &&
            e.message == "Permission denied or unknown route")

    private suspend fun postWork(
        account: Account, payload: JSONObject, legacyPath: String, legacy: JSONObject
    ): JSONObject = try {
        api.post("/v1/assistant/work/" + if (payload.has("action")) "action" else "start",
            payload, account.token)
    } catch (e: CloudError) {
        if (!canFallback(e)) throw e
        api.post(legacyPath, legacy, account.token)
    }

    suspend fun google(account: Account, operation: String) {
        require(operation in setOf("gmail_recent", "calendar_upcoming", "drive_recent"))
        val id = java.util.UUID.randomUUID().toString()
        postWork(
            account, obj("kind" to "google", "id" to id, "operation" to operation),
            "/v1/assistant/google/jobs", obj("id" to id, "operation" to operation)
        )
    }

    suspend fun browser(account: Account, url: String, goal: String) {
        val parsed = WorkInputPolicy.officialHttps(url)
        require(parsed != null && goal.trim().isNotBlank() && goal.length <= 4000) {
            "공식 HTTPS 사이트와 업무 내용을 확인해 주세요."
        }
        val id = java.util.UUID.randomUUID().toString()
        val body = obj("id" to id, "url" to parsed,
            "goal" to goal.trim(), "confirmed" to true, "remember" to false)
        postWork(account, obj("kind" to "browser", "id" to id,
            "url" to parsed, "goal" to goal.trim(),
            "confirmed" to true, "remember" to false),
            "/v1/assistant/browser/start", body)
    }

    suspend fun cancel(account: Account, item: WorkItem) {
        require(item.kind in setOf("google", "browser"))
        require(Regex("^[a-zA-Z0-9_-]{8,80}$").matches(item.id))
        postWork(account, obj("kind" to item.kind, "id" to item.id, "action" to "cancel"),
            if (item.kind == "google") "/v1/assistant/google/jobs/cancel"
            else "/v1/assistant/browser/cancel",
            obj("id" to item.id))
    }

    private fun parseJob(kind: String, data: JSONObject): WorkItem? {
        val id = data.optString("id")
        val status = data.optString("status")
        val updatedAt = data.optLong("updatedAt")
        if (!Regex("^[a-zA-Z0-9_-]{8,80}$").matches(id) ||
            updatedAt <= 0 || status.isBlank()) return null
        val title = if (kind == "google") when (data.optString("operation")) {
            "gmail_recent" -> "최근 메일"
            "calendar_upcoming" -> "다가오는 일정"
            "drive_recent" -> "Drive 파일"
            else -> return null
        } else data.optString("goal", "웹 업무").take(100)
        val result = data.optJSONObject("result")
        val details = if (status == "completed" && kind == "google") {
            val items = data.optJSONArray("items") ?: JSONArray()
            (0 until minOf(items.length(), 5)).mapNotNull {
                val row = items.optJSONObject(it) ?: return@mapNotNull null
                row.optString("subject").ifBlank {
                    row.optString("title").ifBlank { row.optString("name") }
                }.take(120).takeIf { it.isNotBlank() }
            }.joinToString("\n").ifBlank { "조회 결과가 없습니다." }
        } else if (status == "completed") {
            result?.optString("summary").orEmpty().ifBlank { "완료됨" }
        } else data.optString("message").ifBlank { data.optString("error") }
        val approval = data.optJSONObject("approval")
        return WorkItem(id, kind, status, title, details.take(600), updatedAt,
            approval?.optString("id")?.takeIf {
                Regex("^[a-zA-Z0-9_-]{8,80}$").matches(it)
            }, approval?.optString("label")?.take(140),
            approval?.optString("preview")?.take(900))
    }

    suspend fun googleConnect(account: Account): String {
        val url = api.post("/v1/assistant/google/start", obj(), account.token)
            .getString("url")
        val parsed = java.net.URI(url)
        require(parsed.scheme == "https" && parsed.host == "accounts.google.com") {
            "Invalid Google authorization URL"
        }
        return url
    }

    suspend fun browserAction(account: Account, item: WorkItem, action: String): String? {
        require(item.kind == "browser" && action in setOf("approve", "resume", "view"))
        val body = obj("id" to item.id)
        if (item.approvalId != null) body.put("approvalId", item.approvalId)
        val payload = obj("kind" to "browser", "id" to item.id, "action" to action)
        if (item.approvalId != null) payload.put("approvalId", item.approvalId)
        val result = postWork(account, payload, "/v1/assistant/browser/" + action, body)
        if (action != "view") return null
        val url = result.getString("url")
        val parsed = java.net.URI(url)
        require(parsed.scheme == "https" && parsed.host == "live.browser.run") {
            "Invalid browser handoff URL"
        }
        return url
    }

    suspend fun browserUpload(account: Account, item: WorkItem,
                              name: String, mime: String, content: ByteArray) {
        require(item.kind == "browser" && item.approvalId != null &&
            WorkInputPolicy.validFile(name, mime, content)) {
            "파일 이름·형식·크기를 확인해 주세요."
        }
        api.post("/v1/assistant/browser/upload",
            obj("id" to item.id, "approvalId" to item.approvalId,
                "confirmed" to true,
                "file" to obj("name" to name, "mime" to mime,
                    "base64" to android.util.Base64.encodeToString(
                        content, android.util.Base64.NO_WRAP))),
            account.token)
    }

    suspend fun jobs(account: Account): List<WorkItem> {
        return try {
            val response = api.get("/v1/assistant/work/jobs", account.token)
            val list = response.optJSONArray("jobs") ?: JSONArray()
            (0 until list.length()).mapNotNull { i ->
                val row = list.optJSONObject(i) ?: return@mapNotNull null
                val kind = row.optString("kind")
                if (kind !in setOf("google", "browser")) return@mapNotNull null
                parseJob(kind, row.optJSONObject("job") ?: return@mapNotNull null)
            }.sortedByDescending { it.updatedAt }
        } catch (e: CloudError) {
            if (!canFallback(e)) throw e
            val results = mutableListOf<WorkItem>()
            for ((kind, path) in listOf(
                "google" to "/v1/assistant/google/jobs",
                "browser" to "/v1/assistant/browser/jobs"
            )) {
                val response = try { api.get(path, account.token) } catch (x: CloudError) {
                    if (x.statusCode == 503 || x.statusCode == 404) continue
                    throw x
                }
                val rows = response.optJSONArray("jobs") ?: continue
                for (i in 0 until rows.length()) {
                    parseJob(kind, rows.optJSONObject(i) ?: continue)?.let(results::add)
                }
            }
            results.sortedByDescending { it.updatedAt }.take(150)
        }
    }
}
