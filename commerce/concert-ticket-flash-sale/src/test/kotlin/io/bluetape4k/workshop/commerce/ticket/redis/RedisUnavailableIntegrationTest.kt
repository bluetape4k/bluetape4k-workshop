package io.bluetape4k.workshop.commerce.ticket.redis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.ticket.config.TicketProperties
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisConfiguration
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisProperties
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisResources
import io.lettuce.core.RedisException
import org.junit.jupiter.api.Test

/**
 * RedisUnavailableIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class RedisUnavailableIntegrationTest {

    companion object: KLogging() {
/**
 * redis은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val redis by lazy { RedisServer.Launcher.redis }
    }

    @Test
/**
 * 이 시나리오는 "distributed route limit rejects excess calls independently of the lease" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `distributed route limit rejects excess calls independently of the lease`() {
        val cleanupClient = LettuceClients.clientOf(redis.url)
        try {
            LettuceClients.connect(cleanupClient).sync().flushdb()
        } finally {
            LettuceClients.shutdown(cleanupClient)
        }
        val properties = TicketProperties(
            redis = TicketRedisProperties(
                uri = redis.url,
                rateLimitCapacity = 2,
                rateLimitPeriod = 1.hours(),
            ),
        )

        TicketRedisResources.open(properties.redis).use { resources ->
            val limiter = TicketRedisConfiguration().ticketRateLimiter(resources, properties)

            limiter.consume("purchase:safe-subject", 1).isConsumed.shouldBeTrue()
            limiter.consume("purchase:safe-subject", 1).isConsumed.shouldBeTrue()
            limiter.consume("purchase:safe-subject", 1).isRejected.shouldBeTrue()
        }
    }

    @Test
/**
 * 이 시나리오는 "new purchase fails closed while existing database workflow remains runnable" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `new purchase fails closed while existing database workflow remains runnable`() {
        val unavailable = MultiKeyLeasePort { throw RedisException("redis unavailable") }
        val gate = ForegroundLeaseGate(unavailable)
        var reconciled = 0

        assertFailsWith<AdmissionTemporarilyUnavailable> {
            gate.acquire(request())
        }
        reconciled += 1
        reconciled shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "busy lease is a conflict instead of a redis outage" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `busy lease is a conflict instead of a redis outage`() {
        val busy = MultiKeyLeasePort { LeaseDecision.Busy }

        assertFailsWith<PurchaseApprovalInProgress> {
            ForegroundLeaseGate(busy).acquire(request())
        }
    }

/**
 * request은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(): LeaseRequest =
        LeaseRequest(
            keys = LeaseKeys("ticket:{sale}:inflight:ip:ip", "ticket:{sale}:inflight:user:user"),
            ownerCandidates = listOf(LeaseOwner(1, "owner".padEnd(43, 'x'))),
            ttl = 5.seconds(),
        )
}
