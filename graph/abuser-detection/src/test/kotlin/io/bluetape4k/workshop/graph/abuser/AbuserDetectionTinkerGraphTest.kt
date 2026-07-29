package io.bluetape4k.workshop.graph.abuser

import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * TinkerGraph가 뒷받침하는 [AbuserDetectionService] 테스트입니다(인메모리, Docker 불필요).
 *
 * [AbstractAbuserDetectionTest]의 15개 테스트를 in-process TinkerGraph 인스턴스에서 실행합니다.
 * 그래프는 base [cleanGraph] 설정으로 각 테스트 전에 정리하고 다시 초기화합니다.
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
