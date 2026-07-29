package io.bluetape4k.workshop.graph.abuser

import io.bluetape4k.graph.memgraph.MemgraphGraphOperations
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.graphdb.MemgraphServer
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

/**
 * Memgraph Testcontainer가 뒷받침하는 [AbuserDetectionService] 통합 테스트입니다.
 *
 * Docker가 필요합니다. 실행: `./gradlew :graph-abuser-detection:integrationTest`
 * 기본 `:test` task는 `@Tag("integration")`으로 이 클래스를 제외합니다.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemgraphAbuserDetectionTest : AbstractAbuserDetectionTest() {

    companion object : KLogging() {
        private val memgraph = MemgraphServer.Launcher.memgraph
    }

    override val graphName = "memgraph"

    private val driver: Driver by lazy {
        GraphDatabase.driver(memgraph.boltUrl, AuthTokens.none())
    }

    override val ops: GraphOperations by lazy {
        MemgraphGraphOperations(driver)
    }

    override val service by lazy {
        AbuserDetectionService(ops, graphName)
    }

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
            .onFailure { log.warn(it) { "Driver close failed during tearDown — possible resource leak" } }
    }
}
