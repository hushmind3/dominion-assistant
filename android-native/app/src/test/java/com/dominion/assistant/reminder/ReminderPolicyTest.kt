package com.dominion.assistant.reminder

import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    private val now=1_800_000_000_000L
    @Test fun acceptsOwnerReminderWithinOneYear() {
        assertTrue(ReminderPolicy.valid("문서 확인",now+60_000,now))
        assertTrue(ReminderPolicy.valid("보고",now+24L*60*60*1000,now))
    }
    @Test fun rejectsPastDatesAndOversizedTitles() {
        assertFalse(ReminderPolicy.valid("공백",now-1,now))
        assertFalse(ReminderPolicy.valid("공백",now+4_000,now))
        assertFalse(ReminderPolicy.valid("a".repeat(121),now+60_000,now))
        assertFalse(ReminderPolicy.valid(" ",now+60_000,now))
        assertFalse(ReminderPolicy.valid("x\ninjection",now+60_000,now))
    }
    @Test fun rejectsMoreThanOneYearInFuture() {
        assertFalse(ReminderPolicy.valid("1년 초과",now+366L*24*60*60*1000,now))
    }
}
