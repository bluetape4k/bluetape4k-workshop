package io.bluetape4k.workshop.resilience.retry

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ReactiveRetryTest: AbstractRetryTest() {

    companion object: KLogging()

    @Nested
    inner class MonoMethod {
        @Nested
        inner class BackendA {
            @Test
            fun `Backend A - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
                val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_A)

                procedureMonoFailure(BACKEND_A)

                // 3회 retry 후 실패를 반환한다
                checkMetrics(FAILED_WITH_RETRY, BACKEND_A, currentCount + 1)
            }

            @Test
            fun `Backend A - 호출 성공 시 success_with_retry 가 1 증가한다`() {
                val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A)

                procedureMonoSuccess(BACKEND_A)

                // 3회 retry 후 실패를 반환한다
                checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A, currentCount + 1)
            }
        }

        @Nested
        inner class BackendB {
            @Test
            fun `Backend B - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
                val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_B)

                procedureMonoFailure(BACKEND_B)

                // 3회 retry 후 실패를 반환한다
                checkMetrics(FAILED_WITH_RETRY, BACKEND_B, currentCount + 1)
            }

            @Test
            fun `Backend B - 호출 성공 시 success_with_retry 가 1 증가한다`() {
                val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B)

                procedureMonoSuccess(BACKEND_B)

                // 3회 retry 후 실패를 반환한다
                checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B, currentCount + 1)
            }
        }

        private fun procedureMonoFailure(serviceName: String) {
            webClient.get()
                .uri("/$serviceName/monoFailure")
                .exchange()
                .expectStatus().is5xxServerError
        }

        private fun procedureMonoSuccess(serviceName: String) {
            webClient.get()
                .uri("/$serviceName/monoSuccess")
                .exchange()
                .expectStatus().is2xxSuccessful
        }
    }

    @Nested
    inner class FluxMethod {

        @Nested
        inner class BackendA {
            @Test
            fun `Backend A - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
                val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_A)

                procedureFluxFailure(BACKEND_A)

                // 3회 retry 후 실패를 반환한다
                checkMetrics(FAILED_WITH_RETRY, BACKEND_A, currentCount + 1)
            }

            @Test
            fun `Backend A - 호출 성공 시 success_with_retry 가 1 증가한다`() {
                val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A)

                procedureFluxSuccess(BACKEND_A)

                // 3회 retry 후 실패를 반환한다
                checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_A, currentCount + 1)
            }
        }

        @Nested
        inner class BackendB {
            @Test
            fun `Backend B - 예외 발생 시 failed_with_retry 가 1 증가한다`() {
                val currentCount = getCurrentCount(FAILED_WITH_RETRY, BACKEND_B)

                procedureFluxFailure(BACKEND_B)

                // 3회 retry 후 실패를 반환한다
                checkMetrics(FAILED_WITH_RETRY, BACKEND_B, currentCount + 1)
            }

            @Test
            fun `Backend B - 호출 성공 시 success_with_retry 가 1 증가한다`() {
                val currentCount = getCurrentCount(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B)

                procedureFluxSuccess(BACKEND_B)

                // 3회 retry 후 실패를 반환한다
                checkMetrics(SUCCESSFUL_WITHOUT_RETRY, BACKEND_B, currentCount + 1)
            }
        }

        private fun procedureFluxFailure(serviceName: String) {
            webClient.get()
                .uri("/$serviceName/fluxFailure")
                .exchange()
                .expectStatus().is5xxServerError
        }

        private fun procedureFluxSuccess(serviceName: String) {
            webClient.get()
                .uri("/$serviceName/fluxSuccess")
                .exchange()
                .expectStatus().is2xxSuccessful
        }
    }

}
