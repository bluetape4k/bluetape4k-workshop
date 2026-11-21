package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.workshop.operations.jobconsole.persistence.JobJdbcExecutor
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobHistoryTable
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxTable
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import javax.sql.DataSource

/** Typed authority observations shared by the core and adapter high-contention fixtures. */
/** Typed authority observations shared by the core and adapter high-contention fixtures. */
object JobConsoleAuthorityQueries {

/**
 * baseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun baseline(dataSource: DataSource): JobConsoleAuthorityBaseline = inTransaction(dataSource) {
        JobConsoleAuthorityBaseline(
            jobs = JobTable.selectAll().count(),
            effects = JobHistoryTable.selectAll().count(),
            receipts = JobOutboxTable.selectAll().count(),
        )
    }

/**
 * baseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun baseline(dataSource: DataSource, jobId: UUID): JobConsoleAuthorityBaseline = inTransaction(dataSource) {
        JobConsoleAuthorityBaseline(
            jobs = JobTable.selectAll().where { JobTable.jobId eq jobId }.count(),
            effects = JobHistoryTable.selectAll().where { JobHistoryTable.jobId eq jobId }.count(),
            receipts = JobOutboxTable.selectAll().where { JobOutboxTable.jobId eq jobId }.count(),
        )
    }

/**
 * duplicateOutboxLogicalEventCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun duplicateOutboxLogicalEventCount(dataSource: DataSource): Long = inTransaction(dataSource) {
        JobOutboxTable
            .select(JobOutboxTable.jobId, JobOutboxTable.eventType, JobOutboxTable.resourceVersion)
            .map { row ->
                OutboxLogicalEvent(
                    jobId = row[JobOutboxTable.jobId],
                    eventType = row[JobOutboxTable.eventType],
                    resourceVersion = row[JobOutboxTable.resourceVersion],
                )
            }
            .groupingBy { it }
            .eachCount()
            .count { (_, count) -> count > 1 }
            .toLong()
    }

/**
 * burstQueueViolationCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun burstQueueViolationCount(dataSource: DataSource): Long = inTransaction(dataSource) {
        JobTable
            .select(JobTable.tenantId, JobTable.enqueueSequence)
            .groupBy { it[JobTable.tenantId] }
            .values
            .sumOf { tenantRows ->
                tenantRows
                    .sortedBy { it[JobTable.enqueueSequence] }
                    .withIndex()
                    .count { (index, row) -> row[JobTable.enqueueSequence] != index + 1L }
                    .toLong()
            }
    }

/**
 * activeJobCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun activeJobCount(dataSource: DataSource): Long = inTransaction(dataSource) {
        JobTable
            .selectAll()
            .where { JobTable.state inList listOf("running", "cancel_requested") }
            .count()
    }

/**
 * queueVersionDivergenceCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun queueVersionDivergenceCount(dataSource: DataSource): Long = inTransaction(dataSource) {
        JobTable.selectAll().where { JobTable.queueVersion neq JobTable.version }.count()
    }

/**
 * inTransaction은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T> inTransaction(dataSource: DataSource, block: () -> T): T =
        JobJdbcExecutor(dataSource).use { jdbc -> jdbc.exposedTransaction { block() } }

/**
 * OutboxLogicalEvent 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class OutboxLogicalEvent(
/**
 * jobId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val jobId: UUID,
/**
 * eventType은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val eventType: String,
/**
 * resourceVersion은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val resourceVersion: Long,
    )
}
