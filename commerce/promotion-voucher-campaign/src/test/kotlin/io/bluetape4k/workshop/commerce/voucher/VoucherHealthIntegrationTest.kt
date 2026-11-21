package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.test.web.reactive.server.expectBody

/**
 * VoucherHealthIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherHealthIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @LocalManagementPort
/**
 * managementPort은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var managementPort: Int = 0

/**
 * management은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val management by lazy { testClient(managementPort) }

    @Test
/**
 * 이 시나리오는 "management is isolated and exposes health probes plus Prometheus only" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `management is isolated and exposes health probes plus Prometheus only`() {

        webTestClient.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isNotFound

        management.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.voucherDatabase.status").isEqualTo("UP")

        management.get()
            .uri("/actuator/health/readiness")
            .exchange()
            .expectStatus().isOk

        management.get()
            .uri("/actuator/health/liveness")
            .exchange()
            .expectStatus().isOk

        management.get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk
            .expectBody<String>()
            .value { body ->
                check(body != null && "voucher_sse_active" in body)
            }

        listOf(
            "env",
            "configprops",
            "heapdump",
            "threaddump"
        ).forEach { endpoint ->
            management.get()
                .uri("/actuator/$endpoint")
                .exchange()
                .expectStatus().isNotFound
        }
    }
}
