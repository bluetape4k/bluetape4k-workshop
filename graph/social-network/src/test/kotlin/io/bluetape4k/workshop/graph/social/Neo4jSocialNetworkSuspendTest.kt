package io.bluetape4k.workshop.graph.social

import io.bluetape4k.graph.neo4j.Neo4jGraphSuspendOperations
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.graphdb.Neo4jServer
import io.bluetape4k.workshop.graph.social.service.SocialNetworkSuspendService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

/**
 * [SocialNetworkSuspendService] integration tests backed by a Neo4j Testcontainer.
 *
 * Requires Docker. Run with: `./gradlew :graph-social-network:integrationTest`
 * The default `:test` task excludes this class via `@Tag("integration")`.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jSocialNetworkSuspendTest : AbstractSocialNetworkSuspendTest() {

    companion object : KLoggingChannel() {
        private val neo4j = Neo4jServer.Launcher.neo4j
    }

    override val graphName = "neo4j_social_suspend"

    private val driver: Driver by lazy {
        GraphDatabase.driver(neo4j.url)
    }

    override val ops: GraphSuspendOperations by lazy {
        Neo4jGraphSuspendOperations(driver)
    }

    override val service by lazy {
        SocialNetworkSuspendService(ops, graphName)
    }

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
            .onFailure { log.warn(it) { "Driver close failed during tearDown — possible resource leak" } }
    }
}
