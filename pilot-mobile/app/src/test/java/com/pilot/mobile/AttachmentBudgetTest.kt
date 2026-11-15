package com.pilot.mobile

import com.pilot.mobile.transport.Protocol
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentBudgetTest {
    private fun budget(json: String) = AttachmentBudget.from(Protocol.json.parseToJsonElement(json).jsonObject)
    private fun file(bytes: Int) = MobileAttachment("文件.txt", "text/plain", "", bytes)

    @Test fun usesSmallerServerBudgetsAndChecksRestoredDrafts() {
        val budget = budget("""{"attachmentsMaxFiles":2,"attachmentsMaxFileBytes":3,"attachmentsMaxBytes":4,"attachmentsMaxRequestBytes":200} """)
        budget.validate(listOf(file(2), file(2)))
        assertEquals(200, budget.maxRequestBytes)
        assertThrows(IllegalArgumentException::class.java) { budget.validate(listOf(file(3), file(2))) }
        assertThrows(IllegalArgumentException::class.java) { budget.validate(listOf(file(4))) }
        assertThrows(IllegalArgumentException::class.java) { budget.validate(listOf(file(1), file(1), file(1))) }
        assertThrows(IllegalArgumentException::class.java) { budget.validate(listOf(file(0))) }
    }

    @Test fun messagesUseReadableSizeAndRawLimit() {
        val budget = budget("""{"attachmentsMaxFileBytes":2097152,"attachmentsMaxBytes":6291456}""")
        val oversize = assertThrows(IllegalArgumentException::class.java) { budget.validate(listOf(file(2 * 1024 * 1024 + 1))) }
        org.junit.Assert.assertTrue(oversize.message!!.contains("2.0 MB"), oversize.message)
        org.junit.Assert.assertTrue(oversize.message!!.contains("2097152"), oversize.message)
        val total = assertThrows(IllegalArgumentException::class.java) {
            budget.validate(listOf(file(2 * 1024 * 1024), file(2 * 1024 * 1024), file(2 * 1024 * 1024), file(1)))
        }
        org.junit.Assert.assertTrue(total.message!!.contains("6.0 MB"), total.message)
        org.junit.Assert.assertTrue(total.message!!.contains("6291456"), total.message)
        assertEquals("512 KB（524288 字节）", AttachmentBudget.describe(512 * 1024))
        assertEquals("1.0 MB（1048576 字节）", AttachmentBudget.describe(1024 * 1024))
    }

    @Test fun boundsLegacyCapabilitiesAndRejectsInvalidValues() {
        assertEquals(16, budget("{}").maxFiles)
        assertEquals(6 * 1024 * 1024, budget("""{"attachmentsMaxBytes":8388608}""").maxTotalBytes)
        assertThrows(IllegalArgumentException::class.java) { AttachmentBudget.from(null) }
        for (value in listOf("0", "-1", "1.5", "\"invalid\"")) {
            assertThrows(IllegalArgumentException::class.java) { budget("""{"attachmentsMaxBytes":$value}""") }
        }
    }
}
