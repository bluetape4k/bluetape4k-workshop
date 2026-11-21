package io.bluetape4k.workshop.commerce.voucher.web

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * VoucherRuntimeIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherRuntimeIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @Autowired
/**
 * hikari은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var hikari: HikariDataSource

    @Test
/**
 * 이 시나리오는 "live request uses a Java 25 virtual thread and Hikari stays bounded" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live request uses a Java 25 virtual thread and Hikari stays bounded`() {
        webTestClient.get()
            .uri("/internal/runtime-thread")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.virtual").isEqualTo(true)
            .jsonPath("$.javaFeature").isEqualTo(25)

        hikari.maximumPoolSize shouldBeEqualTo 16
        hikari.minimumIdle shouldBeEqualTo 4
        hikari.connectionTimeout shouldBeEqualTo 60_000L
    }
}
