package simulations

import io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class JpaSimulation: Simulation() {

    val httpProtocol = http
        .baseUrl("http://localhost:8080")
        .acceptHeader("*/*")

    val scn = scenario("JPA Operations Simulation")
        .exec(
            http("Find all teams")
                .get("/team")
                .check(status().`is`(200))
        )
        .exec(
            http("Find member by id")
                .get("/member/2")
                .check(status().`is`(200))
        )

    init {
        setUp(
            scn.injectClosed(rampConcurrentUsers(10).to(50).during(10.seconds.toJavaDuration()))
            // scn.injectOpen(constantUsersPerSec(20.0).during(5.seconds.toJavaDuration()))
        ).protocols(httpProtocol)
    }
}
