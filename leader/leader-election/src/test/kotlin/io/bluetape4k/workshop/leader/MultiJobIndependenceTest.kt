package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T4: Multiple jobs with independent lock names.
 *
 * A single elector can hold multiple locks simultaneously (one per lockName).
 * Both jobs should be executed when run sequentially by the same instance.
 */
class MultiJobIndependenceTest : AbstractLeaderElectionTest() {

    @Test
    fun `two jobs with different lockNames are both executed by the same elector`() {
        val lockA = "test:t4:job-a:${UUID.randomUUID()}"
        val lockB = "test:t4:job-b:${UUID.randomUUID()}"
        val elector = newElector()

        val resultA = elector.runIfLeader(lockA) { "A" }
        val resultB = elector.runIfLeader(lockB) { "B" }

        resultA.shouldNotBeNull() shouldBeEqualTo "A"
        resultB.shouldNotBeNull() shouldBeEqualTo "B"
    }
}
