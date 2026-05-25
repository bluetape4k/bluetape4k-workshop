package io.bluetape4k.workshop.graph.recommendation

import io.bluetape4k.graph.memgraph.MemgraphGraphSuspendOperations
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.graphdb.MemgraphServer
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationSuspendService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

/**
 * [RecommendationSuspendService] integration tests backed by a Memgraph Testcontainer.
 *
 * Requires Docker. Run with: `./gradlew :graph-recommendation:integrationTest`
 * The default `:test` task excludes this class via `@Tag("integration")`.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemgraphRecommendationSuspendTest : AbstractRecommendationSuspendTest() {

    companion object : KLoggingChannel() {
        private val memgraph = MemgraphServer.Launcher.memgraph
    }

    override val graphName = "memgraph_recommendation_suspend"

    private val driver: Driver by lazy {
        GraphDatabase.driver(memgraph.boltUrl, AuthTokens.none())
    }

    override val ops: GraphSuspendOperations by lazy {
        MemgraphGraphSuspendOperations(driver)
    }

    override val service by lazy {
        RecommendationSuspendService(ops, graphName)
    }

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
            .onFailure { log.warn(it) { "Driver close failed during tearDown — possible resource leak" } }
    }
}
