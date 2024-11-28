package simulations

import io.bluetape4k.workshop.webflux.virtualthread.model.DispatcherType
import io.gatling.javaapi.core.ClosedInjectionStep
import io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.ScenarioBuilder
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpProtocolBuilder
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

object ScenarioProvider {

    const val BASE_URL = "http://localhost:8080"

    fun getHttpProtocol(): HttpProtocolBuilder = http
        .baseUrl(BASE_URL)
        .acceptHeader("*/*")

    fun getScenario(dispatcherType: DispatcherType): ScenarioBuilder {
        val basePath = dispatcherType.code
        return scenario("$dispatcherType Simulation")
            .exec(http("Suspend").get("/$basePath/suspend"))
            .exec(http("Deferred").get("/$basePath/deferred"))
            .exec(http("Sequential flow").get("/$basePath/sequential-flow"))
            .exec(http("Concurrent flow").get("/$basePath/concurrent-flow"))
    }

    fun getRampConcurrentUsers(
        start: Int = 10,
        finish: Int = 400,
        duration: Duration = 30.seconds.toJavaDuration(),
    ): ClosedInjectionStep {
        return rampConcurrentUsers(start).to(finish).during(duration)
    }
}
