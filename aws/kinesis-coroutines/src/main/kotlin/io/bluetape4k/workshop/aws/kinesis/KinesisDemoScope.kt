package io.bluetape4k.workshop.aws.kinesis

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 데모가 소유한 job과 호출자가 소유한 collector를 분리하는 수명주기 경계입니다.
 *
 * collector registry는 job을 취소하지 않고 완료 여부만 관찰합니다. 따라서 일반 서비스
 * 호출자의 cancellation을 애플리케이션 scope가 가로채지 않습니다.
 */
class KinesisDemoScope(
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {

    private val supervisor = SupervisorJob()
    private val appScope = CoroutineScope(supervisor + dispatcher)
    private val appJobs = ConcurrentHashMap.newKeySet<Job>()
    private val callerCollectors = ConcurrentHashMap.newKeySet<Job>()
    private val lifecycleLock = Any()
    private var admissionClosed = false

    val appJobCount: Int
        get() = appJobs.size

    val callerCollectorCount: Int
        get() = callerCollectors.size

    /** runner가 생성한 job만 app-owned registry에 등록합니다. */
    fun launchDemo(block: suspend CoroutineScope.() -> Unit): Deferred<Unit> {
        val job = synchronized(lifecycleLock) {
            check(!admissionClosed) { "Kinesis demo scope admission is closed." }
            appScope.async(start = CoroutineStart.LAZY, block = block).also { candidate ->
                appJobs += candidate
                candidate.invokeOnCompletion { appJobs -= candidate }
            }
        }
        job.start()
        return job
    }

    /** caller의 현재 Job을 passive registry에 등록합니다. */
    fun registerCallerCollector(job: Job): Boolean = synchronized(lifecycleLock) {
        if (admissionClosed) return@synchronized false
        callerCollectors += job
        job.invokeOnCompletion { callerCollectors -= job }
        true
    }

    /** shutdown drain 전에 새 caller collector의 admission을 원자적으로 닫습니다. */
    fun closeAdmission() {
        synchronized(lifecycleLock) {
            admissionClosed = true
        }
    }

    /** Flow completion 시 registry 항목만 제거하며 caller Job을 취소하지 않습니다. */
    fun unregisterCallerCollector(job: Job) {
        callerCollectors -= job
    }

    /** 애플리케이션이 소유한 job만 취소하고 완료를 기다립니다. */
    suspend fun cancelAppJobs() {
        val jobs = synchronized(lifecycleLock) { appJobs.toList() }
        jobs.forEach { job ->
            job.cancelAndJoin()
        }
    }

    /** caller-owned collector는 취소하지 않고 bounded하게 완료를 관찰합니다. */
    suspend fun awaitCallerCollectorsEmpty(timeout: Duration): Boolean {
        if (hasCallerCollectors().not()) return true
        return withTimeoutOrNull(timeout.toMillis()) {
            while (hasCallerCollectors()) {
                delay(PASSIVE_POLL_MILLIS)
            }
            true
        } ?: false
    }

    override fun close() {
        closeAdmission()
        supervisor.cancel()
        appScope.cancel()
        appJobs.clear()
        callerCollectors.removeIf { !it.isActive }
    }

    private fun hasCallerCollectors(): Boolean = synchronized(lifecycleLock) {
        callerCollectors.isNotEmpty()
    }

    private companion object {
        const val PASSIVE_POLL_MILLIS: Long = 10L
    }
}
