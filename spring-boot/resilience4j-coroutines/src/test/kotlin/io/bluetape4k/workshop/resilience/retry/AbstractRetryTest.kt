package io.bluetape4k.workshop.resilience.retry

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import io.github.resilience4j.retry.RetryRegistry
import org.amshove.kluent.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.returnResult

abstract class AbstractRetryTest: AbstractResilienceTest() {

    companion object: KLogging() {
        const val FAILED_WITH_RETRY = "failed_with_retry"
        const val SUCCESSFUL_WITHOUT_RETRY = "successful_without_retry"
    }

    @Autowired
    protected val retryRegistry: RetryRegistry = uninitialized()

    protected fun getCurrentCount(kind: String, serviceName: String): Float {
        val metrics = retryRegistry.retry(serviceName).metrics

        return when (kind) {
            FAILED_WITH_RETRY        -> metrics.numberOfFailedCallsWithRetryAttempt.toFloat()
            SUCCESSFUL_WITHOUT_RETRY -> metrics.numberOfSuccessfulCallsWithoutRetryAttempt.toFloat()
            else                     -> 0F
        }.apply {
            log.debug { "Current Count=$this" }
        }
    }

    protected fun checkMetrics(kind: String, serviceName: String, count: Float) {
        webClient.get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectBody()
            .consumeWith {
                val body = String(it.responseBody!!)
                val metricName = getMetricName(kind, serviceName)
                log.debug { "metric=$metricName$count" }
                body shouldContain metricName + count
            }

        val body = webClient.httpGet("/actuator/prometheus")
            .returnResult<String>().responseBody
            .toStream()
            .toList()

        val metricName = getMetricName(kind, serviceName)
        log.debug { "metric=$metricName$count" }
        body shouldContain metricName + count
    }

    protected fun getMetricName(kind: String, serviceName: String): String? {
        // http://localhost:8080/actuator/prometheus
        /*
        resilience4j_retry_calls_total{application="resilience4j-demo",kind="failed_with_retry",name="backendA"} 0.0
        resilience4j_retry_calls_total{application="resilience4j-demo",kind="failed_with_retry",name="backendB"} 0.0
        resilience4j_retry_calls_total{application="resilience4j-demo",kind="failed_without_retry",name="backendA"} 0.0
        resilience4j_retry_calls_total{application="resilience4j-demo",kind="failed_without_retry",name="backendB"} 0.0
        resilience4j_retry_calls_total{application="resilience4j-demo",kind="successful_without_retry",name="backendA"} 0.0
        resilience4j_retry_calls_total{application="resilience4j-demo",kind="successful_without_retry",name="backendB"} 0.0
        resilience4j_retry_calls_total{application="resilience4j-demo",kind="successful_with_retry",name="backendB"} 0.0
        resilience4j_retry_calls_total{application="resilience4j-demo",kind="successful_with_retry",name="backendA"} 0.0
         */
        return """resilience4j_retry_calls_total{application="resilience4j-demo",kind="$kind",name="$serviceName"} """
    }
}
