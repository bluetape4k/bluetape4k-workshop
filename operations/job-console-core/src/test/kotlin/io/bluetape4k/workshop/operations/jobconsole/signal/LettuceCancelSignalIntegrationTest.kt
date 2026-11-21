package io.bluetape4k.workshop.operations.jobconsole.signal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Redis publish가 running worker를 깨우는 보조 신호일 뿐 PostgreSQL 상태 권위가 아님을 검증한다. */
/** Redis publish가 running worker를 깨우는 보조 신호일 뿐 PostgreSQL 상태 권위가 아님을 검증한다. */
@Tag("integration")
class LettuceCancelSignalIntegrationTest {

    companion object: KLogging() {
/**
 * redis은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val redis: RedisServer by lazy { RedisServer.Launcher.redis }
    }

    @Test
/**
 * 이 시나리오는 "publish wakes a subscribed worker without becoming state authority" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `publish wakes a subscribed worker without becoming state authority`() {
        val client = RedisClient.create("redis://${redis.host}:${redis.port}")

        client.connectPubSub().use { subscriber ->
            val messages = LinkedBlockingQueue<String>()
            subscriber.addListener(
                object : RedisPubSubAdapter<String, String>() {
/**
 * message은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                    override fun message(channel: String, message: String) {
                        messages.offer(message)
                    }
                },
            )
            subscriber.sync().subscribe(LettuceCancelSignal.CHANNEL)

            LettuceCancelSignal("redis://${redis.host}:${redis.port}").use { signal ->
                val jobId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")

                signal.publish(jobId).delivered.shouldBeTrue()
                messages.poll(2, TimeUnit.SECONDS) shouldBeEqualTo jobId.toString()
            }
        }
        client.shutdown()
    }
}
