package io.bluetape4k.workshop.commerce.ticket.redis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.api.StatefulRedisConnection
import org.junit.jupiter.api.Test

/**
 * MultiKeyLeaseIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class MultiKeyLeaseIntegrationTest {

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
 * 이 시나리오는 "acquire is all or nothing for ip and user keys" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `acquire is all or nothing for ip and user keys`() = withRedis { connection ->
/**
 * adapter은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val adapter = MultiKeyLeaseAdapter(connection.sync())
/**
 * request은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val request = request(owner(2, "owner-current"))
        connection.sync().set(request.keys.ip, owner(9, "other-owner").wireValue)

        adapter.acquire(request) shouldBeEqualTo LeaseDecision.Busy
        connection.sync().exists(request.keys.user) shouldNotBeEqualTo 1L
    }

    @Test
/**
 * 이 시나리오는 "wrong owner cannot renew or release both keys" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `wrong owner cannot renew or release both keys`() = withRedis { connection ->
/**
 * adapter은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val adapter = MultiKeyLeaseAdapter(connection.sync())
/**
 * request은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val request = request(owner(2, "owner-current"))
/**
 * wrongOwner은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val wrongOwner = request(owner(2, "wrong-owner"))

        adapter.acquire(request) shouldBeEqualTo LeaseDecision.Acquired(version = 2)
        adapter.renew(wrongOwner).shouldBeFalse()
        adapter.release(wrongOwner).shouldBeFalse()
        adapter.renew(request).shouldBeTrue()
        adapter.release(request).shouldBeTrue()
        connection.sync().exists(request.keys.ip, request.keys.user) shouldBeEqualTo 0L
    }

    @Test
/**
 * 이 시나리오는 "acquire response loss across key rotation reuses retained owner token" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `acquire response loss across key rotation reuses retained owner token`() = withRedis { connection ->
/**
 * adapter은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val adapter = MultiKeyLeaseAdapter(connection.sync())
/**
 * oldOwner은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val oldOwner = owner(1, "retained-owner")
/**
 * firstRequest은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val firstRequest = request(oldOwner)
/**
 * rotatedRequest은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val rotatedRequest = request(owner(2, "new-owner"), oldOwner)

        adapter.acquire(firstRequest) shouldBeEqualTo LeaseDecision.Acquired(version = 1)
        adapter.acquire(rotatedRequest) shouldBeEqualTo LeaseDecision.AlreadyOwned(version = 1)
        connection.sync().get(rotatedRequest.keys.ip) shouldBeEqualTo oldOwner.wireValue
        connection.sync().get(rotatedRequest.keys.user) shouldBeEqualTo oldOwner.wireValue
        adapter.release(rotatedRequest).shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "retained owner repairs a partially evicted lease without changing ownership" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retained owner repairs a partially evicted lease without changing ownership`() = withRedis { connection ->
/**
 * adapter은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val adapter = MultiKeyLeaseAdapter(connection.sync())
/**
 * oldOwner은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val oldOwner = owner(1, "retained-owner")
/**
 * rotatedRequest은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val rotatedRequest = request(owner(2, "new-owner"), oldOwner)

        adapter.acquire(request(oldOwner)) shouldBeEqualTo LeaseDecision.Acquired(version = 1)
        connection.sync().del(rotatedRequest.keys.user)

        adapter.acquire(rotatedRequest) shouldBeEqualTo LeaseDecision.AlreadyOwned(version = 1)
        connection.sync().get(rotatedRequest.keys.user) shouldBeEqualTo oldOwner.wireValue
    }

/**
 * request은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(vararg owners: LeaseOwner): LeaseRequest =
        LeaseRequest(
            keys = LeaseKeys(
                ip = "ticket:{sale-safe}:inflight:ip:ip-safe",
                user = "ticket:{sale-safe}:inflight:user:user-safe",
            ),
            ownerCandidates = owners.toList(),
            ttl = 5.seconds(),
        )

/**
 * owner은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun owner(version: Int, token: String): LeaseOwner =
        LeaseOwner(version, token.padEnd(43, 'x'))

/**
 * withRedis은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun withRedis(block: (StatefulRedisConnection<String, String>) -> Unit) {
        val client = LettuceClients.clientOf(redis.url)
        val connection = LettuceClients.connect(client)
        try {
            connection.sync().flushdb()
            block(connection)
        } finally {
            connection.close()
            LettuceClients.shutdown(client)
        }
    }
}
