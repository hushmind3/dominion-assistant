package com.dominion.assistant.data

import org.junit.Assert.*
import org.junit.Test

class PcAttachmentPolicyTest {
    @Test fun validFilesHaveExpectedMagicAndExtension() {
        assertTrue(PcAttachmentPolicy.valid("a.jpg","image/jpeg",
            byteArrayOf(255.toByte(),216.toByte(),255.toByte(),1)))
        assertTrue(PcAttachmentPolicy.valid("a.pdf","application/pdf",
            "%PDF-1.7".toByteArray()))
        assertTrue(PcAttachmentPolicy.valid("notes.md","text/markdown",
            "안녕하세요".toByteArray()))
        assertTrue(PcAttachmentPolicy.valid("data.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            byteArrayOf(80,75,3,4,0)))
        assertFalse(PcAttachmentPolicy.valid("fake.pdf","application/pdf",
            "plain text".toByteArray()))
    }
    @Test fun rejectsSizeBoundaryAndTraversal() {
        assertFalse(PcAttachmentPolicy.valid("a.txt","text/plain",ByteArray(0)))
        assertFalse(PcAttachmentPolicy.valid("a.txt","text/plain",
            ByteArray(PcAttachmentPolicy.MAX_RELAY_BYTES+1)))
        assertFalse(PcAttachmentPolicy.valid("../a.txt","text/plain",
            "ok".toByteArray()))
    }
    @Test fun rejectsBinaryMislabelledAsText() {
        assertFalse(PcAttachmentPolicy.valid("binary.txt","text/plain",
            byteArrayOf(0.toByte(),1.toByte(),2.toByte())))
        assertFalse(PcAttachmentPolicy.valid("bad.txt","text/plain",
            byteArrayOf(0xff.toByte(),0xfe.toByte())))
    }
}
