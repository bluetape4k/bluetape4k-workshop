package io.bluetape4k.workshop.graph.abuser

import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphSuspendOperations
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionSuspendService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * [AbuserDetectionSuspendService] tests backed by TinkerGraph (in-memory, no Docker required).
 *
 * All 16 tests from [AbstractAbuserDetectionSuspendTest] run against an in-process
 * TinkerGraphSuspendOperations instance. The graph is cleared and re-initialized before
 * each test via the base [cleanGraph] setup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbuserDetectionSuspendTinkerGraphTest : AbstractAbuserDetectionSuspendTest() {

    companion object : KLoggingChannel()

    override val graphName = "test_abuser_suspend"
    override val ops: GraphSuspendOperations = TinkerGraphSuspendOperations()
    override val service = AbuserDetectionSuspendService(ops, graphName)

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}
