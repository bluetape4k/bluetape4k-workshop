@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.jdbc.sql.getBooleanOrNull
import io.bluetape4k.jdbc.sql.getStringOrNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8String
import org.springframework.core.io.Resource
import java.io.Serial
import java.io.Serializable
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

/**
 * version과 checksum이 있는 단일 schema resource입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property resource 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class TicketMigration(
    /**
     * `version`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: String,
    /**
     * `resource`은 콘서트 티켓 플래시 세일에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val resource: Resource,
): Serializable {
    init {
        version.requireNotBlank("version")
    }

    /**
     * read은 콘서트 티켓 판매 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun read(): TicketMigrationScript {
        val bytes = try {
            resource.inputStream.use { it.readAllBytes() }
        } catch (failure: Exception) {
            throw TicketMigrationException(TicketMigrationFailure.RESOURCE_UNAVAILABLE, failure)
        }

        return TicketMigrationScript(
            version = version,
            checksum = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
            sql = bytes.toUtf8String(),
        )
    }

    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 단일 database transaction 안에서 사용하는 materialized migration input입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property checksum 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property sql 콘서트 티켓 판매 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 */
data class TicketMigrationScript(
    /**
     * `version`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: String,
    /**
     * `checksum`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val checksum: String,
    /**
     * `sql`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sql: String,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** migration 하나를 적용하거나 replay한 결과입니다. */
/** migration 하나를 적용하거나 replay한 결과입니다. */
enum class TicketMigrationResult {
    /**
     * `TicketMigrationResult.APPLIED`은 마이그레이션·명령·outbox 반영이 이번 호출에서 실제로 완료된 값입니다.
     */
    APPLIED,
    /**
     * `TicketMigrationResult.ALREADY_APPLIED`은 동일한 변경이 이미 반영되어 추가 side effect 없이 멱등 완료한 값입니다.
     */
    ALREADY_APPLIED,
}

/** sanitize된 migration failure category입니다. */
/** sanitize된 migration failure category입니다. */
enum class TicketMigrationFailure {
    /**
     * `TicketMigrationFailure.LOCK_TIMEOUT`은 필요한 DB/Redis lock을 제한 시간 안에 얻지 못한 값입니다.
     */
    LOCK_TIMEOUT,
    /**
     * `TicketMigrationFailure.CHECKSUM_MISMATCH`은 기대 checksum과 실제 schema·artifact가 달라 안전하게 중단한
     * 값입니다.
     */
    CHECKSUM_MISMATCH,
    /**
     * `TicketMigrationFailure.STATEMENT_FAILED`은 SQL 또는 외부 명령 실행이 실패해 transaction 결과를 확정할 수 없는
     * 값입니다.
     */
    STATEMENT_FAILED,
    /**
     * `TicketMigrationFailure.RESOURCE_UNAVAILABLE`은 필수 DB·Redis·worker 자원이 준비되지 않은 값입니다.
     */
    RESOURCE_UNAVAILABLE,
}

/**
 * startup diagnostics에 노출해도 안전한 migration failure입니다.
 * @property code 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
class TicketMigrationException(
    /**
     * `code`은 콘서트 티켓 플래시 세일에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: TicketMigrationFailure,
    cause: Throwable? = null,
): IllegalStateException(code.name, cause) {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** migration checksum verification이 끝난 뒤에만 열리는 readiness gate입니다. */
/** migration checksum verification이 끝난 뒤에만 열리는 readiness gate입니다. */
class TicketMigrationReadiness {
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val ready = AtomicBoolean(false)
    /**
     * 콘서트 티켓 판매 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */

    val isReady: Boolean get() = ready.get()

    /**
     * markReady은 콘서트 티켓 판매의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    internal fun markReady() {
        ready.set(true)
    }
}

/**
 * application-owned migration을 PostgreSQL transaction advisory lock 아래에서 적용합니다.
 * @property dataSource 콘서트 티켓 판매 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property migration 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property advisoryLockKey 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property lockTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property readiness 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class TicketMigrationRunner(
    /**
     * `dataSource`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
    /**
     * `migration`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val migration: TicketMigration,
    /**
     * `advisoryLockKey`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val advisoryLockKey: Long,
    /**
     * `lockTimeout`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val lockTimeout: Duration = 30.seconds(),
    /**
     * `readiness`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val readiness: TicketMigrationReadiness = TicketMigrationReadiness(),
) {
    init {
        lockTimeout.requirePositiveDuration("lockTimeout")
    }

    /**
     * migrate은 콘서트 티켓 판매의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun migrate(): TicketMigrationResult {
        val script = migration.read()

        // TODO: Exposed 로 전환하자 
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireMigrationLock(connection)
                createHistoryTable(connection)
                val result = when (val checksum = appliedChecksum(connection, script.version)) {
                    null -> applyMigration(connection, script)
                    script.checksum -> {
                        connection.commit()
                        log.info { "ticket_migration_already_applied version=${script.version}" }
                        TicketMigrationResult.ALREADY_APPLIED
                    }

                    else ->
                        throw TicketMigrationException(TicketMigrationFailure.CHECKSUM_MISMATCH)
                }
                readiness.markReady()
                result
            } catch (failure: TicketMigrationException) {
                connection.rollbackQuietly()
                log.warn { "ticket_migration_failed version=${script.version} code=${failure.code}" }
                throw failure
            } catch (failure: SQLException) {
                log.info { "rollback quietly ..." }
                connection.rollbackQuietly()
                log.warn(failure) {
                    "ticket_migration_failed version=${script.version} code=${TicketMigrationFailure.STATEMENT_FAILED}"
                }
                throw TicketMigrationException(TicketMigrationFailure.STATEMENT_FAILED, failure)
            }
        }
    }

    /**
     * acquireMigrationLock은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun acquireMigrationLock(connection: Connection) {
        val deadline = System.nanoTime() + lockTimeout.toNanos()
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, advisoryLockKey)
            while (true) {
                statement.executeQuery().use { result ->
                    check(result.next()) { "advisory lock query returned no row" }
                    if (result.getBooleanOrNull(1) == true) return
                }
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
                    throw TicketMigrationException(TicketMigrationFailure.LOCK_TIMEOUT)
                }
                LockSupport.parkNanos(LOCK_RETRY_DELAY.toNanos())
            }
        }
    }

    /**
     * createHistoryTable은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun createHistoryTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS ticket_schema_history (
                    version VARCHAR(64) PRIMARY KEY,
                    checksum VARCHAR(64) NOT NULL,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * appliedChecksum은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun appliedChecksum(connection: Connection, version: String): String? {
        val sql = "SELECT checksum FROM ticket_schema_history WHERE version = ?"
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, version)
            statement.executeQuery().use { result ->
                if (result.next()) result.getStringOrNull("checksum") else null
            }
        }
    }

    /**
     * applyMigration은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param script 업무 처리에 필요한 script 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun applyMigration(
        connection: Connection,
        script: TicketMigrationScript,
    ): TicketMigrationResult {
        connection.createStatement().use { statement ->
            script.sql
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .onEach { log.debug { "Execute statement. sql=$it" } }
                .forEach(statement::execute)
        }

        val sql = "INSERT INTO ticket_schema_history(version, checksum) VALUES (?, ?)"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, script.version)
            statement.setString(2, script.checksum)
            statement.executeUpdate()
        }
        connection.commit()
        log.info { "ticket_migration_applied version=${script.version}" }
        return TicketMigrationResult.APPLIED
    }

    /**
     * rollbackQuietly은 콘서트 티켓 판매의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게
     * 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun Connection.rollbackQuietly() {
        runCatching { rollback() }
    }

    companion object: KLogging() {
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        private val LOCK_RETRY_DELAY: Duration = 10.millis()
    }
}
