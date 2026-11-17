package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import io.bluetape4k.workshop.operations.jobconsole.api.JobEventType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.select
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.sql.DataSource

data class OutboxClaim(
    val token: UUID,
    val events: List<JobEvent>,
)

class JobOutboxRepository(
    private val dataSource: DataSource,
) {
    private val jdbc = JobJdbcExecutor(dataSource)
    companion object: KLogging() {
        const val MAX_BATCH_SIZE: Int = 100
    }

    /**
     * PostgreSQL 전용 claim 제어는 SQL로 유지합니다. `FOR UPDATE SKIP LOCKED`, UUID 배열,
     * database clock 기반 interval은 이식 가능한 Exposed DSL 연산이 아닙니다.
     */
    fun claim(batchSize: Int, claimDuration: Duration): OutboxClaim = inTransaction { connection ->
        require(!claimDuration.isNegative && !claimDuration.isZero) { "claimDuration must be positive" }
        val boundedSize = batchSize.coerceIn(1, MAX_BATCH_SIZE)
        val token = Uuid.V7.nextId()
        val events = connection.prepareStatement(
            """
                    SELECT event_id, job_id, event_type, queue_version, occurred_at
                    FROM job_outbox
                    WHERE published_at IS NULL
                      AND (claim_token IS NULL OR claim_expires_at <= CURRENT_TIMESTAMP)
                    ORDER BY occurred_at, event_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                    """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, boundedSize)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            JobEvent(
                                eventId = result.getObject("event_id", UUID::class.java),
                                eventType = JobEventType.entries.single { it.wireValue == result.getString("event_type") },
                                jobId = result.getObject("job_id", UUID::class.java),
                                queueVersion = result.getLong("queue_version"),
                                occurredAt = result.getTimestamp("occurred_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }
        if (events.isNotEmpty()) {
            connection.prepareStatement(
                """
                    UPDATE job_outbox
                    SET claim_token = ?, claim_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
                    WHERE event_id = ANY (?)
                    """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, token)
                statement.setLong(2, claimDuration.toMillis())
                statement.setArray(3, connection.createArrayOf("uuid", events.map { it.eventId }.toTypedArray()))
                statement.executeUpdate()
            }
        }
        OutboxClaim(token, events)
    }

    fun markPublished(token: UUID, eventId: UUID): Boolean = jdbc.exposedTransaction {
        val updated =
            JobOutboxTable.update(
                where = {
                    (JobOutboxTable.eventId eq eventId) and
                        (JobOutboxTable.claimToken eq token) and
                        JobOutboxTable.publishedAt.isNull()
                },
            ) { statement ->
                statement[JobOutboxTable.publishedAt] = CurrentTimestamp
                statement[JobOutboxTable.claimToken] = null
                statement[JobOutboxTable.claimExpiresAt] = null
            }
        updated == 1 || isPublished(eventId)
    }

    fun release(token: UUID, eventId: UUID) {
        jdbc.exposedTransaction {
            JobOutboxTable.update(
                where = {
                    (JobOutboxTable.eventId eq eventId) and
                        (JobOutboxTable.claimToken eq token) and
                        JobOutboxTable.publishedAt.isNull()
                },
            ) { statement ->
                statement[JobOutboxTable.claimToken] = null
                statement[JobOutboxTable.claimExpiresAt] = null
            }
        }
    }

    fun oldestUnpublishedAge(now: Instant): Duration? =
        jdbc.exposedTransaction {
            val oldest = JobOutboxTable.occurredAt.min()
            JobOutboxTable
                .select(oldest)
                .where { JobOutboxTable.publishedAt.isNull() }
                .singleOrNull()
                ?.get(oldest)
                ?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) }
        }

    /** `markPublished`가 연 Exposed transaction 안에서 호출해야 합니다. */
    private fun isPublished(eventId: UUID): Boolean =
        JobOutboxTable
            .select(JobOutboxTable.publishedAt)
            .where { JobOutboxTable.eventId eq eventId }
            .singleOrNull()
            ?.get(JobOutboxTable.publishedAt) != null

    private fun <T> inTransaction(block: (Connection) -> T): T = jdbc.transaction(block)
}
