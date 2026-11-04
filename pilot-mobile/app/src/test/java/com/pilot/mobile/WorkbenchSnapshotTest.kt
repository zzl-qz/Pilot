package com.pilot.mobile

import com.pilot.mobile.transport.Protocol
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class WorkbenchSnapshotTest {
    private val revision = "a".repeat(64)
    private fun json(value: String): JsonObject = Protocol.json.parseToJsonElement(value).jsonObject
    private fun full(revision: String = this.revision) = json("""{"version":1,"revision":"$revision","sessions":[{"id":"ses_a"}],"capabilities":{"idempotency":true,"conditionalSnapshot":true}}""")
    private fun unchanged(revision: String = this.revision) = json("""{"version":1,"revision":"$revision","unchanged":true}""")

    @Test fun unchangedResponseRetainsSnapshotAndRequestsOnlyItsOwnRevision() {
        val cursor = WorkbenchSnapshot()
        assertNull(cursor.request().snapshotRevision)
        val snapshot = full()
        assertSame(snapshot, cursor.accept(snapshot))
        assertEquals("/api/mobile/workbench", cursor.request().path)
        assertEquals(revision, cursor.request().snapshotRevision)
        assertSame(snapshot, cursor.accept(unchanged()))
        val changed = full("b".repeat(64))
        assertSame(changed, cursor.accept(changed))
        assertEquals("b".repeat(64), cursor.request().snapshotRevision)
        assertNull(WorkbenchSnapshot().request().snapshotRevision)
    }

    @Test fun unknownAcknowledgementDropsCursorForFullResynchronization() {
        val cursor = WorkbenchSnapshot()
        assertThrows(IOException::class.java) { cursor.accept(unchanged()) }
        cursor.accept(full())
        assertThrows(IOException::class.java) { cursor.accept(unchanged("b".repeat(64))) }
        assertNull(cursor.request().snapshotRevision)
        val recovered = full()
        assertSame(recovered, cursor.accept(recovered))
    }

    @Test fun oldServerOrInvalidRevisionFallsBackToFullReadWithoutChangingRoute() {
        for (value in listOf(json("""{"version":1,"capabilities":{"idempotency":true}}"""), full("invalid"))) {
            val cursor = WorkbenchSnapshot()
            assertSame(value, cursor.accept(value))
            assertNull(cursor.request().snapshotRevision)
            assertEquals("/api/mobile/workbench", cursor.request().path)
        }
    }

    @Test fun rejectsIncompatibleOrUnsafeProtocolBeforeAcceptingSnapshot() {
        val cursor = WorkbenchSnapshot()
        assertThrows(IllegalArgumentException::class.java) { cursor.accept(json("""{"version":2}""")) }
        assertThrows(IllegalArgumentException::class.java) { cursor.accept(json("""{"version":1,"capabilities":{}}""")) }
        assertNull(cursor.request().snapshotRevision)
    }
}
