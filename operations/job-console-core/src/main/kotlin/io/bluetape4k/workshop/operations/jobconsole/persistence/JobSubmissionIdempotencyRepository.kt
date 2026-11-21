package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.idempotency.AbandonReason
import io.bluetape4k.workshop.operations.jobconsole.idempotency.CleanupReport
import io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCommand
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionSnapshotPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PollResult
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PreparedJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.ReplayableJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.Reservation
import io.bluetape4k.workshop.operations.jobconsole.idempotency.WaiterRegistration
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

/**
 * JobSubmissionIdempotencyRepository은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface JobSubmissionIdempotencyRepository {
    /**
     * reserve은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun reserve(command: JobSubmissionCommand, now: Instant): Reservation

    /**
     * registerWaiter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param ownership 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun registerWaiter(ownership: InFlightOwnership, now: Instant): WaiterRegistration

    /**
     * registerWaiter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun registerWaiter(
        ownership: InFlightOwnership,
        now: Instant,
        waiterTtl: Duration,
    ): WaiterRegistration = registerWaiter(ownership, now)

    /**
     * registerWaiter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun registerWaiter(
        ownership: InFlightOwnership,
        now: Instant,
        waiterTtl: Duration,
        deadlineAt: Instant,
    ): WaiterRegistration = registerWaiter(ownership, now, waiterTtl)

    /**
     * removeWaiter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param keyHash 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param generation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param waiterToken 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun removeWaiter(scope: DemoCallerScope, keyHash: String, generation: Long, waiterToken: UUID): Boolean

    /**
     * poll은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param keyHash 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param generation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun poll(scope: DemoCallerScope, keyHash: String, generation: Long, now: Instant): PollResult

    /**
     * poll은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun poll(
        scope: DemoCallerScope,
        keyHash: String,
        generation: Long,
        now: Instant,
        statementTimeout: Duration,
    ): PollResult = poll(scope, keyHash, generation, now)

/**
 * withTransaction은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> withTransaction(block: (Connection) -> T): T

    /**
     * finalizeOwner은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun finalizeOwner(
        ownership: JobSubmissionOwnership,
        prepared: PreparedJobSubmission,
        now: Instant,
    ): ReplayableJobSubmission

    /**
     * finalizeOwner은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun finalizeOwner(
        connection: Connection,
        ownership: JobSubmissionOwnership,
        prepared: PreparedJobSubmission,
        now: Instant,
    ): ReplayableJobSubmission

    /**
     * abandon은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param ownership 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param reason 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun abandon(ownership: JobSubmissionOwnership, reason: AbandonReason, now: Instant): Boolean

    /**
     * cleanupExpired은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param batchSize 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun cleanupExpired(now: Instant, batchSize: Int = 100): CleanupReport
}

/**
 * caller thread를 붙잡거나 늦게 도착한 connection을 누수하지 않도록 pool 대기를 제한합니다.
 * PostgreSQL/Hikari는 일반적으로 interruption에 응답하며, hand-off guard는 caller deadline
 * 이후 provider가 반환한 connection도 닫습니다.
 */
internal class BoundedConnectionAcquirer(
    /**
     * `dataSource`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
    /**
     * `executor`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: ExecutorService = ForkJoinPool.commonPool(),
) {
    /**
     * acquire은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param timeout 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun acquire(timeout: Duration): Connection? {
        if (timeout.isZero || timeout.isNegative) return null
        val handoff = Handoff()
        val future =
            executor.submit<Connection> {
                val connection = dataSource.connection
                synchronized(handoff) {
                    if (handoff.timedOut) {
                        runCatching { connection.close() }
                        throw LateConnection()
                    }
                    handoff.connection = connection
                }
                connection
            }
        return try {
            val connection = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
            synchronized(handoff) { handoff.handedOff = true }
            connection
        } catch (_: TimeoutException) {
            cancel(handoff, future)
            null
        } catch (error: InterruptedException) {
            cancel(handoff, future)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    /**
     * cancel은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param handoff 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param future 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun cancel(handoff: Handoff, future: java.util.concurrent.Future<Connection>) {
        synchronized(handoff) {
            handoff.timedOut = true
            if (!handoff.handedOff) {
                handoff.connection?.let { runCatching { it.close() } }
                handoff.connection = null
            }
        }
        future.cancel(true)
    }

    /**
     * Handoff은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private class Handoff {
        /**
         * `timedOut`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        var timedOut = false
        /**
         * `handedOff`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        var handedOff = false
        /**
         * `connection`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        var connection: Connection? = null
    }

    /**
     * LateConnection은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private class LateConnection : RuntimeException()
}

/** 모든 상태 전이의 기준을 request row에 두는 JDBC 구현입니다. */
/** 모든 상태 전이의 기준을 request row에 두는 JDBC 구현입니다. */
internal class JdbcJobSubmissionIdempotencyRepository(
    /**
     * `dataSource`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
    /**
     * `jobRepository`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val jobRepository: JobRepository,
    /**
     * `policy`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val policy: JobSubmissionIdempotencyPolicy = JobSubmissionIdempotencyPolicy(),
    /**
     * `snapshotPolicy`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val snapshotPolicy: JobSubmissionSnapshotPolicy = JobSubmissionSnapshotPolicy(policy),
    /**
     * `executor`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: ExecutorService = ForkJoinPool.commonPool(),
) : JobSubmissionIdempotencyRepository, AutoCloseable {
    /**
     * `boundedConnectionAcquirer`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val boundedConnectionAcquirer = BoundedConnectionAcquirer(dataSource, executor)
    /**
     * `jdbc`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc = JobJdbcExecutor(dataSource)

    /** Exposed database manager를 bounded-wait service lifecycle과 함께 해제합니다. */
    /** Exposed database manager를 bounded-wait service lifecycle과 함께 해제합니다. */
    override fun close() {
        jdbc.close()
    }

    /**
     * reserve은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun reserve(command: JobSubmissionCommand, now: Instant): Reservation {
        var attempt = 0
        while (true) {
            try {
                return inTransaction(
                    connectionTimeout = policy.connectionAcquireTimeout,
                    block = { connection -> reserveInTransaction(connection, command) },
                    afterCommit = { connection, reservation ->
                        if (reservation is Reservation.Wait) {
                            // request 조회 시각은 lock/update 작업보다 앞설 수 있습니다. transaction이
                            // 외부에 commit된 뒤 PostgreSQL clock을 다시 읽어 waiter deadline의 기준을
                            // 실제 완료 시각에 맞춥니다.
                            reservation.copy(databaseNow = currentDatabaseTimestamp(connection))
                        } else {
                            reservation
                        }
                    },
                )
            } catch (failure: SQLException) {
                if (failure.sqlState != UNIQUE_VIOLATION_SQL_STATE || attempt++ >= MAX_RESERVE_RETRIES) throw failure
            } catch (_: ConnectionAcquireDeadlineExceeded) {
                return Reservation.Overflow
            }
        }
    }

    /** commit 이후 waiter deadline 기준을 맞추기 위한 PostgreSQL clock 조회입니다. */
    /** commit 이후 waiter deadline 기준을 맞추기 위한 PostgreSQL clock 조회입니다. */
    /** commit 직후에도 기존 transaction connection을 재사용하여 단일 pool을 보호합니다. */
    /** commit 직후에도 기존 transaction connection을 재사용하여 단일 pool을 보호합니다. */
    private fun currentDatabaseTimestamp(connection: Connection): Instant {
        val timeoutMillis = policy.statementTimeout.toMillis().coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong())
        connection.setNetworkTimeout(executor, timeoutMillis.toInt())
        applyStatementTimeout(connection, policy.statementTimeout)
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT clock_timestamp()").use { result ->
                check(result.next()) { "database clock query returned no row" }
                result.getTimestamp(1).toInstant()
            }
        }
    }

    /**
     * registerWaiter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param ownership 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun registerWaiter(ownership: InFlightOwnership, now: Instant): WaiterRegistration =
        registerWaiterInTransaction(ownership, now, policy.waiterTimeout, null)

    /**
     * registerWaiter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun registerWaiter(
        ownership: InFlightOwnership,
        now: Instant,
        waiterTtl: Duration,
    ): WaiterRegistration = registerWaiterInTransaction(ownership, now, waiterTtl, null)

    /**
     * registerWaiter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun registerWaiter(
        ownership: InFlightOwnership,
        now: Instant,
        waiterTtl: Duration,
        deadlineAt: Instant,
    ): WaiterRegistration = registerWaiterInTransaction(ownership, now, waiterTtl, deadlineAt)

    /**
     * registerWaiterInTransaction은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun registerWaiterInTransaction(
        ownership: InFlightOwnership,
        now: Instant,
        waiterTtl: Duration,
        deadlineAt: Instant?,
    ): WaiterRegistration {
        val boundedTtl = boundedRegistrationTtl(waiterTtl)
        val deadlineNanos = Math.addExact(System.nanoTime(), boundedTtl.toNanos())
        val result = inBoundedTransaction(deadlineNanos) { connection, statementBudgetNanos ->
            checkRegistrationStatementBudget(deadlineNanos, statementBudgetNanos)
            refreshNetworkTimeout(connection, deadlineNanos, statementBudgetNanos)
            val request = loadRequest(connection, ownership.ownership.scope, ownership.ownership.keyHash, forUpdate = true)
                ?: return@inBoundedTransaction WaiterRegistration.Overflow
            checkRegistrationStatementBudget(deadlineNanos, statementBudgetNanos)
            refreshNetworkTimeout(connection, deadlineNanos, statementBudgetNanos)
            if (request.state != RequestState.IN_FLIGHT || request.generation != ownership.ownership.generation) {
                return@inBoundedTransaction WaiterRegistration.Overflow
            }
            refreshNetworkTimeout(connection, deadlineNanos, statementBudgetNanos)
            val active = cleanupAndCountActiveWaiters(connection, request.scope, request.keyHash, request.generation)
            if (active >= policy.maxWaitersPerKey) return@inBoundedTransaction WaiterRegistration.Overflow
            checkRegistrationStatementBudget(deadlineNanos, statementBudgetNanos)
            refreshNetworkTimeout(connection, deadlineNanos, statementBudgetNanos)

            val waiterToken = UUID.randomUUID()
            val expiry = request.databaseNow
                .plusMillis(boundedTtl.toMillis().coerceAtLeast(1L))
                .let { candidate -> deadlineAt?.let(candidate::coerceAtMost) ?: candidate }
            JobRequestWaiterTable.insert { statement ->
                statement[JobRequestWaiterTable.tenantId] = request.scope.tenantId
                statement[JobRequestWaiterTable.submitterHash] = normalizedSubmitterHash(request.scope.submitterHash)
                statement[JobRequestWaiterTable.keyHash] = request.keyHash
                statement[JobRequestWaiterTable.generation] = request.generation
                statement[JobRequestWaiterTable.waiterToken] = waiterToken
                statement[JobRequestWaiterTable.expiresAt] = expiry
                statement[JobRequestWaiterTable.createdAt] = request.databaseNow
            }
            checkRegistrationStatementBudget(deadlineNanos, statementBudgetNanos)
            WaiterRegistration.Registered(waiterToken, request.generation)
        }
        return result ?: WaiterRegistration.DeadlineExceeded
    }

    /**
     * removeWaiter은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param keyHash 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param generation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param waiterToken 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun removeWaiter(scope: DemoCallerScope, keyHash: String, generation: Long, waiterToken: UUID): Boolean =
        inTransaction(connectionTimeout = policy.connectionAcquireTimeout) { _ ->
            JobRequestWaiterTable.deleteWhere {
                (JobRequestWaiterTable.tenantId eq scope.tenantId) and
                    (JobRequestWaiterTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                    (JobRequestWaiterTable.keyHash eq keyHash) and
                    (JobRequestWaiterTable.generation eq generation) and
                    (JobRequestWaiterTable.waiterToken eq waiterToken)
            } == 1
        }

    /**
     * poll은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param keyHash 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param generation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun poll(scope: DemoCallerScope, keyHash: String, generation: Long, now: Instant): PollResult =
        poll(scope, keyHash, generation, now, policy.statementTimeout)

    /**
     * poll은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun poll(
        scope: DemoCallerScope,
        keyHash: String,
        generation: Long,
        now: Instant,
        statementTimeout: Duration,
    ): PollResult =
        try {
            /**
             * `deadlineNanos`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val deadlineNanos = Math.addExact(System.nanoTime(), statementTimeout.toNanos())
            inTransaction(
                statementTimeout = statementTimeout,
                connectionTimeout = minOf(policy.connectionAcquireTimeout, statementTimeout),
                deadlineNanos = deadlineNanos,
            ) { connection ->
                /**
                 * `request`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val request = loadRequest(connection, scope, keyHash, forUpdate = false) ?: return@inTransaction PollResult.StillInFlight
                if (request.generation != generation) return@inTransaction PollResult.StillInFlight
                when (request.state) {
                    RequestState.TERMINAL -> PollResult.Terminal(request.snapshot(connection))
                    RequestState.ABANDONED -> PollResult.Abandoned(request.generation)
                    RequestState.IN_FLIGHT -> PollResult.StillInFlight
                }
            }
        } catch (_: ConnectionAcquireDeadlineExceeded) {
            PollResult.StillInFlight
        } catch (_: TransactionDeadlineExceeded) {
            PollResult.StillInFlight
        }

    /**
     * finalizeOwner은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun finalizeOwner(
        ownership: JobSubmissionOwnership,
        prepared: PreparedJobSubmission,
        now: Instant,
    ): ReplayableJobSubmission =
        withTransaction { connection -> finalizeOwner(connection, ownership, prepared, now) }

    /**
     * finalizeOwner은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun finalizeOwner(
        connection: Connection,
        ownership: JobSubmissionOwnership,
        prepared: PreparedJobSubmission,
        now: Instant,
    ): ReplayableJobSubmission {
        /**
         * response JSONB, database clock, interval, `RETURNING`을 한 번에 처리해야 하므로
         * 이 owner finalization statement는 PostgreSQL 전용 raw JDBC로 유지합니다.
         */
        val validated = snapshotPolicy.validate(prepared)
        val replayHeaders = validated.responseHeaders
        val enqueueSequence =
            jobRepository.insertSubmittedJob(
                connection = connection,
                scope = ownership.scope,
                request = prepared.request,
                jobId = ownership.jobId,
                now = now,
            ).enqueueSequence
        val updated =
            connection.prepareStatement(
                """
                UPDATE job_requests
                SET state = 'TERMINAL', owner_token = NULL, owner_lease_expires_at = NULL,
                    response_status = ?, response_body = ?, response_content_type = ?, response_headers = CAST(? AS JSONB),
                    terminal_at = CURRENT_TIMESTAMP,
                    retained_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ?
                  AND state = 'IN_FLIGHT' AND generation = ? AND owner_token = ?
                  AND owner_lease_expires_at > CURRENT_TIMESTAMP
                RETURNING job_id
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, validated.responseStatus)
                statement.setBytes(2, validated.responseBody)
                statement.setString(3, validated.responseContentType)
                statement.setString(4, encodeHeaders(replayHeaders))
                statement.setLong(5, policy.retention.toMillis())
                statement.setString(6, ownership.scope.tenantId)
                statement.setString(7, normalizedSubmitterHash(ownership.scope.submitterHash))
                statement.setString(8, ownership.keyHash)
                statement.setLong(9, ownership.generation)
                statement.setObject(10, ownership.ownerToken)
                statement.executeQuery().use { result -> if (result.next()) result.getObject("job_id", UUID::class.java) else null }
            }
        if (updated == null) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
        return ReplayableJobSubmission(
            jobId = updated,
            enqueueSequence = enqueueSequence,
            responseStatus = validated.responseStatus,
            responseBody = validated.responseBody.copyOf(),
            responseContentType = validated.responseContentType,
            responseHeaders = replayHeaders.mapValues { (_, values) -> values.toList() },
        )
    }

    /**
     * abandon은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param ownership 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param reason 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun abandon(ownership: JobSubmissionOwnership, reason: AbandonReason, now: Instant): Boolean =
        inTransaction(connectionTimeout = policy.connectionAcquireTimeout) { connection ->
            // lease/abandonment 경계는 애플리케이션 clock이 아니라 DB clock으로 계산합니다.
            connection.prepareStatement(
                """
                UPDATE job_requests
                SET state = 'ABANDONED', owner_token = NULL, owner_lease_expires_at = NULL,
                    abandoned_until = CURRENT_TIMESTAMP + interval '1 minute', updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ?
                  AND state = 'IN_FLIGHT' AND generation = ? AND owner_token = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, ownership.scope.tenantId)
                statement.setString(2, normalizedSubmitterHash(ownership.scope.submitterHash))
                statement.setString(3, ownership.keyHash)
                statement.setLong(4, ownership.generation)
                statement.setObject(5, ownership.ownerToken)
                statement.executeUpdate() == 1
            }
        }

    /**
     * cleanupExpired은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param batchSize 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun cleanupExpired(now: Instant, batchSize: Int): CleanupReport {
        require(batchSize > 0) { "batchSize must be positive" }
        /**
         * `ctid`, parent별 `SKIP LOCKED` 순서, CTE 삭제는 Exposed DSL로 동일한
         * PostgreSQL 동시성 의미를 표현하기 어려워 raw JDBC로 유지합니다.
         */
        return inTransaction(connectionTimeout = policy.connectionAcquireTimeout) { connection ->
            val waitersDeleted =
                connection.prepareStatement(
                    """
                    WITH parent_candidates AS MATERIALIZED (
                        SELECT request.tenant_id, request.submitter_hash, request.key_hash
                        FROM job_requests request
                        WHERE EXISTS (
                            SELECT 1
                            FROM job_request_waiters waiter
                            WHERE waiter.tenant_id = request.tenant_id
                              AND waiter.submitter_hash = request.submitter_hash
                              AND waiter.key_hash = request.key_hash
                              AND waiter.expires_at <= CURRENT_TIMESTAMP
                        )
                        ORDER BY request.updated_at
                        FOR UPDATE OF request SKIP LOCKED
                        LIMIT ?
                    ),
                    candidates AS (
                        SELECT waiter.ctid
                        FROM job_request_waiters waiter
                        JOIN parent_candidates request
                          ON request.tenant_id = waiter.tenant_id
                         AND request.submitter_hash = waiter.submitter_hash
                         AND request.key_hash = waiter.key_hash
                        WHERE waiter.expires_at <= CURRENT_TIMESTAMP
                        ORDER BY waiter.expires_at
                        LIMIT ?
                    )
                    DELETE FROM job_request_waiters waiter
                    USING candidates
                    WHERE waiter.ctid = candidates.ctid
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, batchSize)
                    statement.setInt(2, batchSize)
                    statement.executeUpdate()
                }
            val requestsDeleted =
                connection.prepareStatement(
                    """
                    WITH candidates AS (
                        SELECT candidate.ctid
                        FROM job_requests candidate
                        WHERE (
                            (candidate.state = 'TERMINAL' AND COALESCE(candidate.retained_until, candidate.created_at + INTERVAL '1 hour') <= CURRENT_TIMESTAMP)
                            OR (candidate.state = 'ABANDONED' AND candidate.abandoned_until <= CURRENT_TIMESTAMP)
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM job_request_waiters waiter
                            WHERE waiter.tenant_id = candidate.tenant_id
                              AND waiter.submitter_hash = candidate.submitter_hash
                              AND waiter.key_hash = candidate.key_hash
                        )
                        ORDER BY candidate.updated_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT ?
                    )
                    DELETE FROM job_requests request
                    USING candidates
                    WHERE request.ctid = candidates.ctid
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, batchSize)
                    statement.executeUpdate()
                }
            CleanupReport(waitersDeleted, requestsDeleted)
        }
    }

    /**
     * reserveInTransaction은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun reserveInTransaction(connection: Connection, command: JobSubmissionCommand): Reservation {
        val request = loadRequest(connection, command.scope, command.keyHash, forUpdate = true)
        if (request == null) {
            val ownership = insertInFlightRequest(connection, command)
            return Reservation.Owner(ownership)
        }
        if (request.state == RequestState.TERMINAL && request.retentionExpired) {
            return Reservation.Owner(takeOver(connection, request, command, incrementGeneration = true))
        }
        if (request.requestFingerprint != command.requestFingerprint) return Reservation.Conflict
        return when (request.state) {
            RequestState.TERMINAL ->
                Reservation.Replay(request.snapshot(connection))
            RequestState.ABANDONED -> {
                deleteExpiredWaiters(connection, request.scope, request.keyHash, request.generation)
                if (activeWaiterCount(connection, request.scope, request.keyHash, request.generation) > 0) {
                    Reservation.Abandoned
                } else {
                    Reservation.Owner(takeOver(connection, request, command, incrementGeneration = true))
                }
            }
            RequestState.IN_FLIGHT -> {
                if (request.leaseExpired) {
                    Reservation.Owner(takeOver(connection, request, command, incrementGeneration = false))
                } else {
                    Reservation.Wait(request.ownership(), request.databaseNow)
                }
            }
        }
    }

    /**
     * insertInFlightRequest은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param command 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun insertInFlightRequest(connection: Connection, command: JobSubmissionCommand): JobSubmissionOwnership {
        val jobId = Uuid.V7.nextId()
        val ownerToken = UUID.randomUUID()
        val row =
            connection.prepareStatement(
                """
                INSERT INTO job_requests(
                    tenant_id, submitter_hash, key_hash, request_fingerprint, job_id, created_at,
                    state, generation, owner_token, owner_lease_expires_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'IN_FLIGHT', 1, ?,
                          CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), CURRENT_TIMESTAMP)
                RETURNING generation, owner_lease_expires_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, command.scope.tenantId)
                statement.setString(2, normalizedSubmitterHash(command.scope.submitterHash))
                statement.setString(3, command.keyHash)
                statement.setString(4, command.requestFingerprint)
                statement.setObject(5, jobId)
                statement.setObject(6, ownerToken)
                statement.setLong(7, policy.ownerLease.toMillis())
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong("generation") to result.getTimestamp("owner_lease_expires_at").toInstant()
                }
            }
        return JobSubmissionOwnership(command.scope, command.keyHash, row.first, jobId, ownerToken, row.second)
    }

    /**
     * takeOver은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun takeOver(
        connection: Connection,
        request: RequestRow,
        command: JobSubmissionCommand,
        incrementGeneration: Boolean,
    ): JobSubmissionOwnership {
        val jobId = Uuid.V7.nextId()
        val ownerToken = UUID.randomUUID()
        val generation = if (incrementGeneration) request.generation + 1L else request.generation
        val sqlGeneration = if (incrementGeneration) "generation + 1" else "generation"
        val row =
            connection.prepareStatement(
                """
                UPDATE job_requests
                SET request_fingerprint = ?, state = 'IN_FLIGHT', generation = $sqlGeneration, job_id = ?,
                    owner_token = ?, owner_lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    abandoned_until = NULL, response_status = NULL, response_body = NULL,
                    response_content_type = NULL, response_headers = NULL, terminal_at = NULL,
                    retained_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ? AND generation = ?
                RETURNING generation, owner_lease_expires_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, command.requestFingerprint)
                statement.setObject(2, jobId)
                statement.setObject(3, ownerToken)
                statement.setLong(4, policy.ownerLease.toMillis())
                statement.setString(5, request.scope.tenantId)
                statement.setString(6, normalizedSubmitterHash(request.scope.submitterHash))
                statement.setString(7, request.keyHash)
                statement.setLong(8, request.generation)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                    result.getLong("generation") to result.getTimestamp("owner_lease_expires_at").toInstant()
                }
            }
        return JobSubmissionOwnership(command.scope, command.keyHash, row.first, jobId, ownerToken, row.second)
    }

    /**
     * loadRequest은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun loadRequest(
        connection: Connection,
        scope: DemoCallerScope,
        keyHash: String,
        forUpdate: Boolean,
    ): RequestRow? {
        if (!supportsExposed(connection)) return loadRequestLegacy(connection, scope, keyHash, forUpdate)
        var query = JobRequestTable
            .selectAll()
            .where {
                (JobRequestTable.tenantId eq scope.tenantId) and
                    (JobRequestTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                    (JobRequestTable.keyHash eq keyHash)
            }
        if (forUpdate) query = query.forUpdate()
        val row = query.singleOrNull() ?: return null
        // lock 대기 이후의 실제 DB 시각을 같은 connection에서 다시 읽습니다.
        // JobRequestTable.select(CurrentTimestamp)는 모든 request row를 반환하므로 single()을
        // 사용하면 다른 request가 존재할 때 오히려 lease 판정이 실패합니다.
        val databaseNow = currentDatabaseTimestamp(connection)
        val jobId = row[JobRequestTable.jobId]
        val enqueueSequence = JobTable
            .select(JobTable.enqueueSequence)
            .where { JobTable.jobId eq jobId }
            .singleOrNull()
            ?.get(JobTable.enqueueSequence)
        val ownerLeaseExpiresAt = row[JobRequestTable.ownerLeaseExpiresAt]
        val retainedUntil = row[JobRequestTable.retainedUntil]
        return RequestRow(
            scope = scope,
            keyHash = keyHash,
            requestFingerprint = row[JobRequestTable.requestFingerprint].trim(),
            jobId = jobId,
            enqueueSequence = enqueueSequence,
            state = RequestState.valueOf(row[JobRequestTable.state]),
            generation = row[JobRequestTable.generation],
            ownerToken = row[JobRequestTable.ownerToken],
            ownerLeaseExpiresAt = ownerLeaseExpiresAt,
            responseStatus = row[JobRequestTable.responseStatus],
            responseBody = row[JobRequestTable.responseBody],
            responseContentType = row[JobRequestTable.responseContentType],
            responseHeaders = row[JobRequestTable.responseHeaders],
            abandonedUntil = row[JobRequestTable.abandonedUntil],
            databaseNow = databaseNow,
            leaseExpired = ownerLeaseExpiresAt?.let { it <= databaseNow } ?: false,
            retentionExpired = (retainedUntil ?: row[JobRequestTable.createdAt].plus(Duration.ofHours(1))) <= databaseNow,
        )
    }

    /** metadata가 없는 test double은 Exposed transaction을 열 수 없어 읽기 전용 probe 경로를 유지합니다. */
    /** metadata가 없는 test double은 Exposed transaction을 열 수 없어 읽기 전용 probe 경로를 유지합니다. */
    private fun loadRequestLegacy(
        connection: Connection,
        scope: DemoCallerScope,
        keyHash: String,
        forUpdate: Boolean,
    ): RequestRow? =
        connection.prepareStatement(
            """
            SELECT job_requests.request_fingerprint, job_requests.job_id AS job_id, job_requests.state,
                   job_requests.generation, job_requests.owner_token, job_requests.owner_lease_expires_at,
                   response_status, response_body, response_content_type, response_headers,
                   abandoned_until, clock_timestamp() AS database_now,
                   owner_lease_expires_at <= CURRENT_TIMESTAMP AS lease_expired,
                   COALESCE(job_requests.retained_until, job_requests.created_at + INTERVAL '1 hour') <= CURRENT_TIMESTAMP AS retention_expired,
                   jobs.enqueue_sequence
            FROM job_requests
            LEFT JOIN jobs ON jobs.job_id = job_requests.job_id
            WHERE job_requests.tenant_id = ? AND job_requests.submitter_hash = ? AND job_requests.key_hash = ?
            ${if (forUpdate) " FOR UPDATE OF job_requests" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, scope.tenantId)
            statement.setString(2, normalizedSubmitterHash(scope.submitterHash))
            statement.setString(3, keyHash)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                RequestRow(
                    scope = scope,
                    keyHash = keyHash,
                    requestFingerprint = result.getString("request_fingerprint").trim(),
                    jobId = result.getObject("job_id", UUID::class.java),
                    enqueueSequence = result.getObject("enqueue_sequence")?.let { (it as Number).toLong() },
                    state = RequestState.valueOf(result.getString("state")),
                    generation = result.getLong("generation"),
                    ownerToken = result.getObject("owner_token", UUID::class.java),
                    ownerLeaseExpiresAt = result.getTimestamp("owner_lease_expires_at")?.toInstant(),
                    responseStatus = result.getObject("response_status")?.let { (it as Number).toInt() },
                    responseBody = result.getBytes("response_body"),
                    responseContentType = result.getString("response_content_type"),
                    responseHeaders = result.getString("response_headers"),
                    abandonedUntil = result.getTimestamp("abandoned_until")?.toInstant(),
                    databaseNow = result.getTimestamp("database_now").toInstant(),
                    leaseExpired = result.getBoolean("lease_expired"),
                    retentionExpired = result.getBoolean("retention_expired"),
                )
            }
        }

    /**
     * activeWaiterCount은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param _connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param keyHash 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param generation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun activeWaiterCount(_connection: Connection, scope: DemoCallerScope, keyHash: String, generation: Long): Int =
        JobRequestWaiterTable
            .select(JobRequestWaiterTable.waiterToken.count())
            .where {
                (JobRequestWaiterTable.tenantId eq scope.tenantId) and
                    (JobRequestWaiterTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                    (JobRequestWaiterTable.keyHash eq keyHash) and
                    (JobRequestWaiterTable.generation eq generation) and
                    (JobRequestWaiterTable.expiresAt greater CurrentTimestamp)
            }
            .single()[JobRequestWaiterTable.waiterToken.count()]
            .toInt()

    /**
     * deleteExpiredWaiters은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param _connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param keyHash 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param generation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun deleteExpiredWaiters(_connection: Connection, scope: DemoCallerScope, keyHash: String, generation: Long) {
        JobRequestWaiterTable.deleteWhere {
            (JobRequestWaiterTable.tenantId eq scope.tenantId) and
                (JobRequestWaiterTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                (JobRequestWaiterTable.keyHash eq keyHash) and
                (JobRequestWaiterTable.generation eq generation) and
                (JobRequestWaiterTable.expiresAt lessEq CurrentTimestamp)
        }
    }

/**
 * ownership은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun RequestRow.ownership(): JobSubmissionOwnership =
        JobSubmissionOwnership(
            scope = scope,
            keyHash = keyHash,
            generation = generation,
            jobId = jobId,
            ownerToken = requireNotNull(ownerToken),
            leaseExpiresAt = requireNotNull(ownerLeaseExpiresAt),
        )

/**
 * snapshot은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun RequestRow.snapshot(connection: Connection): ReplayableJobSubmission {
        if (responseStatus != null && responseBody != null && responseContentType != null && enqueueSequence != null) {
            return ReplayableJobSubmission(
                jobId = jobId,
                enqueueSequence = enqueueSequence,
                responseStatus = responseStatus,
                responseBody = responseBody.copyOf(),
                responseContentType = responseContentType,
                responseHeaders = decodeHeaders(responseHeaders),
            )
        }
        if (state != RequestState.TERMINAL) {
            throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
        }
        return materializeLegacySnapshot(connection, this)
    }

    /**
     * materializeLegacySnapshot은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param request 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun materializeLegacySnapshot(connection: Connection, request: RequestRow): ReplayableJobSubmission {
        val stored = jobRepository.loadInCurrentTransaction(request.jobId)
            ?: throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
        if (
            stored.tenantId != request.scope.tenantId ||
            stored.submitterHash != normalizedSubmitterHash(request.scope.submitterHash)
        ) {
            throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
        }
        val snapshot =
            JobSnapshot(
                jobId = stored.jobId,
                jobType = stored.jobType,
                state = stored.state,
                progress = stored.progress,
                checkpoint = stored.completedChunk.takeIf { it > 0 },
                queue = null,
                version = stored.version,
                updatedAt = stored.updatedAt,
            )
        val body = jsonMapper.writeValueAsString(snapshot).toByteArray(UTF_8)
        if (body.size > policy.maxReplayBytes) {
            throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
        }
        val updated =
            connection.prepareStatement(
                """
                UPDATE job_requests
                SET response_status = 202, response_body = ?, response_content_type = 'application/json',
                    response_headers = CAST('{}' AS JSONB), terminal_at = COALESCE(terminal_at, CURRENT_TIMESTAMP),
                    retained_until = COALESCE(retained_until, created_at + INTERVAL '1 hour'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ?
                  AND state = 'TERMINAL' AND generation = ? AND response_status IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, body)
                statement.setString(2, request.scope.tenantId)
                statement.setString(3, normalizedSubmitterHash(request.scope.submitterHash))
                statement.setString(4, request.keyHash)
                statement.setLong(5, request.generation)
                statement.executeUpdate()
            }
        if (updated == 0) {
            val reloaded = loadRequest(connection, request.scope, request.keyHash, forUpdate = true)
            if (reloaded == null || reloaded.responseStatus == null || reloaded.responseBody == null) {
                throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
            }
            return reloaded.snapshot(connection)
        }
        return ReplayableJobSubmission(
            jobId = stored.jobId,
            enqueueSequence = stored.enqueueSequence,
            responseStatus = 202,
            responseBody = body,
            responseContentType = "application/json",
            responseHeaders = emptyMap(),
        )
    }

/**
 * withTransaction은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    override fun <T> withTransaction(block: (Connection) -> T): T =
        inTransaction(connectionTimeout = policy.connectionAcquireTimeout, block = block)

/**
 * inTransaction은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private inline fun <T> inTransaction(
        statementTimeout: Duration = policy.statementTimeout,
        connectionTimeout: Duration? = null,
        deadlineNanos: Long? = null,
        afterCommit: (Connection, T) -> T = { _, result -> result },
        crossinline block: (Connection) -> T,
    ): T {
        /**
         * `acquisitionTimeout`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val acquisitionTimeout =
            if (deadlineNanos == null || connectionTimeout == null) {
                connectionTimeout
            } else {
                /**
                 * `remaining`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0L) throw TransactionDeadlineExceeded()
                minOf(connectionTimeout, remaining.nanos())
            }
        /**
         * `connection`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val connection =
            if (acquisitionTimeout == null) {
                dataSource.connection
            } else {
                boundedConnectionAcquirer.acquire(acquisitionTimeout) ?:
                    if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                        throw TransactionDeadlineExceeded()
                    } else {
                        throw ConnectionAcquireDeadlineExceeded()
                    }
            }
        return connection.use {
            try {
                /**
                 * `effectiveStatementTimeout`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
                 *
                 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
                 */
                val effectiveStatementTimeout =
                    if (deadlineNanos == null) {
                        statementTimeout
                    } else {
                        /**
                         * `remaining`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                         *
                         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                         */
                        val remaining = deadlineNanos - System.nanoTime()
                        if (remaining <= 0L) throw TransactionDeadlineExceeded()
                        minOf(statementTimeout, remaining.nanos())
                    }
                if (effectiveStatementTimeout.toMillis() <= 0L) throw TransactionDeadlineExceeded()
                setNetworkTimeout(connection, effectiveStatementTimeout)
                applyStatementTimeout(connection, effectiveStatementTimeout)
                if (deadlineNanos != null) {
                    /**
                     * `remainingAfterSetup`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                     *
                     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                     */
                    val remainingAfterSetup = deadlineNanos - System.nanoTime()
                    if (remainingAfterSetup <= 0L || remainingAfterSetup.nanos().toMillis() <= 0L) {
                        throw TransactionDeadlineExceeded()
                    }
                    setNetworkTimeout(connection, remainingAfterSetup.nanos())
                }
                /**
                 * `result`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val result = if (supportsExposed(connection)) {
                    jdbc.transaction(connection) { block(connection) }
                } else {
                    legacyTransaction(connection, block)
                }
                if (deadlineNanos != null) {
                    /**
                     * `remaining`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                     *
                     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                     */
                    val remaining = deadlineNanos - System.nanoTime()
                    if (remaining <= 0L) throw TransactionDeadlineExceeded()
                }
                afterCommit(connection, result)
            } catch (failure: SQLException) {
                /**
                 * `hardExpired`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val hardExpired = deadlineNanos != null && System.nanoTime() >= deadlineNanos
                /**
                 * `deadlineFailure`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
                 *
                 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
                 */
                val deadlineFailure =
                    deadlineNanos != null &&
                        (hardExpired || failure.sqlState == QUERY_CANCELED_SQL_STATE)
                if (deadlineFailure) {
                    runCatching { connection.abort(executor) }
                    throw TransactionDeadlineExceeded()
                }
                throw failure
            } catch (failure: Throwable) {
                if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                    runCatching { connection.abort(executor) }
                }
                throw failure
            }
        }
    }

    /**
     * timeout contract 테스트의 test double은 JDBC metadata를 노출하지 않습니다.
     * production connection은 항상 Exposed를 사용하며, 이 fallback은 의도적으로
     * 단순한 proxy가 timeout/error 의미에 집중하도록 남겨 둡니다.
     */
    private fun supportsExposed(connection: Connection): Boolean =
        runCatching { connection.metaData != null }.getOrDefault(false)

/**
 * legacyTransaction은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private inline fun <T> legacyTransaction(connection: Connection, block: (Connection) -> T): T {
        connection.autoCommit = false
        return try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        }
    }

    /** 만료 waiter 삭제와 admission count를 동일한 Exposed transaction으로 묶습니다. */
    /** 만료 waiter 삭제와 admission count를 동일한 Exposed transaction으로 묶습니다. */
    private fun cleanupAndCountActiveWaiters(
        _connection: Connection,
        scope: DemoCallerScope,
        keyHash: String,
        generation: Long,
    ): Int {
        JobRequestWaiterTable.deleteWhere {
            (JobRequestWaiterTable.tenantId eq scope.tenantId) and
                (JobRequestWaiterTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                (JobRequestWaiterTable.keyHash eq keyHash) and
                (JobRequestWaiterTable.generation eq generation) and
                (JobRequestWaiterTable.expiresAt lessEq CurrentTimestamp)
        }
        return JobRequestWaiterTable
            .select(JobRequestWaiterTable.waiterToken.count())
            .where {
                (JobRequestWaiterTable.tenantId eq scope.tenantId) and
                    (JobRequestWaiterTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                    (JobRequestWaiterTable.keyHash eq keyHash) and
                    (JobRequestWaiterTable.generation eq generation) and
                    (JobRequestWaiterTable.expiresAt greater CurrentTimestamp)
            }
            .single()[JobRequestWaiterTable.waiterToken.count()]
            .toInt()
    }

/**
 * inBoundedTransaction은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun <T> inBoundedTransaction(
        deadlineNanos: Long,
        block: (Connection, Long) -> T,
    ): T? {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) return null
        val connection = boundedConnectionAcquirer.acquire(remaining.nanos()) ?: return null
        return connection.use {
            try {
                val statementBudgetNanos = configureRegistrationBudget(it, deadlineNanos)
                if (supportsExposed(it)) {
                    jdbc.transaction(it) {
                        val result = block(it, statementBudgetNanos)
                        checkRegistrationDeadline(deadlineNanos)
                        refreshNetworkTimeout(it, deadlineNanos)
                        result
                    }
                } else {
                    val acquired = it
                    acquired.autoCommit = false
                    try {
                        val result = block(acquired, statementBudgetNanos)
                        checkRegistrationDeadline(deadlineNanos)
                        refreshNetworkTimeout(acquired, deadlineNanos)
                        result.also { acquired.commit() }
                    } catch (failure: Throwable) {
                        runCatching { acquired.rollback() }
                        throw failure
                    }
                }
            } catch (_: RegistrationDeadlineExceeded) {
                runCatching { it.abort(executor) }
                null
            } catch (failure: SQLException) {
                runCatching { it.abort(executor) }
                if (failure.sqlState == QUERY_CANCELED_SQL_STATE || System.nanoTime() >= deadlineNanos) {
                    null
                } else {
                    throw failure
                }
            } catch (failure: Throwable) {
                runCatching { it.abort(executor) }
                throw failure
            }
        }
    }

    /**
     * configureRegistrationBudget은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param deadlineNanos 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun configureRegistrationBudget(connection: Connection, deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining < MIN_REGISTRATION_STATEMENT_BUDGET_NANOS) throw RegistrationDeadlineExceeded()
        val statementBudgetNanos = minOf(
                remaining / REGISTRATION_STATEMENT_COUNT,
                policy.statementTimeout.toNanos(),
            )
        if (statementBudgetNanos <= 0L) throw RegistrationDeadlineExceeded()
        refreshNetworkTimeout(connection, deadlineNanos, statementBudgetNanos)
        val perStatementTimeout = statementBudgetNanos.nanos()
        applyStatementTimeout(connection, perStatementTimeout)
        return statementBudgetNanos
    }

    /**
     * refreshNetworkTimeout은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun refreshNetworkTimeout(
        connection: Connection,
        deadlineNanos: Long,
        maximumTimeoutNanos: Long = Long.MAX_VALUE,
    ) {
        /**
         * `remaining`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) throw RegistrationDeadlineExceeded()
        /**
         * `timeoutNanos`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val timeoutNanos = minOf(remaining, maximumTimeoutNanos)
        /**
         * `timeoutMillis`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val timeoutMillis = timeoutNanos.nanos().toMillis().coerceAtMost(Int.MAX_VALUE.toLong())
        if (timeoutMillis <= 0L) throw RegistrationDeadlineExceeded()
        setNetworkTimeout(connection, timeoutMillis.millis())
    }

    /**
     * setNetworkTimeout은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param timeout 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun setNetworkTimeout(connection: Connection, timeout: Duration) {
        val timeoutMillis = timeout.toMillis().coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong())
        connection.setNetworkTimeout(executor, timeoutMillis.toInt())
    }

    /**
     * checkRegistrationStatementBudget은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을
     * 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param deadlineNanos 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param statementBudgetNanos 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun checkRegistrationStatementBudget(deadlineNanos: Long, statementBudgetNanos: Long) {
        if (deadlineNanos - System.nanoTime() <= 0L || statementBudgetNanos <= 0L) throw RegistrationDeadlineExceeded()
    }

    /**
     * checkRegistrationDeadline은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param deadlineNanos 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun checkRegistrationDeadline(deadlineNanos: Long) {
        if (System.nanoTime() >= deadlineNanos) throw RegistrationDeadlineExceeded()
    }

    /** PostgreSQL transaction-local timeout 설정은 Exposed DSL로 대체할 수 없는 제어 SQL입니다. */
    /** PostgreSQL transaction-local timeout 설정은 Exposed DSL로 대체할 수 없는 제어 SQL입니다. */
    private fun applyStatementTimeout(connection: Connection, statementTimeout: Duration = policy.statementTimeout) {
        connection.createStatement().use { statement ->
            val timeoutMillis = statementTimeout.toMillis().coerceAtLeast(1L)
            statement.execute("SET LOCAL statement_timeout = '${timeoutMillis}ms'")
        }
    }

    /**
     * boundedRegistrationTtl은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param waiterTtl 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun boundedRegistrationTtl(waiterTtl: Duration): Duration {
        val boundedTtl = waiterTtl.coerceAtMost(policy.waiterTimeout)
        require(boundedTtl >= MIN_REGISTRATION_TTL) {
            "waiter registration deadline is too close to execute"
        }
        require(boundedTtl.toMillis() > 0L) {
            "waiter registration deadline must have millisecond precision"
        }
        return boundedTtl
    }

    /**
     * encodeHeaders은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param headers 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun encodeHeaders(headers: Map<String, List<String>>): String =
        if (headers.isEmpty()) "{}" else jsonMapper.writeValueAsString(headers.toSortedMap())

    /**
     * decodeHeaders은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param headers 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun decodeHeaders(headers: String?): Map<String, List<String>> =
        if (headers.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching {
                /**
                 * `node`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val node = jsonMapper.readTree(headers)
                if (!node.isObject) throw IllegalArgumentException("response headers must be an object")
                buildMap<String, List<String>> {
                    node.properties().forEach { (name, values) ->
                        if (!values.isArray || values.any { !it.isString }) {
                            throw IllegalArgumentException("response header values must be string arrays")
                        }
                        /**
                         * `strings`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                         *
                         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                         */
                        val strings = buildList<String> {
                            for (value in values) {
                                add(value.stringValue())
                            }
                        }
                        put(name, strings)
                    }
                }
            }.getOrElse { throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED) }
        }

    /**
     * RequestRow은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class RequestRow(
        /**
         * `scope`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val scope: DemoCallerScope,
        /**
         * `keyHash`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val keyHash: String,
        /**
         * `requestFingerprint`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val requestFingerprint: String,
        /**
         * `jobId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val jobId: UUID,
        /**
         * `enqueueSequence`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val enqueueSequence: Long?,
        /**
         * `state`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val state: RequestState,
        /**
         * `generation`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val generation: Long,
        /**
         * `ownerToken`은 작업 운영 콘솔에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val ownerToken: UUID?,
        /**
         * `ownerLeaseExpiresAt`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val ownerLeaseExpiresAt: Instant?,
        /**
         * `responseStatus`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val responseStatus: Int?,
        /**
         * `responseBody`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val responseBody: ByteArray?,
        /**
         * `responseContentType`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val responseContentType: String?,
        /**
         * `responseHeaders`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val responseHeaders: String?,
        /**
         * `abandonedUntil`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val abandonedUntil: Instant?,
        /**
         * `databaseNow`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val databaseNow: Instant,
        /**
         * `leaseExpired`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val leaseExpired: Boolean,
        /**
         * `retentionExpired`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val retentionExpired: Boolean,
    )

    /**
     * RequestState은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private enum class RequestState {
        /**
         * `RequestState.IN_FLIGHT`은 RequestState 업무 상태의 `IN_FLIGHT` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
         * 분기를 선택하는 기준으로
         * 사용합니다.
         */
        IN_FLIGHT,
        /**
         * `RequestState.TERMINAL`은 RequestState 업무 상태의 `TERMINAL` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를
         * 선택하는 기준으로
         * 사용합니다.
         */
        TERMINAL,
        /**
         * `RequestState.ABANDONED`은 RequestState 업무 상태의 `ABANDONED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
         * 분기를 선택하는 기준으로
         * 사용합니다.
         */
        ABANDONED,
    }

    /**
     * ConnectionAcquireDeadlineExceeded은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
     * 계약을 이 타입의
     * 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private class ConnectionAcquireDeadlineExceeded : RuntimeException()
    /**
     * TransactionDeadlineExceeded은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
     * 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private class TransactionDeadlineExceeded : RuntimeException()
    /**
     * RegistrationDeadlineExceeded은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
     * 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private class RegistrationDeadlineExceeded : RuntimeException()

    private companion object: KLogging() {
        /**
         * `UNIQUE_VIOLATION_SQL_STATE`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        /**
         * `QUERY_CANCELED_SQL_STATE`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        const val QUERY_CANCELED_SQL_STATE = "57014"
        /**
         * `MAX_RESERVE_RETRIES`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        const val MAX_RESERVE_RETRIES = 2
        /**
         * `REGISTRATION_STATEMENT_COUNT`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        const val REGISTRATION_STATEMENT_COUNT = 5
        /**
         * `MIN_REGISTRATION_STATEMENT_BUDGET_NANOS`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        const val MIN_REGISTRATION_STATEMENT_BUDGET_NANOS = REGISTRATION_STATEMENT_COUNT * 1_000_000L
        /**
         * `MIN_REGISTRATION_TTL`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val MIN_REGISTRATION_TTL: Duration = 25.millis()
        /**
         * `jsonMapper`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val jsonMapper = Jackson.defaultJsonMapper

        /**
         * normalizedSubmitterHash은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun normalizedSubmitterHash(value: String): String =
            if (value.matches(Regex("[0-9a-f]{64}"))) {
                value
            } else {
                MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).toHexString()
            }
    }
}
