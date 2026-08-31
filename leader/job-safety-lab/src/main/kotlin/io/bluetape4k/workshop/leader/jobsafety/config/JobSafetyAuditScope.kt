package io.bluetape4k.workshop.leader.jobsafety.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/** leader audit lifecycle collector가 공유하는 application-owned coroutine scope입니다. */
class JobSafetyAuditScope : CoroutineScope,
    AutoCloseable {

    private val rootJob = SupervisorJob()

    override val coroutineContext: CoroutineContext = rootJob + Dispatchers.Default

    /** scope의 root job이 아직 실행 중인지 반환합니다. */
    val isActive: Boolean
        get() = rootJob.isActive

    /** collector를 취소하고 새 작업을 받지 않습니다. */
    override fun close() {
        coroutineContext.cancel()
    }
}
