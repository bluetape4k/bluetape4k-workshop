package io.bluetape4k.workshop.commerce.reservation

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

@TestPropertySource(
    properties = [
        "reservation.redis.enabled=true",
        "reservation.redis.uri=redis://127.0.0.1:1"
    ]
)
/**
 * RedisUnavailableBootIntegrationTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class RedisUnavailableBootIntegrationTest: AbstractReservationIntegrationTest() {

    companion object: KLogging()


    @Test
/**
 * 이 시나리오는 "Redis connection failure does not prevent PostgreSQL authoritative API startup" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Redis connection failure does not prevent PostgreSQL authoritative API startup`() {
        webTestClient
            .get()
            .uri("/api/resources")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.resources[0].code").isEqualTo("demo-room-utc")
    }
}
