package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.reservation.AbstractReservationIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["reservation.operator.enabled=true"])
/**
 * OperatorWebIntegrationTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OperatorWebIntegrationTest: AbstractReservationIntegrationTest() {

    companion object: KLogging() {
/**
 * OPERATOR_KEY은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val OPERATOR_KEY = "local-demo-operator-key-32-bytes-minimum"
    }

    @Test
/**
 * 이 시나리오는 "operator key protects idempotent force release and bounded manual sweep" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator key protects idempotent force release and bounded manual sweep`() {
        val owner = "operator-fixture-owner-0123456789abcdef0123456789abcdef"
        webTestClient.post()
            .uri("/api/resources/1/holds")
            .header("X-Reservation-Owner", owner)
            .header("Idempotency-Key", "operator-fixture-hold-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus().isCreated

        val waiter = "operator-waiter-0123456789abcdef0123456789abcdef"
        webTestClient.post()
            .uri("/api/resources/1/waitlist")
            .header("X-Reservation-Owner", waiter)
            .header("Idempotency-Key", "operator-waitlist-key-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":1,"policyVersion":1}""")
            .exchange()
            .expectStatus().isCreated

        val releaseBody = """{"expectedRevision":0,"reasonCode":"DEMO_RECOVERY"}"""
        webTestClient.post()
            .uri("/api/operator/holds/1/force-release")
            .header("Idempotency-Key", "operator-release-key-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(releaseBody)
            .exchange()
            .expectStatus().isForbidden

        webTestClient.post()
            .uri("/api/operator/holds/1/force-release")
            .header("X-Operator-Key", OPERATOR_KEY)
            .header("Idempotency-Key", "operator-release-key-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(releaseBody)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectBody()
            .jsonPath("$.state").isEqualTo("RELEASED_BY_OPERATOR")

        webTestClient.get()
            .uri("/api/waitlist/1")
            .header("X-Reservation-Owner", waiter)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("OFFERED")
            .jsonPath("$.offerId").isEqualTo(1)

        webTestClient.get()
            .uri("/api/resources")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resources[0].occupiedCount").isEqualTo(1)

        webTestClient
            .post()
            .uri("/api/operator/holds/1/force-release")
            .header("X-Operator-Key", OPERATOR_KEY)
            .header("Idempotency-Key", "operator-release-key-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(releaseBody)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "true")

        webTestClient
            .post()
            .uri("/api/operator/sweep")
            .header("X-Operator-Key", OPERATOR_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"maxResources":32}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.scannedResources").isEqualTo(0)
    }
}
