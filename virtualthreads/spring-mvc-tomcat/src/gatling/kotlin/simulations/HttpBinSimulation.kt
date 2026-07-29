// 주석 처리된 Gatling HttpBin simulation 예제입니다. 필요 시 package 선언부터 다시 활성화합니다.
// virtual thread endpoint 의 blocking 지연 응답을 closed workload 로 측정하는 참고 코드입니다.
// 아래 코드는 현재 빌드 대상이 아니며, 부하 시나리오를 다시 실험할 때 비교 기준으로 남겨 둔 예제입니다.
// `scenario`, `http`, `status`, `injectClosed` 같은 이름은 Gatling DSL 식별자라서 그대로 유지합니다.
// 엔드포인트 경로와 시나리오 이름도 리포트 비교에 쓰이는 코드-facing 값이므로 번역하지 않습니다.
// 이 파일의 한국어 설명은 비활성화된 예제의 목적, 재활성화 조건, 보존 이유를 명확히 하기 위한 것입니다.
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
//class HttpBinSimulation : Simulation() {
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
