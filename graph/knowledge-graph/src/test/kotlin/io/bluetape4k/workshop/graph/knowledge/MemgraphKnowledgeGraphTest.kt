package io.bluetape4k.workshop.graph.knowledge

import io.bluetape4k.graph.memgraph.MemgraphGraphOperations
import io.bluetape4k.graph.memgraph.MemgraphGraphSuspendOperations
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.graphdb.MemgraphServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

/**
 * [AbstractKnowledgeGraphTest] backed by a Memgraph Testcontainer.
 *
 * Requires Docker. Run with: `./gradlew :graph-knowledge-graph:integrationTest`
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemgraphKnowledgeGraphTest : AbstractKnowledgeGraphTest() {
    companion object : KLogging() {
        private val memgraph = MemgraphServer.Launcher.memgraph
    }

    override val graphName: String = "memgraph_knowledge"

    private lateinit var driver: Driver
    override lateinit var ops: GraphOperations

    @BeforeAll
    fun setUp() {
        driver = GraphDatabase.driver(memgraph.boltUrl)
        ops = MemgraphGraphOperations(driver)
    }

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
            .onFailure { log.warn(it) { "Driver close failed during tearDown" } }
    }
}

/**
 * [AbstractKnowledgeGraphSuspendTest] backed by a Memgraph Testcontainer.
 *
 * Requires Docker. Run with: `./gradlew :graph-knowledge-graph:integrationTest`
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemgraphKnowledgeGraphSuspendTest : AbstractKnowledgeGraphSuspendTest() {
    companion object : KLogging() {
        private val memgraph = MemgraphServer.Launcher.memgraph
    }

    override val graphName: String = "memgraph_knowledge_suspend"

    private lateinit var driver: Driver
    override lateinit var ops: GraphSuspendOperations

    @BeforeAll
    fun setUp() {
        driver = GraphDatabase.driver(memgraph.boltUrl)
        ops = MemgraphGraphSuspendOperations(driver)
    }

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
            .onFailure { log.warn(it) { "Driver close failed during tearDown" } }
    }
}
