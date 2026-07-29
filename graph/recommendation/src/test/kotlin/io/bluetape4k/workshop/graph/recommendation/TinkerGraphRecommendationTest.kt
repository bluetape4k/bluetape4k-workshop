package io.bluetape4k.workshop.graph.recommendation

import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * TinkerGraph(인메모리, Docker 불필요)가 뒷받침하는 [RecommendationService] 테스트입니다.
 *
 * [AbstractRecommendationTest]의 모든 테스트는 in-process TinkerGraph instance에서 실행됩니다.
 * 그래프는 base [cleanGraph] setup을 통해 매 테스트 전에 비우고 다시 초기화합니다.
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
