package simulations

import io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class ExposedUserSimulation: Simulation() {

    val httpProtocol = http
        .baseUrl("http://localhost:8080")
        .acceptHeader("*/*")

    val scn = scenario("JPA Operations Simulation")
        .exec(
            http("Find all users")
                .get("/api/v1/users")
                .check(status().`is`(200))
        )
        .exec(
            http("Find user by id")
                .get("/api/v1/users/1")
                .check(status().`is`(200))
        )

    init {
        setUp(
            scn.injectClosed(rampConcurrentUsers(10).to(50).during(10.seconds.toJavaDuration()))
            // scn.injectOpen(constantUsersPerSec(20.0).during(5.seconds.toJavaDuration()))
        ).protocols(httpProtocol)
    }
}
