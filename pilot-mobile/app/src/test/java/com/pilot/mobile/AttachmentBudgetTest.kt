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

    @Test fun boundsLegacyCapabilitiesAndRejectsInvalidValues() {
        assertEquals(16, budget("{}").maxFiles)
        assertEquals(6 * 1024 * 1024, budget("""{"attachmentsMaxBytes":8388608}""").maxTotalBytes)
        assertThrows(IllegalArgumentException::class.java) { AttachmentBudget.from(null) }
        for (value in listOf("0", "-1", "1.5", "\"invalid\"")) {
            assertThrows(IllegalArgumentException::class.java) { budget("""{"attachmentsMaxBytes":$value}""") }
        }
    }
}
