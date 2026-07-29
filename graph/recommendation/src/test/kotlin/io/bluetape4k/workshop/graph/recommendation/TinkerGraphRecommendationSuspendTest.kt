package io.bluetape4k.workshop.graph.recommendation

import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphSuspendOperations
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationSuspendService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * TinkerGraph(인메모리, Docker 불필요)가 뒷받침하는 [RecommendationSuspendService] 테스트입니다.
 *
 * [AbstractRecommendationSuspendTest]의 모든 테스트는 in-process TinkerGraphSuspendOperations
 * instance에서 실행됩니다. 그래프는 base [cleanGraph] setup을 통해 매 테스트 전에 비우고 다시 초기화합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TinkerGraphRecommendationSuspendTest : AbstractRecommendationSuspendTest() {

    companion object : KLoggingChannel()

    override val graphName = "test_recommendation_suspend"
    override val ops: GraphSuspendOperations = TinkerGraphSuspendOperations()
    override val service = RecommendationSuspendService(ops, graphName)

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}
