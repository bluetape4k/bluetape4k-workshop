package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryConformanceFixture
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryObservation
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryPath
import org.junit.jupiter.api.Test

class KafkaRecoveryConformanceFixtureTest {

    companion object: KLogging()

    private val fixture = KafkaRecoveryConformanceFixture(15.seconds())

    @Test
    fun `black box fixture keeps replay delivery separate from unique applied effect`() {
        fixture.assertDedupBoundary(
            logicalEventIds = setOf("event-1"),
            deliveredEventIds = listOf("event-1", "event-1"),
            appliedEventIds = setOf("event-1"),
        )
    }

    @Test
    fun `black box fixture rejects transport evidence labelled as broker failover`() {
        val error = assertFailsWith<IllegalStateException> {
            fixture.assertBrokerLeaderFailover(
                observation = KafkaRecoveryObservation(
                    path = KafkaRecoveryPath.TRANSPORT_INTERRUPTION,
                    logicalEventIds = setOf("event-1"),
                    deliveredEventIds = listOf("event-1"),
                    appliedEventIds = setOf("event-1"),
                    conflictCount = 0,
                    recoveryElapsed = 1.seconds(),
                    recovered = true,
                    transportInterrupted = true,
                    leaderChanged = false,
                    coordinatorChanged = false,
                    replacementReady = false,
                    isrRestored = false,
                ),
            )
        }
        log.debug { "error=$error" }
        error.message shouldBeEqualTo "broker failover observation has the wrong path"
    }
}
