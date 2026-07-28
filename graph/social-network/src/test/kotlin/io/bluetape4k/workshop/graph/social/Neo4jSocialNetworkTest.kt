package io.bluetape4k.workshop.graph.social

import io.bluetape4k.graph.neo4j.Neo4jGraphOperations
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.graphdb.Neo4jServer
import io.bluetape4k.workshop.graph.social.service.SocialNetworkService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

/**
 * Neo4j Testcontainer가 뒷받침하는 [SocialNetworkService] 통합 테스트입니다.
 *
 * Docker가 필요합니다. 실행: `./gradlew :graph-social-network:integrationTest`
 * 기본 `:test` task는 `@Tag("integration")`으로 이 클래스를 제외합니다.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jSocialNetworkTest : AbstractSocialNetworkTest() {

    companion object : KLogging() {
        private val neo4j = Neo4jServer.Launcher.neo4j
    }

    override val graphName = "neo4j_social"

    private val driver: Driver by lazy {
        GraphDatabase.driver(neo4j.url)
    }

    override val ops: GraphOperations by lazy {
        Neo4jGraphOperations(driver)
    }

    override val service by lazy {
        SocialNetworkService(ops, graphName)
    }

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
            .onFailure { log.warn(it) { "Driver close failed during tearDown — possible resource leak" } }
    }
}
