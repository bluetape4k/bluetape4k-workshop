package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class EventStoreQueryPlanArchitectureTest {

    @Test
    fun `stream tail plan uses the Exposed explain wrapper`() {
        val source = Files.readString(locateSource())
        source.shouldContain(".explain(options = \"FORMAT TEXT\"")
        source.shouldContain(".selectAll()")
        source.shouldNotContain("EXPLAIN (FORMAT TEXT)")
    }

    @Test
    fun `stress query plans use the Exposed explain wrapper`() {
        val source = Files.readString(locateStressSource())
        source.shouldContain(".explain(analyze = true, options = \"BUFFERS\"")
        source.shouldNotContain("EXPLAIN (ANALYZE, BUFFERS)")
    }

    private fun locateSource(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = directory?.resolve(
                Path.of(
                    "commerce",
                    "event-sourced-promotion-voucher-campaign",
                    "src",
                    "test",
                    "kotlin",
                    "io",
                    "bluetape4k",
                    "workshop",
                    "commerce",
                    "voucher",
                    "eventsourced",
                    "persistence",
                    "EventStorePostgresCapabilityIntegrationTest.kt",
                ),
            )
            if (candidate != null && Files.isRegularFile(candidate)) return candidate
            directory = directory?.parent
        }
        error("EventStorePostgresCapabilityIntegrationTest.kt를 찾을 수 없습니다")
    }

    private fun locateStressSource(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = directory?.resolve(
                Path.of(
                    "commerce",
                    "event-sourced-promotion-voucher-campaign",
                    "src",
                    "test",
                    "kotlin",
                    "io",
                    "bluetape4k",
                    "workshop",
                    "commerce",
                    "voucher",
                    "eventsourced",
                    "persistence",
                    "EventSourcedQueryPlanStressTest.kt",
                ),
            )
            if (candidate != null && Files.isRegularFile(candidate)) return candidate
            directory = directory?.parent
        }
        error("EventSourcedQueryPlanStressTest.kt를 찾을 수 없습니다")
    }
}
