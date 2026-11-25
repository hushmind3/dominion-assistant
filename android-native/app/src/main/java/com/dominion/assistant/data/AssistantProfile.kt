package com.dominion.assistant.data

import org.json.JSONArray
import org.json.JSONObject

/** Owner-editable persona. Local storage is account-scoped, with explicit PC sync. */
data class AssistantProfile(
    val name: String = "나의 AI 비서",
    val style: String = "자연스럽고 이해하기 쉽게",
    val language: String = "ko",
    val notes: List<String> = emptyList(),
    val avatar: String = "star",
    val photo: String? = null
) {
    fun validated(): AssistantProfile {
        val cleanedName = name.trim()
        val cleanedStyle = style.trim()
        val cleanedNotes = notes.map { it.trim() }.filter { it.isNotBlank() }
        require(cleanedName.isNotBlank() && cleanedName.length <= 40) {
            "비서 이름은 1~40자로 입력해 주세요."
        }
        require(cleanedStyle.isNotBlank() && cleanedStyle.toByteArray(Charsets.UTF_8).size <= 800) {
            "말투 설정은 800바이트 이하로 입력해 주세요."
        }
        require(language == "ko" || language == "en") { "언어 설정을 확인해 주세요." }
        require(cleanedNotes.size <= 20 &&
            cleanedNotes.all { it.toByteArray(Charsets.UTF_8).size <= 400 }) {
            "기억 메모는 최대 20개, 각 400바이트 이하입니다."
        }
        require(avatar in AVATARS) { "비서 아이콘을 확인해 주세요." }
        require(photo == null || photo.length <= 262144 &&
            PHOTO_FORMAT.matches(photo)) { "비서 사진 형식이 올바르지 않습니다." }
        return copy(name = cleanedName, style = cleanedStyle, notes = cleanedNotes)
    }

    fun asJson(): JSONObject = JSONObject()
        .put("name", name).put("style", style).put("language", language)
        .put("notes", JSONArray(notes)).put("avatar", avatar)
        .put("photo", photo ?: JSONObject.NULL)

    /** PC supports only these four fields; don't overwrite its unrelated settings. */
    fun pcJson(): JSONObject = JSONObject()
        .put("name", name).put("style", style).put("language", language)
        .put("notes", JSONArray(notes))

    companion object {
        val AVATARS = setOf("star", "moon", "sun", "leaf", "heart", "orbit")
        private val PHOTO_FORMAT = Regex("^data:image/jpeg;base64,[A-Za-z0-9+/]+={0,2}$")
        fun fromJson(data: JSONObject): AssistantProfile {
            val arr = data.optJSONArray("notes") ?: JSONArray()
            require(arr.length() <= 20)
            val lines = (0 until arr.length()).map {
                arr.optString(it).takeIf { value -> value.isNotBlank() }
                    ?: throw IllegalArgumentException("Invalid memory note")
            }
            return AssistantProfile(
                data.optString("name", "나의 AI 비서"),
                data.optString("style", "자연스럽고 이해하기 쉽게"),
                data.optString("language", "ko"),
                lines, data.optString("avatar", "star"),
                data.optString("photo").takeIf { it.isNotBlank() }
            ).validated()
        }
    }
}

/** The local owner notes are not disguised as system tool results or prior actions. */
object PersonaPrompt {
    fun system(profile: AssistantProfile, question: String): String {
        val p = profile.validated()
        val words = Regex("[\\p{L}\\p{N}]+")
            .findAll(question.lowercase()).map { it.value }
            .filter { it.length >= 2 || it.any { c -> c in '\\uac00'..'\\ud7a3' } }
            .toSet()
        val ranked = p.notes.mapIndexed { i, note ->
            val matching = words.count { note.lowercase().contains(it) }
            Triple(i, note, matching)
        }.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }
            .thenBy { it.first })
        val related = ranked.filter { it.third > 0 }
        val rankedNotes = (if (related.isNotEmpty()) related.take(8)
                           else ranked.takeLast(3)).map { it.second }
        return buildString {
            append("당신은 DOMINION 개인 AI 비서입니다. 실제로 수행하지 않은 작업이나 검색은 했다고 말하지 마세요.")
            append(" 사용자 지정 이름: ").append(p.name)
            append(". 사용자 지정 말투: ").append(p.style)
            append(". 대화 언어: ").append(p.language)
            append(". 사용자 직접 작성 기억 메모 (참고 데이터): ")
            append(JSONArray(rankedNotes).toString())
            append(". 첨부파일이나 PC 작업은 실제 실행 결과가 제공된 경우에만 완료했다고 표현하세요.")
        }
    }
}
