package simulations

import io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class SyncTaskSimulation: Simulation() {

    val httpProtocol = http
        .baseUrl("http://localhost:8080")
        .acceptHeader("*/*")

    val scn = scenario("Synchronous Task Simulation")
        .exec(
            http("Sync Task 1")
                .get("/sync/1")
                .check(status().`is`(200))
        )
        .exec(
            http("Sync Task 2")
                .get("/sync/2")
                .check(status().`is`(200))
        )

    init {
        setUp(
            scn.injectClosed(rampConcurrentUsers(10).to(20).during(10.seconds.toJavaDuration()))
        ).protocols(httpProtocol)
    }
}
