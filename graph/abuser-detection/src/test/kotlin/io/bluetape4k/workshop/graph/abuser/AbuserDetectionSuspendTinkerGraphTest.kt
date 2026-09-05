package io.bluetape4k.workshop.graph.abuser

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.graph.GraphQueryException
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphSuspendOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionSuspendService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * TinkerGraph가 뒷받침하는 [AbuserDetectionSuspendService] 테스트입니다(인메모리, Docker 불필요).
 *
 * [AbstractAbuserDetectionSuspendTest]의 16개 테스트를 in-process
 * TinkerGraphSuspendOperations 인스턴스에서 실행합니다. 그래프는 매 테스트 전에 정리하고 다시 초기화합니다.
 * base [cleanGraph] 설정을 통해 각 테스트 전에 실행됩니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbuserDetectionSuspendTinkerGraphTest : AbstractAbuserDetectionSuspendTest() {

    companion object : KLoggingChannel()

    override val graphName = "test_abuser_suspend"
    override val ops: GraphSuspendOperations = TinkerGraphSuspendOperations()
    override val service = AbuserDetectionSuspendService(ops, graphName)

    @Test
    fun `malformed TinkerGraph vertex ID is rejected`() = runSuspendIO {
        assertFailsWith<GraphQueryException> {
            service.findAbuseCluster(GraphElementId("malformed-id"))
        }
    }

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}
