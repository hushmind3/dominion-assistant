package com.dominion.assistant.data

import org.junit.Assert.*
import org.junit.Test

class AssistantTaskIntentTest {
    @Test fun acceptsKnownBrowserWithValidHttps() {
        val raw = """{"action":"browser_task","url":"https://www.hometax.go.kr/","goal":"사업자등록 신청서 작성"}"""
        val parsed = AssistantTaskIntent.parse(raw)
        assertTrue(parsed is AssistantIntent.Browser)
        assertEquals("https://www.hometax.go.kr/", (parsed as AssistantIntent.Browser).url)
        assertEquals(parsed, AssistantTaskIntent.parse(AssistantTaskIntent.encode(parsed)))
    }

    @Test fun rejectsPrivateOrCredentialedBrowser() {
        assertNull(AssistantTaskIntent.parse("""{"action":"browser_task","url":"http://example.com","goal":"자료 조회"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"browser_task","url":"https://127.0.0.1/","goal":"자료 조회"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"browser_task","url":"https://u:p@example.com","goal":"자료 조회"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"browser_task","url":"https://example.com","goal":"자료 조회","confirmed":true}"""))
    }

    @Test fun acceptsAllowlistedReadActionsOnly() {
        assertEquals(AssistantIntent.Google("gmail_recent"), AssistantTaskIntent.parse("""{"action":"gmail_recent"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"gmail_delete"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"calendar_upcoming","email":"a@b.com"}"""))
    }

    @Test fun searchAndQuestionsNeedValidatedFields() {
        assertEquals(AssistantIntent.Search("국세청 사업자등록"), AssistantTaskIntent.parse("""{"action":"web_search","query":"국세청 사업자등록"}"""))
        assertEquals(AssistantIntent.Ask("상호명이 무엇인가요?"), AssistantTaskIntent.parse("""{"action":"ask_user","question":"상호명이 무엇인가요?"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"web_search","query":" "}"""))
    }

    @Test fun mailWatchRemainsAProposalWithValidatedFilters() {
        val proposed = """{"action":"mail_watch","mode":"matched","sender":"example@domain.test","keyword":"계약"}"""
        val parsed = AssistantTaskIntent.parse(proposed)
        assertTrue(parsed is AssistantIntent.MailWatch)
        assertTrue(AssistantTaskIntent.persistable(parsed!!))
        assertEquals(parsed, AssistantTaskIntent.parse(AssistantTaskIntent.encode(parsed)))
        assertNull(AssistantTaskIntent.parse("""{"action":"mail_watch","mode":"matched","sender":"","keyword":""}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"mail_watch","mode":"delete","sender":"","keyword":""}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"mail_watch","mode":"all","sender":"","keyword":"","confirmed":true}"""))
    }

    @Test fun nativeDeviceActionsAreExplicitAndValidated() {
        val valid = AssistantTaskIntent.parse("""{"action":"device_action","operation":"dial","target":"010-1234-5678"}""")
        assertEquals(AssistantIntent.Device("dial","010-1234-5678"), valid)
        assertEquals(valid, AssistantTaskIntent.parse(AssistantTaskIntent.encode(valid!!)))
        assertTrue(!AssistantTaskIntent.persistable(valid))
        assertNull(AssistantTaskIntent.parse("""{"action":"device_action","operation":"sms","target":"abc"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"device_action","operation":"settings","target":"hidden"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"device_action","operation":"open_url","target":"http://example.com"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"device_action","operation":"pay","target":"1000"}"""))
        assertNull(AssistantTaskIntent.parse("""{"action":"device_action","operation":"dial","target":"01012345678","confirmed":true}"""))
        assertEquals(AssistantIntent.Device("calendar",""),
            AssistantTaskIntent.parse("""{"action":"device_action","operation":"calendar","target":""}"""))
    }

    @Test fun harmlessChatNeverBecomesAnAction() {
        assertNull(AssistantTaskIntent.parse("오늘 날씨가 궁금하시군요."))
        assertNull(AssistantTaskIntent.parse("비서가 신청을 완료했습니다."))
        assertNull(AssistantTaskIntent.parse("""```json
{"action":"gmail_recent"}
```"""))
        assertTrue(AssistantTaskIntent.looksLikeAction("""{"action":"gmail_delete"}"""))
    }
}
