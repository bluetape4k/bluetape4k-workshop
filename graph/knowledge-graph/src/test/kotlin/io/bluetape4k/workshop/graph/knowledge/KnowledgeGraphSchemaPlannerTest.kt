package io.bluetape4k.workshop.graph.knowledge

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.graph.schema.GraphSchemaManagementOperations
import io.bluetape4k.graph.schema.GraphSchemaManager
import io.bluetape4k.graph.schema.GraphSchemaPlanAction
import io.bluetape4k.graph.schema.GraphSchemaPlanOptions
import io.bluetape4k.graph.schema.GraphSuspendSchemaManagementOperations
import io.bluetape4k.graph.schema.GraphSuspendSchemaManager
import io.bluetape4k.graph.schema.schemaManager
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphSuspendOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphService
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphSuspendService
import org.junit.jupiter.api.Test

class KnowledgeGraphSchemaPlannerTest {

    @Test
    fun `TinkerGraph apply reports unsupported unique constraints`() {
        val ops = TinkerGraphOperations()
        try {
            val service = KnowledgeGraphService(ops)
            val plan = service.planSchema(GraphSchemaPlanOptions(dryRun = false))

            val report = plan.apply(ops.schemaManager())

            report.isSuccessful.shouldBeFalse()
            report.unsupported.count { it.action == GraphSchemaPlanAction.UNSUPPORTED } shouldBeEqualTo 3
            report.unsupported.mapNotNull { it.constraint?.property } shouldContain "entityId"
            report.unsupported.mapNotNull { it.constraint?.property } shouldContain "conceptId"
            report.unsupported.mapNotNull { it.constraint?.property } shouldContain "documentId"
        } finally {
            ops.close()
        }
    }

    @Test
    fun `blocking initialize plans before graph creation`() {
        val ops = FailingSchemaGraphOperations()
        try {
            assertFailsWith<UnsupportedOperationException> {
                KnowledgeGraphService(ops, "failure-before-seed").initialize()
            }

            ops.createGraphCalls shouldBeEqualTo 0
        } finally {
            ops.close()
        }
    }

    @Test
    fun `suspend initialize plans before graph creation`() = runSuspendIO {
        val ops = FailingSuspendSchemaGraphOperations()
        try {
            assertFailsWith<UnsupportedOperationException> {
                KnowledgeGraphSuspendService(ops, "failure-before-seed").initialize()
            }

            ops.createGraphCalls shouldBeEqualTo 0
        } finally {
            ops.close()
        }
    }

    private class FailingSchemaGraphOperations(
        private val delegate: TinkerGraphOperations = TinkerGraphOperations(),
    ) : GraphOperations by delegate, GraphSchemaManagementOperations {
        var createGraphCalls: Int = 0

        override fun createGraph(name: String) {
            createGraphCalls++
            delegate.createGraph(name)
        }

        override fun schemaManager(): GraphSchemaManager =
            throw UnsupportedOperationException("schema planner unavailable")
    }

    private class FailingSuspendSchemaGraphOperations(
        private val delegate: TinkerGraphSuspendOperations = TinkerGraphSuspendOperations(),
    ) : GraphSuspendOperations by delegate, GraphSuspendSchemaManagementOperations {
        var createGraphCalls: Int = 0

        override suspend fun createGraph(name: String) {
            createGraphCalls++
            delegate.createGraph(name)
        }

        override fun schemaManager(): GraphSuspendSchemaManager =
            throw UnsupportedOperationException("suspend schema planner unavailable")
    }
}
