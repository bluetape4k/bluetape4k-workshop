package io.bluetape4k.workshop.graph.eventlineage

import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.eventlineage.service.EventLineageService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * TinkerGraph가 뒷받침하는 [EventLineageService] 테스트입니다.
 *
 * 이 모듈은 첫 워크숍 slice를 의도적으로 인메모리로 유지하므로
 * 학습자는 Docker 없이 lineage와 감사 추적 예제를 실행할 수 있습니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventLineageTinkerGraphTest : AbstractEventLineageTest() {

    companion object : KLogging()

    override val graphName: String = "test_event_lineage"
    override val ops: GraphOperations = TinkerGraphOperations()
    override val service: EventLineageService = EventLineageService(ops, graphName)

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}
