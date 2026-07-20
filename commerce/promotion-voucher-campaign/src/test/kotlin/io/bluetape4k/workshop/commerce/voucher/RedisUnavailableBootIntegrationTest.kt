package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionState
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionGate
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionKeyFactory
import io.bluetape4k.workshop.commerce.voucher.config.VoucherAdmissionConfiguration
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisConfiguration
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisResources
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

internal class RedisUnavailableBootIntegrationTest {
    @Test
    fun `unavailable Redis degrades without preventing application context startup`() {
        ApplicationContextRunner()
            .withUserConfiguration(
                VoucherAdmissionConfiguration::class.java,
                VoucherRedisConfiguration::class.java,
                TestClockConfiguration::class.java,
            ).withPropertyValues(
                "workshop.voucher.redis.enabled=true",
                "workshop.voucher.redis.uri=redis://127.0.0.1:1",
                "workshop.voucher.redis.command-timeout=50ms",
                "workshop.voucher.keys.current-version=1",
                "workshop.voucher.keys.redis-slot=${"r".repeat(32)}",
                "workshop.voucher.keys.risk=${"k".repeat(32)}",
            ).run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBean(VoucherRedisResources::class.java).bloomFilter shouldBeEqualTo null
                val keys = context.getBean(VoucherAdmissionKeyFactory::class.java)
                val gate = context.getBean(VoucherAdmissionGate::class.java)
                repeat(3) {
                    gate.decide(keys.rateKey("tenant-a", "principal-a", "allocate")) shouldBeEqualTo
                        AdmissionDecision.Proceed
                }
                gate.state() shouldBeEqualTo AdmissionState.DEGRADED
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class TestClockConfiguration {
        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }
}
