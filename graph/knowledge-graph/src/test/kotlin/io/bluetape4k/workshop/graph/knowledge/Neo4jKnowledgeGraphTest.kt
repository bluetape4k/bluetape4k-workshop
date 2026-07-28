package io.bluetape4k.workshop.graph.knowledge

import io.bluetape4k.graph.neo4j.Neo4jGraphOperations
import io.bluetape4k.graph.neo4j.Neo4jGraphSuspendOperations
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.graphdb.Neo4jServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

/**
 * Neo4j Testcontainer가 뒷받침하는 [AbstractKnowledgeGraphTest]입니다.
 *
 * Docker가 필요합니다. 실행: `./gradlew :graph-knowledge-graph:integrationTest`
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jKnowledgeGraphTest : AbstractKnowledgeGraphTest() {
    companion object : KLogging() {
        private val neo4j = Neo4jServer.Launcher.neo4j
    }

    override val graphName: String = "neo4j_knowledge"

    private val driver: Driver by lazy {
        GraphDatabase.driver(neo4j.url)
    }

    override val ops: GraphOperations by lazy {
        Neo4jGraphOperations(driver)
    }

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
            .onFailure { log.warn(it) { "Driver close failed during tearDown" } }
    }
}

/**
 * Neo4j Testcontainer가 뒷받침하는 [AbstractKnowledgeGraphSuspendTest]입니다.
 *
 * Docker가 필요합니다. 실행: `./gradlew :graph-knowledge-graph:integrationTest`
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jKnowledgeGraphSuspendTest : AbstractKnowledgeGraphSuspendTest() {
    companion object : KLogging() {
        private val neo4j = Neo4jServer.Launcher.neo4j
    }

    override val graphName: String = "neo4j_knowledge_suspend"

    private val driver: Driver by lazy {
        GraphDatabase.driver(neo4j.url)
    }

    override val ops: GraphSuspendOperations by lazy {
        Neo4jGraphSuspendOperations(driver)
    }

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
            .onFailure { log.warn(it) { "Driver close failed during tearDown" } }
    }
}
