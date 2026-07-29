// 주석 처리된 Gatling virtual thread simulation 예제입니다. 필요 시 package 선언부터 다시 활성화합니다.
// 단일/다중 virtual thread endpoint 를 closed workload 로 측정하는 참고 코드입니다.
// 아래 코드는 현재 빌드 대상이 아니며, 가상 스레드 처리량 실험을 재개할 때 기준선으로 쓰는 예제입니다.
// `Simulation`, `scenario`, `http`, `status`, `injectClosed` 같은 이름은 Gatling DSL 식별자라서 그대로 둡니다.
// 시나리오 이름과 엔드포인트 경로는 기존 리포트와 비교해야 하는 코드-facing 값이므로 번역하지 않습니다.
// 한국어 설명은 이 주석 블록이 왜 남아 있는지와 어떤 조건에서 다시 활성화하는지를 기록합니다.
//package simulations
//
//import io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers
//import io.gatling.javaapi.core.CoreDsl.scenario
//import io.gatling.javaapi.core.Simulation
//import io.gatling.javaapi.http.HttpDsl.http
//import io.gatling.javaapi.http.HttpDsl.status
//import kotlin.time.Duration.Companion.seconds
//import kotlin.time.toJavaDuration

//class VirtualThreadSimulation : Simulation() {
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
