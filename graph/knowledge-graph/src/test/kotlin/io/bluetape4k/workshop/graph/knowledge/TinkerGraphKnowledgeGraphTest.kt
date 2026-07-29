package io.bluetape4k.workshop.graph.knowledge

import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphSuspendOperations
import io.bluetape4k.logging.KLogging

/**
 * TinkerGraph(인메모리)가 뒷받침하는 [AbstractKnowledgeGraphTest]입니다.
 *
 * Docker가 필요하지 않으며 기본 `:test` task에서 실행됩니다.
 */
class TinkerGraphKnowledgeGraphTest : AbstractKnowledgeGraphTest() {
    companion object : KLogging()

    override val graphName: String = "default"
    override val ops: GraphOperations = TinkerGraphOperations()
}

/**
 * TinkerGraph(인메모리)가 뒷받침하는 [AbstractKnowledgeGraphSuspendTest]입니다.
 *
 * Docker가 필요하지 않으며 기본 `:test` task에서 실행됩니다.
 */
class TinkerGraphKnowledgeGraphSuspendTest : AbstractKnowledgeGraphSuspendTest() {
    companion object : KLogging()

    override val graphName: String = "default"
    override val ops: GraphSuspendOperations = TinkerGraphSuspendOperations()
}
