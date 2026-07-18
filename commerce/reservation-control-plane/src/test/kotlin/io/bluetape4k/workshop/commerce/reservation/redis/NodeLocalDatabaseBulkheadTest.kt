package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class NodeLocalDatabaseBulkheadTest {

    @Test
    fun `foreground work is rejected when its local lane is full and recovers after release`() {
        val bulkhead = NodeLocalDatabaseBulkhead(
            foregroundPermits = 1,
            backgroundPermits = 1,
            acquireTimeout = Duration.ZERO,
        )
        var nested: DatabaseBulkheadOutcome<String>? = null

        val outer = bulkhead.execute(DatabaseWorkload.FOREGROUND) {
            nested = bulkhead.execute(DatabaseWorkload.FOREGROUND) { "unexpected" }
            "outer"
        }
        val recovered = bulkhead.execute(DatabaseWorkload.FOREGROUND) { "recovered" }

        outer shouldBeEqualTo DatabaseBulkheadOutcome.Executed("outer")
        nested shouldBeEqualTo DatabaseBulkheadOutcome.Rejected(DatabaseWorkload.FOREGROUND)
        recovered shouldBeEqualTo DatabaseBulkheadOutcome.Executed("recovered")
    }

    @Test
    fun `background lane remains independent from a saturated foreground lane`() {
        val bulkhead = NodeLocalDatabaseBulkhead(
            foregroundPermits = 1,
            backgroundPermits = 1,
            acquireTimeout = Duration.ZERO,
        )
        var background: DatabaseBulkheadOutcome<String>? = null

        bulkhead.execute(DatabaseWorkload.FOREGROUND) {
            background = bulkhead.execute(DatabaseWorkload.BACKGROUND) { "background" }
        }

        background shouldBeEqualTo DatabaseBulkheadOutcome.Executed("background")
    }
}
