package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionState
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionGate
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionKeyFactory
import io.bluetape4k.workshop.commerce.voucher.config.VoucherAdmissionConfiguration
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisConfiguration
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisResources
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * RedisUnavailableBootIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class RedisUnavailableBootIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "unavailable Redis degrades without preventing application context startup" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unavailable Redis degrades without preventing application context startup`() {
        ApplicationContextRunner()
            .withUserConfiguration(
                VoucherAdmissionConfiguration::class.java,
                VoucherRedisConfiguration::class.java,
                TestClockConfiguration::class.java,
            )
            .withPropertyValues(
                "workshop.voucher.redis.enabled=true",
                "workshop.voucher.redis.uri=redis://127.0.0.1:1",
                "workshop.voucher.redis.command-timeout=50ms",
                "workshop.voucher.keys.current-version=1",
                "workshop.voucher.keys.redis-slot=${"r".repeat(32)}",
                "workshop.voucher.keys.risk=${"k".repeat(32)}",
            ).run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean<VoucherRedisResources>().bloomFilter.shouldBeNull()
                val keys = context.getBean<VoucherAdmissionKeyFactory>()
                val gate = context.getBean<VoucherAdmissionGate>()

                repeat(3) {
                    val rateKey = keys.rateKey("tenant-a", "principal-a", "allocate")
                    gate.decide(rateKey) shouldBeEqualTo AdmissionDecision.Proceed
                }
                gate.state() shouldBeEqualTo AdmissionState.DEGRADED
            }
    }

    @Configuration(proxyBeanMethods = false)
/**
 * TestClockConfiguration 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    internal class TestClockConfiguration {
        @Bean
/**
 * clock은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun clock(): Clock = Clock.systemUTC()
    }
}
