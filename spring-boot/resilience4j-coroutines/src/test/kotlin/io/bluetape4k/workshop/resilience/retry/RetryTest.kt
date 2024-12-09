package io.bluetape4k.workshop.resilience.retry

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.tests.httpGet
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class RetryTest: AbstractRetryTest() {

    companion object: KLogging()

    @Nested
    inner class BackendA {
        @Test
        fun `Backend A - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_A)

            procedureFailure(BACKEND_A)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(FAILED_WITH_RETRY, BACKEND_A, currentCount + 1)
        }

        @Test
        fun `Backend A - 성공 호출 시 success_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A)

            procedureSuccess(BACKEND_A)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A, currentCount + 1)
        }
    }

    @Nested
    inner class BackendB {
        @Test
        fun `Backend B - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_B)

            procedureFailure(BACKEND_B)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(FAILED_WITH_RETRY, BACKEND_B, currentCount + 1)
        }

        @Test
        fun `Backend B - 성공 호출 시 success_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B)

            procedureSuccess(BACKEND_B)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B, currentCount + 1)
        }
    }

    private fun procedureFailure(serviceName: String) {
        webClient.httpGet("/$serviceName/failure", HttpStatus.INTERNAL_SERVER_ERROR)
    }

    private fun procedureSuccess(serviceName: String) {
        webClient.httpGet("/$serviceName/success")
    }
}
