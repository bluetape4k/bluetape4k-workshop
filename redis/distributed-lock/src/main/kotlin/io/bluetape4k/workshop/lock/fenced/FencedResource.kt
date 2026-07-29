package io.bluetape4k.workshop.lock.fenced

import io.bluetape4k.logging.KLogging
import kotlinx.atomicfu.atomic

/**
 * 단일 리소스의 fencing token을 CAS로 검증하는 가드입니다.
 *
 * ## 동작 계약
 * - CAS 루프로 지금까지 관측한 가장 큰 fencing token을 추적합니다.
 * - `apply(token, work)`는 `token < lastSeenToken`인 오래된 보유자에게 `null`을 반환합니다.
 * - 동일 토큰(`token == lastSeenToken`)은 **허용**합니다. 같은 lease 기간 안의 재진입을 모델링하며,
 *   이때 [work]가 멱등적이라고 가정합니다.
 * - 이 가드는 **메모리 전용**입니다. JVM을 재시작하면 상태가 초기화됩니다(워크숍 한계).
 *
 * ## 사용 예
 * ```kotlin
 * val resource = FencedResource(inventoryId)
 * val result = resource.apply(token) {
 *     store.applyChange(id, -qty)
 * }
 * if (result == null) return Rejected(token)
 * ```
 */
class FencedResource(val resourceId: Long) {

    companion object : KLogging()

    private val lastSeenToken = atomic(0L)

    /**
     * [token]이 오래되지 않았을 때만 [work]를 실행합니다.
     *
     * @return [work]의 결과. [token]이 지금까지 관측한 token보다 작으면 `null`입니다.
     */
    fun <T : Any> apply(token: Long, work: () -> T): T? {
        while (true) {
            val current = lastSeenToken.value
            if (token < current) return null  // 오래된 토큰: 엄격한 작음 비교
            if (lastSeenToken.compareAndSet(current, maxOf(current, token))) {
                return work()
            }
            // 동시 갱신에 CAS가 실패했으므로 재시도합니다.
        }
    }
}
