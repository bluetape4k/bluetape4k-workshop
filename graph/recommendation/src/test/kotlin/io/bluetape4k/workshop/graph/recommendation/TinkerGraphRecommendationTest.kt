package io.bluetape4k.workshop.graph.recommendation

import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * [RecommendationService] tests backed by TinkerGraph (in-memory, no Docker required).
 *
 * All tests from [AbstractRecommendationTest] run against an in-process TinkerGraph instance.
 * The graph is cleared and re-initialized before each test via the base [cleanGraph] setup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TinkerGraphRecommendationTest : AbstractRecommendationTest() {

    companion object : KLogging()

    override val graphName = "test_recommendation"
    override val ops: GraphOperations = TinkerGraphOperations()
    override val service = RecommendationService(ops, graphName)

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}
