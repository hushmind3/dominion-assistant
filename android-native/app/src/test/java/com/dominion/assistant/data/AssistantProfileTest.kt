package com.dominion.assistant.data

import org.junit.Assert.*
import org.junit.Test

class AssistantProfileTest {
    @Test fun profileRoundTrips() {
        val original=AssistantProfile(name="도미니온",style="간결하게",language="ko",
            notes=listOf("근거를 제시하세요","대화는 한국어"),avatar="moon").validated()
        assertEquals(original,AssistantProfile.fromJson(original.asJson()))
        assertFalse(original.pcJson().has("avatar"))
    }
    @Test fun rejectsOversizedMemoryAndInvalidLanguage() {
        assertThrows(IllegalArgumentException::class.java){
            AssistantProfile(notes=List(21){"기억"}).validated()
        }
        assertThrows(IllegalArgumentException::class.java){
            AssistantProfile(notes=listOf("한".repeat(135))).validated()
        }
        assertThrows(IllegalArgumentException::class.java){
            AssistantProfile(language="ar").validated()
        }
    }
    @Test fun systemPromptIncludesOnlyEightTopicalMemoryNotes() {
        val notes=listOf("그림 그리기", "앱 개발", "이전 일정",
            "앱 빌드", "커피 주문", "앱 기능", "음식",
            "PC 모니터", "앱 연결", "메시지", "디자인", "요금제")
        val text=PersonaPrompt.system(AssistantProfile(notes=notes),"앱 빌드 오류")
        assertTrue(text.contains("앱 빌드"))
        assertTrue(text.contains("앱 연결"))
        assertTrue(text.contains("참고 데이터"))
        assertFalse(text.contains("음식"))
    }
    @Test fun rejectTamperedAvatarAndBlankName() {
        assertThrows(IllegalArgumentException::class.java){
            AssistantProfile(name=" ").validated()
        }
        assertThrows(IllegalArgumentException::class.java){
            AssistantProfile(avatar="http://bad").validated()
        }
    }
}
