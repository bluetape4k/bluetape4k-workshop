package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.support.requireNotNull
import org.awaitility.core.ConditionTimeoutException
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withAlias
import org.awaitility.kotlin.withPollInterval
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * TicketHighContentionAwait 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal object TicketHighContentionAwait {

/**
 * condition은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    inline fun condition(
        timeout: Duration,
        pollInterval: Duration,
        description: String,
        crossinline probe: () -> Boolean,
    ) {
/**
 * lastFailure은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val lastFailure = AtomicReference<Exception>()
        try {
            await withAlias description withPollInterval pollInterval atMost timeout until {
                try {
                    probe()
                } catch (error: Exception) {
                    lastFailure.set(error)
                    false
                }
            }
        } catch (timeoutFailure: ConditionTimeoutException) {
            throw IllegalStateException(description, lastFailure.get() ?: timeoutFailure)
        }
    }

/**
 * value은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun <T: Any> value(
        timeout: Duration,
        pollInterval: Duration,
        description: String,
        probe: () -> T?,
    ): T {
/**
 * value은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val value = AtomicReference<T>()
        condition(timeout, pollInterval, description) {
            probe()?.let {
                value.set(it)
                true
            } ?: false
        }
        return value.get().requireNotNull(description)
    }

/**
 * failure은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun failure(
        timeout: Duration,
        pollInterval: Duration,
        description: String,
        block: () -> Unit,
    ) {
        condition(timeout, pollInterval, description) {
            try {
                block()
                false
            } catch (_: Exception) {
                true
            }
        }
    }
}
