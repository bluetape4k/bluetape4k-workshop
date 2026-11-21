@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8String
import org.springframework.core.io.Resource
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource
/**
 * VoucherMigration은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property resource 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class VoucherMigration(
    /**
     * `version`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: String,
    /**
     * `resource`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val resource: Resource,
) {
    init {
        version.requireNotBlank("version")
    }

    /**
     * read은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun read(): MigrationScript {
        val bytes = try {
            resource.inputStream.use { it.readAllBytes() }
        } catch (failure: Exception) {
            throw VoucherMigrationException(VoucherMigrationFailureCode.RESOURCE_UNAVAILABLE, failure)
        }

        return MigrationScript(
            version = version,
            checksum = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
            sql = bytes.toUtf8String(),
        )
    }
}
/**
 * MigrationScript은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property checksum 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property sql 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 */


internal data class MigrationScript(
    /**
     * `version`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: String,
    /**
     * `checksum`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val checksum: String,
    /**
     * `sql`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sql: String,
)

/**
 * `VoucherMigrationResult`은 명령 처리 결과입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
internal enum class VoucherMigrationResult {
    /**
     * `VoucherMigrationResult.APPLIED`은 마이그레이션·명령·outbox 반영이 이번 호출에서 실제로 완료된 값입니다.
     */
    APPLIED,
    /**
     * `VoucherMigrationResult.ALREADY_APPLIED`은 동일한 변경이 이미 반영되어 추가 side effect 없이 멱등 완료한 값입니다.
     */
    ALREADY_APPLIED,
}

/**
 * `VoucherMigrationFailureCode`은 업무 코드입니다.
 * 호출자가 재시도·거절·대사·운영자 조치를 분기하는 오류 계약입니다.
 */
internal enum class VoucherMigrationFailureCode {
    /**
     * `VoucherMigrationFailureCode.LOCK_TIMEOUT`은 필요한 DB/Redis lock을 제한 시간 안에 얻지 못한 값입니다.
     */
    LOCK_TIMEOUT,
    /**
     * `VoucherMigrationFailureCode.CHECKSUM_DRIFT`은 `checksum drift` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    CHECKSUM_DRIFT,
    /**
     * `VoucherMigrationFailureCode.STATEMENT_FAILED`은 SQL 또는 외부 명령 실행이 실패해 transaction 결과를 확정할 수
     * 없는 값입니다.
     */
    STATEMENT_FAILED,
    /**
     * `VoucherMigrationFailureCode.RESOURCE_UNAVAILABLE`은 필수 DB·Redis·worker 자원이 준비되지 않은 값입니다.
     */
    RESOURCE_UNAVAILABLE,
}
/**
 * VoucherMigrationException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property code 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


internal class VoucherMigrationException(
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: VoucherMigrationFailureCode,
    cause: Throwable? = null,
): RuntimeException(code.name, cause)

/**
 * application-owned schema를 PostgreSQL transaction advisory lock 아래에서 정확히 한 번 적용합니다.
 * @property dataSource 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property migration 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property advisoryLockKey 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property lockTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
internal class VoucherMigrationRunner(
    /**
     * `dataSource`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
    /**
     * `migration`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val migration: VoucherMigration,
    /**
     * `advisoryLockKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val advisoryLockKey: Long,
    /**
     * `lockTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val lockTimeout: Duration = 30.seconds(),
) {
    companion object: KLogging() {
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        private val LOCK_RETRY_DELAY: Duration = 10.millis()
    }

    init {
        lockTimeout.requirePositiveDuration("lockTimeout")
    }

    /**
     * migrate은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun migrate(): VoucherMigrationResult {
        val script = migration.read()

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireMigrationLock(connection)
                createHistoryTable(connection)
                when (val appliedChecksum = appliedChecksum(connection, script.version)) {
                    null -> applyMigration(connection, script)
                    script.checksum -> {
                        connection.commit()
                        log.info { "voucher_migration_already_applied version=${script.version}" }
                        VoucherMigrationResult.ALREADY_APPLIED
                    }
                    else -> throw VoucherMigrationException(VoucherMigrationFailureCode.CHECKSUM_DRIFT)
                }
            } catch (failure: VoucherMigrationException) {
                connection.rollbackQuietly()
                log.warn { "voucher_migration_failed version=${script.version} code=${failure.code}" }
                throw failure
            } catch (failure: SQLException) {
                connection.rollbackQuietly()
                log.warn {
                    "voucher_migration_failed version=${script.version} " +
                            "code=${VoucherMigrationFailureCode.STATEMENT_FAILED}"
                }
                throw VoucherMigrationException(VoucherMigrationFailureCode.STATEMENT_FAILED, failure)
            }
        }
    }

    /**
     * acquireMigrationLock은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param conn 업무 처리에 필요한 conn 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun acquireMigrationLock(conn: Connection) {
        val deadline = System.nanoTime() + lockTimeout.toNanos()
        val sql = "SELECT pg_try_advisory_xact_lock(?)"

        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, advisoryLockKey)

            while (true) {
                stmt.executeQuery().use { result ->
                    check(result.next()) { "advisory lock query returned no row" }
                    if (result.getBoolean(1)) return
                }
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
                    throw VoucherMigrationException(VoucherMigrationFailureCode.LOCK_TIMEOUT)
                }
                LockSupport.parkNanos(LOCK_RETRY_DELAY.toNanos())
            }
        }
    }

    /**
     * createHistoryTable은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param conn 업무 처리에 필요한 conn 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun createHistoryTable(conn: Connection) {
        val sql = """
            CREATE TABLE IF NOT EXISTS voucher_schema_history (
                version VARCHAR(64) PRIMARY KEY,
                checksum VARCHAR(64) NOT NULL,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()

        conn.createStatement().use { stmt ->
            stmt.execute(sql)
        }
    }

    /**
     * appliedChecksum은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param conn 업무 처리에 필요한 conn 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun appliedChecksum(
        conn: Connection,
        version: String,
    ): String? {
        val sql = "SELECT checksum FROM voucher_schema_history WHERE version = ?"

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, version)
            stmt.executeQuery().use { result ->
                if (result.next()) result.getString("checksum") else null
            }
        }
    }

    /**
     * applyMigration은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param conn 업무 처리에 필요한 conn 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param script 업무 처리에 필요한 script 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun applyMigration(
        conn: Connection,
        script: MigrationScript,
    ): VoucherMigrationResult {
        conn.createStatement().use { statement ->
            script.sql
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(statement::execute)
        }

        val insertSql = "INSERT INTO voucher_schema_history(version, checksum) VALUES (?, ?)"
        conn.prepareStatement(insertSql).use { stmt ->
            stmt.setString(1, script.version)
            stmt.setString(2, script.checksum)
            stmt.executeUpdate()
        }
        conn.commit()
        log.info { "voucher_migration_applied version=${script.version}" }
        return VoucherMigrationResult.APPLIED
    }

    /**
     * rollbackQuietly은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun Connection.rollbackQuietly() {
        runCatching { rollback() }
    }
}
