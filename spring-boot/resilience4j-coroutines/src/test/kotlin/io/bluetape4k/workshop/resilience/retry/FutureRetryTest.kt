package io.bluetape4k.workshop.resilience.retry

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class FutureRetryTest: AbstractRetryTest() {

    companion object: KLoggingChannel()

    @Nested
    inner class BackendA {
        @Test
        fun `Backend A - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_A)

            procedureFutureFailure(BACKEND_A)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(FAILED_WITH_RETRY, BACKEND_A, currentCount + 1)
        }

        @Test
        fun `Backend A - 호출 성공 시 success_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A)

            procedureFutureSuccess(BACKEND_A)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A, currentCount + 1)
        }
    }

    @Nested
    inner class BackendB {
        @Test
        fun `Backend B - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_B)

            procedureFutureFailure(BACKEND_B)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(FAILED_WITH_RETRY, BACKEND_B, currentCount + 1)
        }

        @Test
        fun `Backend B - 호출 성공 시 success_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B)

            procedureFutureSuccess(BACKEND_B)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B, currentCount + 1)
        }
    }

    private fun procedureFutureFailure(serviceName: String) {
        webClient
            .get()
            .uri("/$serviceName/futureFailure")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    private fun procedureFutureSuccess(serviceName: String) {
        webClient
            .get()
            .uri("/$serviceName/futureSuccess")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.OK)
    }
}
