package io.bluetape4k.workshop.graph.social

import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.social.service.SocialNetworkService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * [SocialNetworkService] tests backed by TinkerGraph (in-memory, no Docker required).
 *
 * All 34 tests from [AbstractSocialNetworkTest] run against an in-process TinkerGraph instance.
 * The graph is cleared and re-initialized before each test via the base [cleanGraph] setup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialNetworkTinkerGraphTest : AbstractSocialNetworkTest() {

    companion object : KLogging()

    override val graphName = "test_social"
    override val ops: GraphOperations = TinkerGraphOperations()
    override val service = SocialNetworkService(ops, graphName)

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}
