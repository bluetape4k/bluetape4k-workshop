package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import java.util.concurrent.TimeoutException
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 테스트 한 번에서 공유하는 단조 증가 시계 기반 누적 deadline입니다.
 *
 * 각 단계는 부모 deadline보다 길어질 수 없으며, blocking 호출은 이 객체의
 * 남은 시간만 사용해야 합니다. 벽시계 변경은 timeout 계산에 영향을 주지
 * 않습니다.
 */
class KafkaFailoverDeadline private constructor(
    private val deadlineNanos: Long,
    private val nowNanos: () -> Long,
) {

    /** 현재 deadline까지 남은 나노초입니다. 항상 0 이상입니다. */
    fun remainingNanos(): Long = (deadlineNanos - nowNanos()).coerceAtLeast(0L)

    /** 현재 deadline까지 남은 [Duration]입니다. */
    fun remaining(): Duration = remainingNanos().nanoseconds

    /** 부모 deadline 안에서 더 짧은 단계 deadline을 만듭니다. */
    fun child(timeout: Duration): KafkaFailoverDeadline {
        require(timeout.isFinite() && !timeout.isNegative()) {
            "timeout must be finite and non-negative"
        }
        val now = nowNanos()
        val delta = timeout.inWholeNanoseconds
        val candidate = if (delta > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delta
        return KafkaFailoverDeadline(min(deadlineNanos, candidate), nowNanos)
    }

    /**
     * 이미 만료된 단계는 실행하지 않고, 실패 원인에 phase를 보존합니다.
     * 실제 Future/Client 호출은 호출자가 이 deadline의 남은 시간을 API timeout으로
     * 전달해야 하며, 이 함수는 그 경계에서 공통 만료 검사를 제공합니다.
     */
    fun <T> awaitBlocking(phase: String, operation: () -> T): T {
        require(phase.isNotBlank()) { "phase must not be blank" }
        if (remainingNanos() == 0L) {
            throw TimeoutException("phase=$phase deadline exhausted")
        }
        return operation()
    }

    companion object {
        val MODULE_TIMEOUT: Duration = 420.seconds
        val SCENARIO_TIMEOUT: Duration = 180.seconds
        val STARTUP_TIMEOUT: Duration = 45.seconds
        val CLEANUP_TIMEOUT: Duration = 10.seconds

        /** 현재 [System.nanoTime]에서 [timeout]만큼 유효한 deadline을 만듭니다. */
        fun fromNow(timeout: Duration, nowNanos: () -> Long = System::nanoTime): KafkaFailoverDeadline {
            require(timeout.isFinite() && !timeout.isNegative()) {
                "timeout must be finite and non-negative"
            }
            val now = nowNanos()
            val delta = timeout.inWholeNanoseconds
            val deadline = if (delta > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delta
            return KafkaFailoverDeadline(deadline, nowNanos)
        }
    }
}
