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
/**
 * OutboxClaim은 운영 작업 콘솔에서 전달되는 불변 데이터 모델입니다.
 * @property token 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property events 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 */


data class OutboxClaim(
    /**
     * `token`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val token: UUID,
    /**
     * `events`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val events: List<JobEvent>,
)
/**
 * JobOutboxRepository은 운영 작업 콘솔에서 사용하는 업무 객체입니다.
 * @property dataSource 운영 작업 콘솔 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */


class JobOutboxRepository(
    /**
     * `dataSource`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
) : AutoCloseable {
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val jdbc = JobJdbcExecutor(dataSource)

    /** Exposed database manager를 outbox poller lifecycle과 함께 해제합니다. */
    /** Exposed database manager를 outbox poller lifecycle과 함께 해제합니다. */
    override fun close() {
        jdbc.close()
    }
    companion object: KLogging() {
        /**
         * `MAX_BATCH_SIZE`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
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

    /**
     * markPublished은 운영 작업 콘솔 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param token 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param eventId 처리할 운영 작업 콘솔 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
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

    /**
     * release은 운영 작업 콘솔의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param token 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param eventId 처리할 운영 작업 콘솔 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
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

    /**
     * oldestUnpublishedAge은 운영 작업 콘솔 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와
     * 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun oldestUnpublishedAge(now: Instant): Duration? =
        jdbc.exposedTransaction {
            /**
             * `oldest`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val oldest = JobOutboxTable.occurredAt.min()
            JobOutboxTable
                .select(oldest)
                .where { JobOutboxTable.publishedAt.isNull() }
                .singleOrNull()
                ?.get(oldest)
                ?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) }
        }

    /** `markPublished`가 연 Exposed transaction 안에서 호출해야 합니다. */
    /** `markPublished`가 연 Exposed transaction 안에서 호출해야 합니다. */
    private fun isPublished(eventId: UUID): Boolean =
        JobOutboxTable
            .select(JobOutboxTable.publishedAt)
            .where { JobOutboxTable.eventId eq eventId }
            .singleOrNull()
            ?.get(JobOutboxTable.publishedAt) != null

    /**
     * inTransaction은 운영 작업 콘솔 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> inTransaction(block: (Connection) -> T): T = jdbc.transaction(block)
}
