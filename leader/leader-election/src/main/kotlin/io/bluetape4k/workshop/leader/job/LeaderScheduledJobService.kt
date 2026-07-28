package io.bluetape4k.workshop.leader.job

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * 등록된 [LeaderGuardedJob] bean을 fixed schedule로 dispatch하는 Spring service입니다.
 *
 * 선출된 leader instance만 각 job을 실행합니다. 다른 instance는 조용히 skip합니다.
 *
 * ## 동작 / 계약
 * - 중복된 [LeaderGuardedJob.lockName] 값은 `init {}`에서 감지하고 startup 때
 *   [IllegalStateException]을 발생시킵니다. 빠른 실패가 조용한 split-brain 실행을 막습니다.
 * - 등록된 job이 없으면 error가 아니라 warning을 log로 남깁니다.
 * - 각 job은 개별 `try/catch`로 감쌉니다. 한 job의 실패가 이후 job 실행을 막지 않습니다.
 * - 이 instance가 lock을 얻지 못하면 `runIfLeader`는 `null`을 반환합니다(skipped).
 *   non-null 반환은 leader에서 성공적으로 실행되었음을 뜻합니다.
 *
 * ## 사용 예
 * ```
 * # application.yml
 * leader:
 *   job-fixed-delay: PT10S
 * ```
 */
@Service
class LeaderScheduledJobService(
    private val leaderElector: LeaderElector,
    private val jobs: List<LeaderGuardedJob>,
) {
    init {
        val lockNames = jobs.map { it.lockName }
        val duplicates = lockNames.groupBy { it }.filter { it.value.size > 1 }.keys
        check(duplicates.isEmpty()) {
            "Duplicate lockNames detected in LeaderGuardedJob beans: $duplicates. " +
                "Each job must have a unique lockName."
        }
        if (jobs.isEmpty()) {
            log.warn { "No LeaderGuardedJob beans registered. The scheduler will run but skip all jobs." }
        } else {
            log.info { "LeaderScheduledJobService initialized with ${jobs.size} job(s): ${lockNames.joinToString()}" }
        }
    }

    /**
     * 등록된 모든 [LeaderGuardedJob] instance를 fixed delay로 실행합니다.
     * leader instance만 각 job body를 실행하고, 나머지는 skip합니다.
     */
    @Scheduled(fixedDelayString = "\${leader.job-fixed-delay:PT10S}")
    fun runJobs() {
        jobs.forEach { job ->
            try {
                val result = leaderElector.runIfLeader(job.lockName) {
                    job.execute()
                }
                if (result != null) {
                    log.info { "[LEADER] Job '${job.lockName}' executed successfully on this instance" }
                } else {
                    log.debug { "[SKIPPED] Job '${job.lockName}' — this instance is not the elected leader" }
                }
            } catch (e: Exception) {
                log.error(e) { "[ERROR] Job '${job.lockName}' threw an exception: ${e.message}" }
            }
        }
    }

    companion object : KLogging()
}
