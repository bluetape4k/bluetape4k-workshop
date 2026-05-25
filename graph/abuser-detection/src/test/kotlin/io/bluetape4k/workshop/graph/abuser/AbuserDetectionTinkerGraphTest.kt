package io.bluetape4k.workshop.graph.abuser

import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * [AbuserDetectionService] tests backed by TinkerGraph (in-memory, no Docker required).
 *
 * All 15 tests from [AbstractAbuserDetectionTest] run against an in-process TinkerGraph instance.
 * The graph is cleared and re-initialized before each test via the base [cleanGraph] setup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbuserDetectionTinkerGraphTest : AbstractAbuserDetectionTest() {

    companion object : KLogging()

    override val graphName = "test_abuser"
    override val ops: GraphOperations = TinkerGraphOperations()
    override val service = AbuserDetectionService(ops, graphName)

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}
