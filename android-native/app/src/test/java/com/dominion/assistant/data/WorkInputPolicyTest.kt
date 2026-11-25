package com.dominion.assistant.data

import org.junit.Assert.*
import org.junit.Test

class WorkInputPolicyTest {
    @Test fun validOfficialUrl() {
        assertEquals("https://example.com/path", WorkInputPolicy.officialHttps("https://example.com/path"))
        assertNotNull(WorkInputPolicy.officialHttps("https://www.gov.kr"))
    }
    @Test fun rejectsLocalOrCredentialUrls() {
        listOf(
            "http://example.com", "https://localhost/",
            "https://foo.internal", "https://127.0.0.1/",
            "https://user:secret@example.com/",
            "https://example.com:8443/",
            "https://example.com.evil.local/"
        ).forEach { assertNull(it, WorkInputPolicy.officialHttps(it)) }
    }
    @Test fun verifiesPdfMagicAndExtension() {
        val pdf = "%PDF-123".toByteArray()
        assertTrue(WorkInputPolicy.validFile("report.pdf", "application/pdf", pdf))
        assertFalse(WorkInputPolicy.validFile("report.png", "application/pdf", pdf))
        assertFalse(WorkInputPolicy.validFile("../report.pdf", "application/pdf", pdf))
    }
    @Test fun verifiesPngMagicAndSize() {
        val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
        assertTrue(WorkInputPolicy.validFile("image.png","image/png",png))
        assertFalse(WorkInputPolicy.validFile("image.jpg","image/png",png))
        assertFalse(WorkInputPolicy.validFile("oversize.png","image/png",ByteArray(2*1024*1024+1)))
    }
    @Test fun verifiesJpegMagic() {
        val jpg = byteArrayOf(255.toByte(),216.toByte(),255.toByte(),0,1,2,3,4)
        assertTrue(WorkInputPolicy.validFile("photo.jpeg","image/jpeg",jpg))
        assertFalse(WorkInputPolicy.validFile("photo.pdf","image/jpeg",jpg))
    }
}
