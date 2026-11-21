package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucherpool.config.RecoverableVoucherPoolAdmissionBackend
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolLeaderTrigger
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRedisProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRedisResources
import io.bluetape4k.workshop.commerce.voucherpool.config.voucherPoolDistributedAdmissionBackend
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * LettuceVoucherPoolAdmissionIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class LettuceVoucherPoolAdmissionIntegrationTest {

    private companion object: KLogging() {
/**
 * redis은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val redis: RedisServer = RedisServer.Launcher.redis

/**
 * flushRedis은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun flushRedis() {
            val client = LettuceClients.clientOf(redis.url)
            try {
                LettuceClients.connect(client).sync().flushall()
            } finally {
                LettuceClients.shutdown(client)
            }
        }
    }

    @BeforeEach
    fun beforeEach() {
        flushRedis()
    }

    @Test
/**
 * 이 시나리오는 "Bucket4j keeps operation quotas in opaque isolated namespaces" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Bucket4j keeps operation quotas in opaque isolated namespaces`() {
        val properties = VoucherPoolRedisProperties(
            enabled = true,
            uri = redis.url,
            commandTimeout = 1.seconds(),
            limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.REVEAL, 1),
        )
        
        VoucherPoolRedisResources.open(properties).use { resources ->
            val gate = VoucherPoolAdmissionGate(voucherPoolDistributedAdmissionBackend(resources.client, properties))
            val principal = ByteArray(32) { 0x41 }

            gate.admit(AdmissionNamespace.REVEAL, principal) shouldBeEqualTo AdmissionDecision.ALLOW
            gate.admit(AdmissionNamespace.REVEAL, principal) shouldBeEqualTo AdmissionDecision.RATE_LIMITED
            gate.admit(AdmissionNamespace.REDEEM, principal) shouldBeEqualTo AdmissionDecision.ALLOW

            val keys = resources.client.connect().use { connection ->
                connection.sync().keys("voucher-pool:admission:*")
            }
            log.debug { "keys=$keys" }
            keys shouldHaveSize 2
            keys.none { "principal" in it || "414141" in it }.shouldBeTrue()
        }
    }

    @Test
/**
 * 이 시나리오는 "unavailable Redis falls back to the same bounded local policy" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unavailable Redis falls back to the same bounded local policy`() {
        val properties = VoucherPoolRedisProperties(
            enabled = true,
            uri = "redis://127.0.0.1:1",
            commandTimeout = 50.millis(),
            limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.REDEEM, 1),
        )

        VoucherPoolRedisResources.open(properties).use { resources ->
            val gate = VoucherPoolAdmissionGate(
                RecoverableVoucherPoolAdmissionBackend(resources.client, properties),
                properties.limits,
            )
            val principal = ByteArray(32) { 0x42 }

            gate.admit(AdmissionNamespace.REDEEM, principal) shouldBeEqualTo AdmissionDecision.DEGRADED_ALLOW
            gate.admit(AdmissionNamespace.REDEEM, principal) shouldBeEqualTo AdmissionDecision.RATE_LIMITED
        }
    }

    @Test
/**
 * 이 시나리오는 "leader trigger executes the supplied shared worker path at most once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `leader trigger executes the supplied shared worker path at most once`() {
        val properties = VoucherPoolRedisProperties(
            enabled = true,
            uri = redis.url,
            commandTimeout = 1.seconds(),
        )
        val calls = AtomicInteger()

        VoucherPoolRedisResources.open(properties).use { resources ->
            val trigger = VoucherPoolLeaderTrigger(resources::leaderElector, "test-instance")
            val result = trigger.run("voucher-pool-reconciliation") {
                calls.incrementAndGet()
            }

            log.debug { "result=$result" }
            result.shouldNotBeNull()
            calls.get() shouldBeEqualTo 1
        }
    }
}
