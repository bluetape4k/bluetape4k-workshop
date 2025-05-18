//package simulations
//
//import io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers
//import io.gatling.javaapi.core.CoreDsl.scenario
//import io.gatling.javaapi.core.Simulation
//import io.gatling.javaapi.http.HttpDsl.http
//import io.gatling.javaapi.http.HttpDsl.status
//import kotlin.time.Duration.Companion.seconds
//import kotlin.time.toJavaDuration
//
//class HttpBinSimulation: Simulation() {
//
//    val httpProtocol = http
//        .baseUrl("http://localhost:8080")
//        .acceptHeader("*/*")
//
//    val scn = scenario("HttpBin Simulation")
//        .exec(
//            http("HttpBin Block 2")
//                .get("/httpbin/block/2")
//                .check(status().`is`(200))
//        )
//        .exec(
//            http("HttpBin Block 1")
//                .get("/httpbin/block/1")
//                .check(status().`is`(200))
//        )
//
//    init {
//        setUp(
//            scn.injectClosed(rampConcurrentUsers(10).to(400).during(30.seconds.toJavaDuration()))
//            //scn.injectOpen(constantUsersPerSec(30.0).during(5.seconds.toJavaDuration()))
//        ).protocols(httpProtocol)
//    }
//
//}
