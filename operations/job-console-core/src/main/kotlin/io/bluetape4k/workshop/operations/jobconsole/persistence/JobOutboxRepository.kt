package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import io.bluetape4k.workshop.operations.jobconsole.api.JobEventType
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class OutboxClaim(
    val token: UUID,
    val events: List<JobEvent>,
)

class JobOutboxRepository(
    private val dataSource: DataSource,
) {
    fun claim(batchSize: Int, claimDuration: Duration): OutboxClaim =
        inTransaction { connection ->
            require(!claimDuration.isNegative && !claimDuration.isZero) { "claimDuration must be positive" }
            val boundedSize = batchSize.coerceIn(1, MAX_BATCH_SIZE)
            val token = UUID.randomUUID()
            val events =
                connection.prepareStatement(
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

    fun markPublished(token: UUID, eventId: UUID): Boolean =
        inTransaction { connection ->
            val updated =
                connection.prepareStatement(
                    """
                    UPDATE job_outbox
                    SET published_at = CURRENT_TIMESTAMP, claim_token = NULL, claim_expires_at = NULL
                    WHERE event_id = ? AND claim_token = ? AND published_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, eventId)
                    statement.setObject(2, token)
                    statement.executeUpdate()
                }
            updated == 1 || isPublished(connection, eventId)
        }

    fun release(token: UUID, eventId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE job_outbox SET claim_token = NULL, claim_expires_at = NULL WHERE event_id = ? AND claim_token = ? AND published_at IS NULL",
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.setObject(2, token)
                statement.executeUpdate()
            }
        }
    }

    fun oldestUnpublishedAge(now: Instant): Duration? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT MIN(occurred_at) FROM job_outbox WHERE published_at IS NULL").use { statement ->
                statement.executeQuery().use { result ->
                    result.next()
                    result.getTimestamp(1)?.toInstant()?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) }
                }
            }
        }

    private fun isPublished(connection: Connection, eventId: UUID): Boolean =
        connection.prepareStatement("SELECT published_at IS NOT NULL FROM job_outbox WHERE event_id = ?").use { statement ->
            statement.setObject(1, eventId)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }

    private fun <T> inTransaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            }
        }

    companion object {
        const val MAX_BATCH_SIZE: Int = 100
    }
}
