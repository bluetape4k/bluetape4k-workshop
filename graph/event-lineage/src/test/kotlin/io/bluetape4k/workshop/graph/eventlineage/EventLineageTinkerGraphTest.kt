package io.bluetape4k.workshop.graph.eventlineage

import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.eventlineage.service.EventLineageService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * [EventLineageService] tests backed by TinkerGraph.
 *
 * This module intentionally keeps the first workshop slice in-memory so
 * learners can run lineage and audit-trail examples without Docker.
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
