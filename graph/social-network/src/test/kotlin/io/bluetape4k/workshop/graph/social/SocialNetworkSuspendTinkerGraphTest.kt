package io.bluetape4k.workshop.graph.social

import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphSuspendOperations
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.graph.social.service.SocialNetworkSuspendService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * [SocialNetworkSuspendService] tests backed by TinkerGraph (in-memory, no Docker required).
 *
 * All 34 tests from [AbstractSocialNetworkSuspendTest] run against an in-process
 * TinkerGraphSuspendOperations instance. The graph is cleared and re-initialized before
 * each test via the base [cleanGraph] setup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialNetworkSuspendTinkerGraphTest : AbstractSocialNetworkSuspendTest() {

    companion object : KLoggingChannel()

    override val graphName = "test_social_suspend"
    override val ops: GraphSuspendOperations = TinkerGraphSuspendOperations()
    override val service = SocialNetworkSuspendService(ops, graphName)

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}
