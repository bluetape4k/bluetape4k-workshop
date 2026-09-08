package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.coroutines.DefaultCoroutineScope
import kotlinx.coroutines.isActive

/**
 * leader audit lifecycle collector가 공유하는 application-owned coroutine scope입니다.
 *
 * 각 Spring application context가 독립 [DefaultCoroutineScope]를 소유합니다. 상위 구현의
 * `SupervisorJob`과 idempotent `close()` 계약을 그대로 사용하므로 한 context의 종료가
 * 재시작된 다른 context의 collector를 취소하지 않습니다.
 */
class JobSafetyAuditScope : DefaultCoroutineScope() {

    /** scope의 root job이 아직 실행 중인지 반환합니다. */
    val isActive: Boolean
        get() = coroutineContext.isActive
}
