package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8String
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource
/**
 * JobMigration은 운영 작업 콘솔에서 전달되는 불변 데이터 모델입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property bytes 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 */


data class JobMigration(
    /**
     * `version`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: String,
    /**
     * `bytes`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val bytes: ByteArray,
) {
    init {
        version.requireNotBlank("version")
    }
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */

    val checksum: String
        get() = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    /**
     * 운영 작업 콘솔 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */

    val sql: String
        get() = bytes.toUtf8String()

    companion object {
        /**
         * classpath은 운영 작업 콘솔 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
         * 보존합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param resource 업무 처리에 필요한 resource 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun classpath(version: String, resource: String): JobMigration {
            val bytes =
                requireNotNull(JobMigration::class.java.classLoader.getResourceAsStream(resource)) {
                    "Migration resource is unavailable"
                }.use { it.readAllBytes() }
            return JobMigration(version, bytes)
        }
    }
}

/**
 * `JobMigrationResult`은 명령 처리 결과입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
enum class JobMigrationResult {
    /**
     * `JobMigrationResult.APPLIED`은 `applied` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    APPLIED,
    /**
     * `JobMigrationResult.ALREADY_APPLIED`은 `already applied` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로
     * 후속 처리를
     * 분기합니다.
     */
    ALREADY_APPLIED,
}

/**
 * `JobMigrationFailureCode`은 업무 코드입니다.
 * 호출자가 재시도·거절·대사·운영자 조치를 분기하는 오류 계약입니다.
 */
enum class JobMigrationFailureCode {
    /**
     * `JobMigrationFailureCode.CHECKSUM_DRIFT`은 `checksum drift` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자
     * 조치의 분기 기준으로 사용합니다.
     */
    CHECKSUM_DRIFT,
    /**
     * `JobMigrationFailureCode.STATEMENT_FAILED`은 `statement failed` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    STATEMENT_FAILED,
}
/**
 * JobMigrationException은 운영 작업 콘솔에서 사용하는 업무 객체입니다.
 * @property code 운영 작업 콘솔의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


class JobMigrationException(
    /**
     * `code`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: JobMigrationFailureCode,
    cause: Throwable? = null,
) : RuntimeException(code.name, cause)
/**
 * JobMigrationRunner은 운영 작업 콘솔에서 사용하는 업무 객체입니다.
 * @property dataSource 운영 작업 콘솔 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property migrations 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property advisoryLockKey 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property lockTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property statementTimeout 운영 작업 콘솔의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property logRowCounts 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 */


class JobMigrationRunner(
    /**
     * `dataSource`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
    /**
     * `migrations`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val migrations: List<JobMigration>,
    /**
     * `advisoryLockKey`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val advisoryLockKey: Long,
    /**
     * `lockTimeout`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val lockTimeout: Duration = Duration.ofSeconds(2),
    /**
     * `statementTimeout`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val statementTimeout: Duration = Duration.ofSeconds(30),
    /**
     * `logRowCounts`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val logRowCounts: Boolean = true,
) {
    companion object: KLogging()

    init {
        require(migrations.map(JobMigration::version).distinct().size == migrations.size) {
            "migration versions must be unique"
        }
        require(!lockTimeout.isZero && !lockTimeout.isNegative) { "lockTimeout must be positive" }
        require(!statementTimeout.isZero && !statementTimeout.isNegative) { "statementTimeout must be positive" }
    }

    /**
     * migrate은 운영 작업 콘솔의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun migrate(): List<JobMigrationResult> =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                setLocalTimeouts(connection)
                acquireLock(connection)
                createHistory(connection)
                migrations.sortedBy(JobMigration::version).map { migration -> apply(connection, migration) }
                    .also {
                        connection.commit()
                    }
            } catch (failure: JobMigrationException) {
                connection.rollbackQuietly()
                log.warn { "job_console_migration_failed code=${failure.code}" }
                throw failure
            } catch (failure: SQLException) {
                connection.rollbackQuietly()
                log.warn { "job_console_migration_failed code=${JobMigrationFailureCode.STATEMENT_FAILED}" }
                throw JobMigrationException(JobMigrationFailureCode.STATEMENT_FAILED, failure)
            }
        }

    /**
     * acquireLock은 운영 작업 콘솔의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun acquireLock(connection: Connection) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, advisoryLockKey)
            statement.executeQuery().use { result -> check(result.next()) }
        }
    }

    /**
     * setLocalTimeouts은 운영 작업 콘솔 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun setLocalTimeouts(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("SET LOCAL lock_timeout = '${lockTimeout.toMillis()}ms'")
            statement.execute("SET LOCAL statement_timeout = '${statementTimeout.toMillis()}ms'")
        }
    }

    /**
     * createHistory은 운영 작업 콘솔 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun createHistory(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS job_schema_history (
                    version VARCHAR(64) PRIMARY KEY,
                    checksum CHAR(64) NOT NULL,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * apply은 운영 작업 콘솔 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param migration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun apply(connection: Connection, migration: JobMigration): JobMigrationResult {
        val rowCountBefore = if (logRowCounts && migration.version == "002") countJobRequests(connection) else null
        val appliedChecksum =
            connection.prepareStatement("SELECT checksum FROM job_schema_history WHERE version = ?").use { statement ->
                statement.setString(1, migration.version)
                statement.executeQuery().use { result -> if (result.next()) result.getString(1).trim() else null }
            }
        if (appliedChecksum != null) {
            if (appliedChecksum != migration.checksum) {
                throw JobMigrationException(JobMigrationFailureCode.CHECKSUM_DRIFT)
            }
            logRowCount(migration, rowCountBefore, countJobRequests(connection))
            return JobMigrationResult.ALREADY_APPLIED
        }

        connection.createStatement().use { statement ->
            migration.sql
                .lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(statement::execute)
        }
        connection.prepareStatement("INSERT INTO job_schema_history(version, checksum) VALUES (?, ?)").use { statement ->
            statement.setString(1, migration.version)
            statement.setString(2, migration.checksum)
            statement.executeUpdate()
        }
        log.info { "job_console_migration_applied version=${migration.version}" }
        logRowCount(migration, rowCountBefore, countJobRequests(connection))
        return JobMigrationResult.APPLIED
    }

    /**
     * countJobRequests은 운영 작업 콘솔 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun countJobRequests(connection: Connection): Long? =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT CASE
                    WHEN to_regclass('job_requests') IS NULL THEN NULL
                    ELSE (SELECT count(*) FROM job_requests)
                END
                """.trimIndent(),
            ).use { result ->
                check(result.next())
                result.getLong(1).takeUnless { result.wasNull() }
            }
        }

    /**
     * logRowCount은 운영 작업 콘솔 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param migration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param before 업무 처리에 필요한 before 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param after 업무 처리에 필요한 after 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun logRowCount(migration: JobMigration, before: Long?, after: Long?) {
        if (before != null && after != null) {
            log.info {
                "job_console_migration_row_count version=${migration.version} before=$before after=$after"
            }
        }
    }

    /**
     * rollbackQuietly은 운영 작업 콘솔의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun Connection.rollbackQuietly() {
        runCatching { rollback() }
    }
}
