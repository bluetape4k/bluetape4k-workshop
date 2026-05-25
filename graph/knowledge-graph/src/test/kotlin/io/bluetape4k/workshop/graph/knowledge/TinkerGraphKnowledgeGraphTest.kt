package io.bluetape4k.workshop.graph.knowledge

import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphSuspendOperations
import io.bluetape4k.logging.KLogging

/**
 * [AbstractKnowledgeGraphTest] backed by TinkerGraph (in-memory).
 *
 * No Docker required — runs in the default `:test` task.
 */
class TinkerGraphKnowledgeGraphTest : AbstractKnowledgeGraphTest() {
    companion object : KLogging()

    override val graphName: String = "default"
    override val ops: GraphOperations = TinkerGraphOperations()
}

/**
 * [AbstractKnowledgeGraphSuspendTest] backed by TinkerGraph (in-memory).
 *
 * No Docker required — runs in the default `:test` task.
 */
class TinkerGraphKnowledgeGraphSuspendTest : AbstractKnowledgeGraphSuspendTest() {
    companion object : KLogging()

    override val graphName: String = "default"
    override val ops: GraphSuspendOperations = TinkerGraphSuspendOperations()
}
