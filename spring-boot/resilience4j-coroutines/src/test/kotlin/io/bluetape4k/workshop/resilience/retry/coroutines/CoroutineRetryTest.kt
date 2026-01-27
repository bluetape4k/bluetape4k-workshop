package io.bluetape4k.workshop.resilience.retry.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.resilience.retry.AbstractRetryTest
import io.bluetape4k.workshop.shared.web.httpGet
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CoroutineRetryTest: AbstractRetryTest() {

    companion object: KLoggingChannel()

    @Nested
    inner class BackendA {

        @Disabled("@Retry 어노테이션은 suspend 함수에 대해서는 적용되지 않는 버그가 있습니다. - CoDecorators를 사용하세요")
        @Test
        fun `Backend A - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_A)

            procedureSuspendFailure(BACKEND_A)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(FAILED_WITH_RETRY, BACKEND_A, currentCount + 1)
        }

        @Test
        fun `Backend A - 호출 성공 시 success_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A)

            procedureSuspendSuccess(BACKEND_A)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A, currentCount + 1)
        }
    }

    @Nested
    inner class BackendB {
        @Test
        fun `Backend B - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_B)

            procedureSuspendFailure(BACKEND_B)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(FAILED_WITH_RETRY, BACKEND_B, currentCount + 1)
        }

        @Test
        fun `Backend B - 호출 성공 시 success_with_retry 가 1 증가한다`() {
            val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B)

            procedureSuspendSuccess(BACKEND_B)

            // 3회 retry 후 실패를 반환한다
            checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B, currentCount + 1)
        }
    }

    private fun procedureSuspendFailure(serviceName: String) {
        webClient
            .httpGet("/coroutines/$serviceName/suspendFailure")
            .expectStatus().is5xxServerError
    }

    private fun procedureSuspendSuccess(serviceName: String) {
        webClient
            .httpGet("/coroutines/$serviceName/suspendSuccess")
            .expectStatus().is2xxSuccessful
    }
}
