//package simulations
//
//import io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers
//import io.gatling.javaapi.core.CoreDsl.scenario
//import io.gatling.javaapi.core.Simulation
//import io.gatling.javaapi.http.HttpDsl.http
//import io.gatling.javaapi.http.HttpDsl.status
//import kotlin.time.Duration.Companion.seconds
//import kotlin.time.toJavaDuration

//class VirtualThreadSimulation: Simulation() {
//
//    val httpProtocol = http
//        .baseUrl("http://localhost:8080")
//        .acceptHeader("*/*")
//
//    val scn = scenario("Virtual Thread Simulation")
//        .exec(
//            http("Simple Virtual Thread")
//                .get("/virtual-thread")
//                .check(status().`is`(200))
//        )
//        .exec(
//            http("Multi Virtual Thread")
//                .get("/virtual-thread/multi")
//                .check(status().`is`(200))
//        )
//
//    init {
//        setUp(
//            scn.injectClosed(rampConcurrentUsers(10).to(400).during(30.seconds.toJavaDuration()))
//            // scn.injectOpen(constantUsersPerSec(20.0).during(5.seconds.toJavaDuration()))
//        ).protocols(httpProtocol)
//    }
//}
