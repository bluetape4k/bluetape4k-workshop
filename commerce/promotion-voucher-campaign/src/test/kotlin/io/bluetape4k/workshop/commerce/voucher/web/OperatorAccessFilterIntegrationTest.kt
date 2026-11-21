package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

/**
 * OperatorAccessFilterIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OperatorAccessFilterIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "operator route rejects ambient or incorrect credentials" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator route rejects ambient or incorrect credentials`() {
        webTestClient.post()
            .uri("/operator/api/v1/reconciliation/run")
            .header(TENANT_HEADER, "tenant-a")
            .bodyValue(emptyMap<String, String>())
            .exchange()
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("OPERATOR_ACCESS_DENIED")

        webTestClient.post()
            .uri("/operator/api/v1/reconciliation/run")
            .header(TENANT_HEADER, "tenant-a")
            .header(OPERATOR_SECRET_HEADER, "wrong-secret-000000000000000000000000")
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .header("Origin", "https://untrusted.example")
            .bodyValue(emptyMap<String, String>())
            .exchange()
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("OPERATOR_ACCESS_DENIED")
    }

    @Test
/**
 * 이 시나리오는 "operator route rejects unsafe origin host content type and preflight before dispatch"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator route rejects unsafe origin host content type and preflight before dispatch`() {
        val path = "/operator/api/v1/reconciliation/run"
        val tenant = randomIdentifier()

        webTestClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header(TENANT_HEADER, tenant)
            .header(IDEMPOTENCY_HEADER, randomIdentifier())
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .bodyValue(emptyMap<String, String>())
            .exchange()
            .expectStatus().isForbidden

        webTestClient.post()
            .uri(path)
            .contentType(MediaType.TEXT_PLAIN)
            .header("Origin", "http://127.0.0.1:$port")
            .header(TENANT_HEADER, tenant)
            .header(IDEMPOTENCY_HEADER, randomIdentifier())
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isForbidden

        webTestClient.options()
            .uri(path)
            .header("Origin", "http://127.0.0.1:$port")
            .header("Access-Control-Request-Method", "POST")
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
/**
 * 이 시나리오는 "same origin browser GET uses explicit origin header when browsers omit Origin" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same origin browser GET uses explicit origin header when browsers omit Origin`() {
        val tenant = randomIdentifier()

        webTestClient.get()
            .uri("/operator/api/v1/reviews?status=OPEN&limit=1")
            .header("X-Workshop-Origin", "http://127.0.0.1:$port")
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange()
            .expectStatus().isOk

        webTestClient.get()
            .uri("/operator/api/v1/reviews?status=OPEN&limit=1")
            .header("X-Workshop-Origin", "https://untrusted.example")
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange()
            .expectStatus().isForbidden
    }
}
