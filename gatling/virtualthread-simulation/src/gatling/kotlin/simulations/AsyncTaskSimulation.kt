package simulations

import io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class AsyncTaskSimulation: Simulation() {

    val httpProtocol = http
        .baseUrl("http://localhost:8080")
        .acceptHeader("*/*")

    val scn = scenario("Asynchronous Task Simulation")
        .exec(
            http("Async Task 1")
                .get("/async/1")
                .check(status().`is`(200))
        )
        .exec(
            http("Async Task 2")
                .get("/async/2")
                .check(status().`is`(200))
        )

    init {
        setUp(
            scn.injectClosed(rampConcurrentUsers(10).to(20).during(10.seconds.toJavaDuration()))
            // scn.injectOpen(rampUsers(100).during(5.seconds.toJavaDuration()))
        ).protocols(httpProtocol)
    }
}
