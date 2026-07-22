package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EventContractTest {
    @Test
    fun `stream keys sort by tenant type and id`() {
        val keys = listOf(
            StreamKey("tenant-b", "Usage", "2"),
            StreamKey("tenant-a", "Usage", "2"),
            StreamKey("tenant-a", "Meter", "9"),
        )

        assertEquals(
            listOf("tenant-a/Meter/9", "tenant-a/Usage/2", "tenant-b/Usage/2"),
            keys.sorted().map(StreamKey::canonical),
        )
    }

    @Test
    fun `stream key rejects a blank boundary`() {
        assertThrows(IllegalArgumentException::class.java) { StreamKey("tenant-a", " ", "stream-1") }
    }
}
