package com.dominion.assistant.data

import org.json.JSONObject

/**
 * A model suggests an action; it NEVER executes a tool by returning JSON.
 * Parsing is strict and the existing Cloud action/approval gates stay authoritative.
 */
sealed class AssistantIntent {
    data class Browser(val url: String, val goal: String) : AssistantIntent()
    data class Google(val operation: String) : AssistantIntent()
    data class Search(val query: String) : AssistantIntent()
    data class Ask(val question: String) : AssistantIntent()
    data class MailWatch(val mode: String, val sender: String, val keyword: String) : AssistantIntent()
}

/** A persisted, owner-scoped, explicitly approved browser/Google proposal. */
data class PreparedErrand(
    val id: String,
    val intent: AssistantIntent,
    val createdAt: Long,
    val state: String = "ready"
) {
    val title: String get() = when (intent) {
        is AssistantIntent.Browser -> "웹사이트에서 심부름"
        is AssistantIntent.Google -> when (intent.operation) {
            "gmail_recent" -> "최근 메일 찾아보기"
            "calendar_upcoming" -> "다가오는 일정 확인"
            else -> "Drive 파일 찾아보기"
        }
        is AssistantIntent.MailWatch -> "메일 도착 알림 설정"
        else -> "심부름"
    }
    val summary: String get() = when (intent) {
        is AssistantIntent.Browser -> intent.url + "\n" + intent.goal
        is AssistantIntent.Google -> title
        is AssistantIntent.MailWatch -> listOfNotNull(
            when (intent.mode) {
                "all" -> "모든 새 메일"
                "important" -> "중요 메일"
                else -> "조건에 맞는 메일"
            },
            intent.sender.takeIf { it.isNotBlank() }?.let { "보낸 사람: " + it },
            intent.keyword.takeIf { it.isNotBlank() }?.let { "제목: " + it }
        ).joinToString("\n")
        else -> title
    }
}

object AssistantTaskIntent {
    val systemGuide: String = """
        사용자가 실제 행동을 요청하면, 평문 대신 아래 JSON 한 개만 응답하세요.
        웹사이트 검색과 사실 확인이 필요한 경우: {"action":"web_search","query":"검색할 구체적인 질문"}
        실제 사이트에서 신청/작성/조회/가입 등 브라우저 조작: {"action":"browser_task","url":"https://공식사이트","goal":"사용자 목표"}
        공식 HTTPS 주소가 대화에 없으면 사이트 주소를 지어내지 말고 web_search를 선택하세요.
        사용자의 Google 계정에 있는 최근 메일/일정/Drive 목록을 읽는 요청은:
        {"action":"gmail_recent"} 또는 {"action":"calendar_upcoming"} 또는 {"action":"drive_recent"}
        새 메일을 계속 감시해 달라는 요청은:
        {"action":"mail_watch","mode":"all 또는 important 또는 matched","sender":"발신자 조건 또는 빈 문자열","keyword":"제목 조건 또는 빈 문자열"}
        필요한 정보가 부족해 진행할 수 없으면 {"action":"ask_user","question":"핵심 질문 한 개"}.
        PC 조작, 결제, 본인인증, 최종 제출은 실행했다고 주장하지 마세요.
        사용자가 심부름을 요청한 대화 자체가 시작 요청입니다. 별도의 시작/부탁하기 버튼을 요구하지 마세요.
        탐색·조회·초안 작성은 가능한 범위에서 바로 진행하고 인증, 최종 제출, 결제 등 사용자 개입이 필요한 순간만 알리세요.
        위 JSON은 실행 의도이며 실제 도구 실행과 중요 승인 단계는 애플리케이션이 관리합니다.
        일반 대화, 조언, 창작, 코드 질문에는 JSON 없이 자연스러운 답변을 하세요.
        """.trimIndent()

    /** Returns null for ordinary prose OR malformed/unknown action JSON. */
    fun parse(raw: String): AssistantIntent? {
        val text = raw.trim()
        if (text.length !in 2..5000 || !text.startsWith("{") || !text.endsWith("}")) {
            return null
        }
        val data = try { JSONObject(text) } catch (_: Exception) { return null }
        return when (data.optString("action")) {
            "browser_task" -> {
                if (!onlyKeys(data, setOf("action", "url", "goal"))) return null
                val url = WorkInputPolicy.officialHttps(data.optString("url")) ?: return null
                val goal = data.optString("goal").trim()
                if (goal.length !in 3..4000) return null
                AssistantIntent.Browser(url, goal)
            }
            "gmail_recent", "calendar_upcoming", "drive_recent" -> {
                if (!onlyKeys(data, setOf("action"))) return null
                AssistantIntent.Google(data.getString("action"))
            }
            "web_search" -> {
                if (!onlyKeys(data, setOf("action", "query"))) return null
                val query = data.optString("query").trim()
                if (query.length !in 2..512) return null
                AssistantIntent.Search(query)
            }
            "mail_watch" -> {
                if (!onlyKeys(data, setOf("action", "mode", "sender", "keyword"))) return null
                val mode = data.optString("mode")
                val sender = data.optString("sender").trim()
                val keyword = data.optString("keyword").trim()
                if (mode !in setOf("all", "important", "matched") ||
                    sender.length > 160 || keyword.length > 100 ||
                    (mode == "matched" && sender.isBlank() && keyword.isBlank())) return null
                AssistantIntent.MailWatch(mode, sender, keyword)
            }
            "ask_user" -> {
                if (!onlyKeys(data, setOf("action", "question"))) return null
                val question = data.optString("question").trim()
                if (question.length !in 3..500) return null
                AssistantIntent.Ask(question)
            }
            else -> null
        }
    }

    /** Prevent exposing raw tool JSON in the visible conversation. */
    fun looksLikeAction(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.length !in 2..5000 || !trimmed.startsWith("{")) return false
        return try { JSONObject(trimmed).has("action") } catch (_: Exception) { false }
    }

    fun persistable(intent: AssistantIntent) =
        intent is AssistantIntent.Browser || intent is AssistantIntent.Google ||
            intent is AssistantIntent.MailWatch

    fun encode(intent: AssistantIntent): String {
        val data = JSONObject()
        when (intent) {
            is AssistantIntent.Browser ->
                data.put("action", "browser_task").put("url", intent.url).put("goal", intent.goal)
            is AssistantIntent.Google -> data.put("action", intent.operation)
            is AssistantIntent.Search -> data.put("action", "web_search").put("query", intent.query)
            is AssistantIntent.Ask -> data.put("action", "ask_user").put("question", intent.question)
            is AssistantIntent.MailWatch -> data.put("action", "mail_watch")
                .put("mode", intent.mode).put("sender", intent.sender).put("keyword", intent.keyword)
        }
        return data.toString()
    }

    private fun onlyKeys(obj: JSONObject, allowed: Set<String>): Boolean {
        val keys = obj.keys()
        while (keys.hasNext()) if (keys.next() !in allowed) return false
        return true
    }
}
