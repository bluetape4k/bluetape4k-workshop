package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.workshop.operations.jobconsole.persistence.JobHistoryTable
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxTable
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import javax.sql.DataSource

/** Typed authority observations shared by the core and adapter high-contention fixtures. */
object JobConsoleAuthorityQueries {

    fun baseline(dataSource: DataSource): JobConsoleAuthorityBaseline = inTransaction(dataSource) {
        JobConsoleAuthorityBaseline(
            jobs = JobTable.selectAll().count(),
            effects = JobHistoryTable.selectAll().count(),
            receipts = JobOutboxTable.selectAll().count(),
        )
    }

    fun baseline(dataSource: DataSource, jobId: UUID): JobConsoleAuthorityBaseline = inTransaction(dataSource) {
        JobConsoleAuthorityBaseline(
            jobs = JobTable.selectAll().where { JobTable.jobId eq jobId }.count(),
            effects = JobHistoryTable.selectAll().where { JobHistoryTable.jobId eq jobId }.count(),
            receipts = JobOutboxTable.selectAll().where { JobOutboxTable.jobId eq jobId }.count(),
        )
    }

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

    fun activeJobCount(dataSource: DataSource): Long = inTransaction(dataSource) {
        JobTable
            .selectAll()
            .where { JobTable.state inList listOf("running", "cancel_requested") }
            .count()
    }

    fun queueVersionDivergenceCount(dataSource: DataSource): Long = inTransaction(dataSource) {
        JobTable.selectAll().where { JobTable.queueVersion neq JobTable.version }.count()
    }

    private fun <T> inTransaction(dataSource: DataSource, block: () -> T): T {
        val database = Database.connect(dataSource)
        return try {
            transaction(database) { block() }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    private data class OutboxLogicalEvent(
        val jobId: UUID,
        val eventType: String,
        val resourceVersion: Long,
    )
}
