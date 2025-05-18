package io.bluetape4k.workshop.resilience.exception

import java.util.function.Predicate

/**
 * Circuit Breaker에서 예외로 기록할 것인지 판단하는 [Predicate] 구현체
 */
class RecordFailurePredicate: Predicate<Throwable> {
    /**
     * 예외가 [BusinessException] 이 아니라면 True를 반환한다.
     */
    override fun test(ex: Throwable): Boolean {
        return ex !is BusinessException
    }
}
