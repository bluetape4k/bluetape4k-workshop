package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import java.time.Instant
import java.util.UUID

/**
 * `SafeLeaderHistoryRecorder`에 audit event admission만 제공하는 workshop sink입니다.
 *
 * PostgreSQL history는 별도의 authoritative 저장소가 소유하므로 이 sink는
 * `LeaderHistoryKey`를 생성하는 동안에만 token을 유지하고, record나 key를 저장하지
 * 않습니다. completion/failure는 upstream `ExportingLeaderHistorySink`가 exporter로
 * 전달할 수 있도록 no-op으로 처리합니다.
 */
internal class AdmissionOnlyLeaderHistorySink : LeaderHistorySink {

    override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey = LeaderHistoryKey(
        historyId = UUID.randomUUID().toString(),
        lockName = record.lockName,
        token = record.token,
        slotId = record.slotId,
    )

    override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit

    override fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    ) = Unit

    override fun deleteOlderThan(cutoff: Instant, limit: Int): Int = 0
}
