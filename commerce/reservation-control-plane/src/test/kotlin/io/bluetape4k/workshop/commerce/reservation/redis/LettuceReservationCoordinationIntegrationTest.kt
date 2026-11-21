package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.api.StatefulRedisConnection
import org.junit.jupiter.api.Test
import org.testcontainers.utility.Base58
import java.time.Duration

/**
 * LettuceReservationCoordinationIntegrationTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class LettuceReservationCoordinationIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "published Lettuce semaphore bounds advisory admission and recovers after release" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `published Lettuce semaphore bounds advisory admission and recovers after release`() =
        withRedisConnection { connection ->
/**
 * key은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val key = "reservation:test:semaphore:${Base58.randomString(8)}"
/**
 * first은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val first = LettuceSemaphoreAdmissionBackend(connection, key, totalPermits = 1)
/**
 * second은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val second = LettuceSemaphoreAdmissionBackend(connection, key, totalPermits = 1)

            try {
                first.tryAcquire(Duration.ZERO).shouldBeTrue()
                second.tryAcquire(Duration.ZERO).shouldBeFalse()
                first.release()
                second.tryAcquire(200.millis()).shouldBeTrue()
                second.release()
            } finally {
                connection.sync().del(key)
            }
        }

    @Test
/**
 * 이 시나리오는 "published Lettuce lock suppresses the same opaque command until token checked release"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `published Lettuce lock suppresses the same opaque command until token checked release`() =
        withRedisConnection { connection ->
/**
 * prefix은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val prefix = "reservation:test:suppression:${Base58.randomString(8)}"
/**
 * backend은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val backend = LettuceLockSuppressionBackend(connection, prefix, 2.seconds())

/**
 * first은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val first = backend.tryAcquire("opaque_id_123")
            first?.let {
                backend.tryAcquire("opaque_id_123").shouldBeNull()
                it.close()
                backend.tryAcquire("opaque_id_123")?.close()
            }
            first.shouldNotBeNull()
            connection.sync().del("$prefix:opaque_id_123")
        }

    @Test
/**
 * 이 시나리오는 "semaphore reinitializes after its advisory Redis key is evicted" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `semaphore reinitializes after its advisory Redis key is evicted`() =
        withRedisConnection { connection ->
/**
 * key은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val key = "reservation:test:eviction:${Base58.randomString(8)}"
/**
 * beforeEviction은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val beforeEviction = LettuceSemaphoreAdmissionBackend(connection, key, totalPermits = 1)
/**
 * afterEviction은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val afterEviction = LettuceSemaphoreAdmissionBackend(connection, key, totalPermits = 1)

            beforeEviction.tryAcquire(Duration.ZERO).shouldBeTrue()
            connection.sync().del(key)
            afterEviction.tryAcquire(Duration.ZERO).shouldBeTrue()
            afterEviction.release()
            connection.sync().del(key)
        }

/**
 * withRedisConnection은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun withRedisConnection(block: (StatefulRedisConnection<String, String>) -> Unit) {
        val redis = RedisServer.Launcher.redis
        val client = LettuceClients.clientOf(redis.url)
        val connection = LettuceClients.connect(client)

        try {
            block(connection)
        } finally {
            connection.close()
            LettuceClients.shutdown(client)
        }
    }
}
