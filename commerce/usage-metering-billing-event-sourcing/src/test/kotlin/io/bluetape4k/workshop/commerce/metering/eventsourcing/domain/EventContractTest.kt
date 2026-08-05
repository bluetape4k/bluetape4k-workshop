package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class EventContractTest {
    @Test
    fun `stream keys sort by tenant type and id`() {
        val keys = listOf(
            StreamKey("tenant-b", "Usage", "2"),
            StreamKey("tenant-a", "Usage", "2"),
            StreamKey("tenant-a", "Meter", "9"),
        )

        keys.sorted().map(StreamKey::canonical)
            .shouldBeEqualTo(listOf("tenant-a/Meter/9", "tenant-a/Usage/2", "tenant-b/Usage/2"))
    }

    @Test
    fun `stream key rejects a blank boundary`() {
        assertFailsWith<IllegalArgumentException> { StreamKey("tenant-a", " ", "stream-1") }
    }
}
