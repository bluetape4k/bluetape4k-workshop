@file:Suppress("MagicNumber", "MaxLineLength", "NestedBlockDepth", "ThrowsCount", "TooGenericExceptionCaught")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCodeDedupTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCommandTombstoneTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRevokePreviewGrantTable
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKeyMaterialUnavailableException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.io.Resource
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource
/**
 * VoucherPoolMigration은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property resource 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class VoucherPoolMigration(val version: String, val resource: Resource) {
    init {
        version.requireNotBlank("version")
    }

    /**
     * read은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun read(): VoucherPoolMigrationScript {
        val bytes = try {
            resource.inputStream.use { it.readAllBytes() }
        } catch (failure: Exception) {
            throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.RESOURCE_UNAVAILABLE, failure)
        }

        return VoucherPoolMigrationScript(
            version = version,
            checksum = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
            sql = bytes.toUtf8String()
        )
    }
}
/**
 * VoucherPoolMigrationScript은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property checksum 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property sql 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 */


internal data class VoucherPoolMigrationScript(
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
 * `VoucherPoolMigrationResult`은 명령 처리 결과입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
internal enum class VoucherPoolMigrationResult {
    /**
     * `VoucherPoolMigrationResult.APPLIED`은 마이그레이션·명령·outbox 반영이 이번 호출에서 실제로 완료된 값입니다.
     */
    APPLIED,
    /**
     * `VoucherPoolMigrationResult.ALREADY_APPLIED`은 동일한 변경이 이미 반영되어 추가 side effect 없이 멱등 완료한 값입니다.
     */
    ALREADY_APPLIED
}

/**
 * `VoucherPoolMigrationFailureCode`은 업무 코드입니다.
 * 호출자가 재시도·거절·대사·운영자 조치를 분기하는 오류 계약입니다.
 */
internal enum class VoucherPoolMigrationFailureCode {
    /**
     * `VoucherPoolMigrationFailureCode.LOCK_TIMEOUT`은 필요한 DB/Redis lock을 제한 시간 안에 얻지 못한 값입니다.
     */
    LOCK_TIMEOUT,
    /**
     * `VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT`은 `checksum drift` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    CHECKSUM_DRIFT,
    /**
     * `VoucherPoolMigrationFailureCode.STATEMENT_FAILED`은 SQL 또는 외부 명령 실행이 실패해 transaction 결과를 확정할
     * 수 없는 값입니다.
     */
    STATEMENT_FAILED,
    /**
     * `VoucherPoolMigrationFailureCode.RESOURCE_UNAVAILABLE`은 필수 DB·Redis·worker 자원이 준비되지 않은 값입니다.
     */
    RESOURCE_UNAVAILABLE,
    /**
     * `VoucherPoolMigrationFailureCode.CONNECTION_UNAVAILABLE`은 필수 의존성이 준비되지 않아 요청을 수용할 수 없는 값입니다.
     */
    CONNECTION_UNAVAILABLE,
}
/**
 * VoucherPoolMigrationException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property code 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


internal class VoucherPoolMigrationException(
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: VoucherPoolMigrationFailureCode,
    cause: Throwable? = null,
): RuntimeException(code.name, cause)

/**
 * versioned SQL을 transaction advisory lock 아래에서 적용하고 checksum drift가 있으면 fail-closed합니다.
 * @property dataSource 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property migration 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property advisoryLockKey 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property lockTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
@Suppress("SqlResolve")
internal class VoucherPoolMigrationRunner(
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
    private val migration: VoucherPoolMigration,
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
    init {
        require(!lockTimeout.isNegative && !lockTimeout.isZero) { "lockTimeout must be positive" }
    }

    /**
     * migrate은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun migrate(): VoucherPoolMigrationResult =
        try {
            migrate(migration.read())
        } catch (failure: VoucherPoolMigrationException) {
            log.warn(failure) {
                "voucher_pool_migration_failed version=${migration.version} code=${failure.code}"
            }
            throw failure
        } catch (failure: SQLException) {
            /**
             * `mapped`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val mapped = VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.CONNECTION_UNAVAILABLE, failure)
            log.warn(mapped) {
                "voucher_pool_migration_failed version=${migration.version} code=${mapped.code}"
            }
            throw mapped
        }

    /**
     * migrate은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param script 업무 처리에 필요한 script 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun migrate(script: VoucherPoolMigrationScript): VoucherPoolMigrationResult =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireLock(connection)
                connection.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS voucher_pool_schema_history(
                            version VARCHAR(64) PRIMARY KEY, 
                            checksum VARCHAR(64) NOT NULL,
                            applied_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp()
                        )
                        """.trimIndent(),

                    )
                }
                when (appliedChecksum(connection, script.version)) {
                    null            ->
                        apply(connection, script).also {
                            log.info { "voucher_pool_migration_applied version=${script.version}" }
                        }

                    script.checksum -> {
                        connection.commit()
                        log.info { "voucher_pool_migration_already_applied version=${script.version}" }
                        VoucherPoolMigrationResult.ALREADY_APPLIED
                    }

                    else            ->
                        throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT)
                }
            } catch (failure: VoucherPoolMigrationException) {
                runCatching { connection.rollback() }
                throw failure
            } catch (failure: SQLException) {
                runCatching { connection.rollback() }
                throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.STATEMENT_FAILED, failure)
            }
        }

    /**
     * acquireLock은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun acquireLock(connection: Connection) {
        val deadline = System.nanoTime() + lockTimeout.toNanos()
        connection
            .prepareStatement("SELECT pg_try_advisory_xact_lock(?)")
            .use { stmt ->
                stmt.setLong(1, advisoryLockKey)
                while (true) {
                    stmt.executeQuery().use { result ->
                        if (result.next() && result.getBoolean(1)) return
                    }
                    if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
                        throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.LOCK_TIMEOUT)
                    }
                    LockSupport.parkNanos(10.millis().toNanos())
                }
            }
    }

    /**
     * appliedChecksum은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun appliedChecksum(connection: Connection, version: String): String? =
        connection
            .prepareStatement("SELECT checksum FROM voucher_pool_schema_history WHERE version=?")
            .use { stmt ->
                stmt.setString(1, version)
                stmt.executeQuery().use { result ->
                    if (result.next()) result.getString(1) else null
                }
            }

    /**
     * apply은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param script 업무 처리에 필요한 script 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun apply(connection: Connection, script: VoucherPoolMigrationScript): VoucherPoolMigrationResult {
        connection.createStatement().use { stmt ->
            splitSqlStatements(script.sql).forEach(stmt::execute)
        }
        connection
            .prepareStatement("INSERT INTO voucher_pool_schema_history(version,checksum) VALUES (?,?)")
            .use { stmt ->
                stmt.setString(1, script.version)
                stmt.setString(2, script.checksum)
                stmt.executeUpdate()
            }
        connection.commit()
        return VoucherPoolMigrationResult.APPLIED
    }

    companion object: KLogging()

    /**
     * splitSqlStatements은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param sql 업무 처리에 필요한 sql 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // The scanner keeps PostgreSQL lexical states explicit.
    private fun splitSqlStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var singleQuoted = false
        var doubleQuoted = false
        var lineComment = false
        var blockCommentDepth = 0
        var dollarTag: String? = null

        while (index < sql.length) {
            val next = sql.getOrNull(index + 1)
            val activeDollarTag = dollarTag
            when {
                activeDollarTag != null -> {
                    if (sql.startsWith(activeDollarTag, index)) {
                        current.append(activeDollarTag)
                        index += activeDollarTag.length
                        dollarTag = null
                    } else {
                        current.append(sql[index++])
                    }
                }

                lineComment             -> {
                    current.append(sql[index])
                    if (sql[index++] == '\n') lineComment = false
                }

                blockCommentDepth > 0   -> {
                    when {
                        sql[index] == '/' && next == '*' -> {
                            current.append("/*")
                            blockCommentDepth++
                            index += 2
                        }
                        sql[index] == '*' && next == '/' -> {
                            current.append("*/")
                            blockCommentDepth--
                            index += 2
                        }
                        else -> current.append(sql[index++])
                    }
                }

                singleQuoted            -> {
                    current.append(sql[index])
                    if (sql[index] == '\'' && next == '\'') {
                        current.append(next)
                        index += 2
                    } else {
                        if (sql[index] == '\'') singleQuoted = false
                        index++
                    }
                }

                doubleQuoted            -> {
                    current.append(sql[index])
                    if (sql[index] == '"' && next == '"') {
                        current.append(next)
                        index += 2
                    } else {
                        if (sql[index] == '"') doubleQuoted = false
                        index++
                    }
                }

                sql[index] == '-' && next == '-' -> {
                    current.append("--")
                    lineComment = true
                    index += 2
                }

                sql[index] == '/' && next == '*' -> {
                    current.append("/*")
                    blockCommentDepth = 1
                    index += 2
                }

                sql[index] == '\''      -> {
                    current.append(sql[index++])
                    singleQuoted = true
                }

                sql[index] == '"'       -> {
                    current.append(sql[index++])
                    doubleQuoted = true
                }

                sql[index] == '$'       -> {
                    val tagEnd = sql.indexOf('$', startIndex = index + 1)
                    val tag = tagEnd.takeIf { it >= 0 }?.let { sql.substring(index, it + 1) }
                    if (tag != null && tag.drop(1).dropLast(1).all { it == '_' || it.isLetterOrDigit() }) {
                        current.append(tag)
                        dollarTag = tag
                        index += tag.length
                    } else {
                        current.append(sql[index++])
                    }
                }

                sql[index] == ';'       -> {
                    current.toString().trim().takeIf(String::isNotEmpty)?.let(statements::add)
                    current.clear()
                    index++
                }

                else                    ->
                    current.append(sql[index++])
            }
        }
        check(!singleQuoted && !doubleQuoted && blockCommentDepth == 0 && dollarTag == null) {
            "unterminated SQL literal or comment"
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(statements::add)

        return statements.also { parsedStatements ->
            parsedStatements.forEachIndexed { index, statementSql ->
                log.debug { "sql[$index]: $statementSql" }
            }
        }
    }
}

/**
 * live PostgreSQL authority row가 참조하는 key version에 대한 bounded purpose-aware verification입니다.
 * @property dataSource 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property digests 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property kekRing 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class VoucherPoolReferencedKeyPreflight(
    /**
     * `dataSource`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
    /**
     * `digests`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digests: VoucherDigestService,
    /**
     * `kekRing`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val kekRing: VoucherKekRing,
) {
    private companion object: KLogging() {
        /**
         * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
         */
        const val MAX_REFERENCED_VERSIONS = 64
    }

    /**
     * verify은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun verify() {
        withExposedTransaction {
            distinctStrings(VoucherPoolEntryTable.kekVersion) {
                VoucherPoolEntryTable.select(VoucherPoolEntryTable.kekVersion)
                    .where { VoucherPoolEntryTable.kekVersion.isNotNull() }
            }.forEach(kekRing::require)

            distinctNullableInts(VoucherPoolEntryTable.verificationKeyVersion) {
                VoucherPoolEntryTable.select(VoucherPoolEntryTable.verificationKeyVersion)
                    .where { VoucherPoolEntryTable.verificationKeyVersion.isNotNull() }
            }.forEach {
                digests.requireAvailable(DigestPurpose.VERIFICATION, it)
            }
            distinctInts(VoucherPoolCampaignTable.userIdentityKeyVersion) {
                VoucherPoolCampaignTable.select(VoucherPoolCampaignTable.userIdentityKeyVersion)
            }.forEach {
                digests.requireAvailable(DigestPurpose.USER_IDENTITY, it)
            }
            val auditVersions = buildList {
                addAll(distinctNullableInts(VoucherPoolAuditTable.auditKeyVersion) {
                    VoucherPoolAuditTable.select(VoucherPoolAuditTable.auditKeyVersion)
                        .where { VoucherPoolAuditTable.auditKeyVersion.isNotNull() }
                })
                addAll(distinctInts(VoucherPoolRevokePreviewGrantTable.signatureKeyVersion) {
                    VoucherPoolRevokePreviewGrantTable.select(VoucherPoolRevokePreviewGrantTable.signatureKeyVersion)
                })
            }.distinct().sorted().also(::requireBounded)
            auditVersions.forEach {
                digests.requireAvailable(DigestPurpose.AUDIT, it)
            }
            distinctInts(VoucherPoolCodeDedupTable.keyVersion) {
                VoucherPoolCodeDedupTable.select(VoucherPoolCodeDedupTable.keyVersion)
            }.forEach {
                digests.requireAvailable(DigestPurpose.STABLE_DEDUP, it)
            }
            distinctInts(VoucherPoolCommandTombstoneTable.keyVersion) {
                VoucherPoolCommandTombstoneTable.select(VoucherPoolCommandTombstoneTable.keyVersion)
            }.forEach {
                digests.requireAvailable(DigestPurpose.COMMAND_TOMBSTONE, it)
            }
        }
    }

    /**
     * distinctInts은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param column 업무 처리에 필요한 column 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param query 업무 처리에 필요한 query 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun distinctInts(column: org.jetbrains.exposed.v1.core.Column<Int>, query: () -> org.jetbrains.exposed.v1.jdbc.Query): List<Int> =
        query().withDistinct().orderBy(column to SortOrder.ASC).limit(MAX_REFERENCED_VERSIONS + 1)
            .map { it[column] }
            .also(::requireBounded)

    /**
     * distinctNullableInts은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param column 업무 처리에 필요한 column 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param query 업무 처리에 필요한 query 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun distinctNullableInts(column: org.jetbrains.exposed.v1.core.Column<Int?>, query: () -> org.jetbrains.exposed.v1.jdbc.Query): List<Int> =
        query().withDistinct().orderBy(column to SortOrder.ASC).limit(MAX_REFERENCED_VERSIONS + 1)
            .mapNotNull { it[column] }
            .also(::requireBounded)

    /**
     * distinctStrings은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param column 업무 처리에 필요한 column 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param query 업무 처리에 필요한 query 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun distinctStrings(column: org.jetbrains.exposed.v1.core.Column<String?>, query: () -> org.jetbrains.exposed.v1.jdbc.Query): List<String> =
        query().withDistinct().orderBy(column to SortOrder.ASC).limit(MAX_REFERENCED_VERSIONS + 1)
            .mapNotNull { it[column] }
            .also(::requireBounded)

    /**
     * requireBounded은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param versions 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun requireBounded(versions: List<*>): List<*> = versions.also {
        if (it.size > MAX_REFERENCED_VERSIONS) throw VoucherKeyMaterialUnavailableException()
    }

    /**
     * withExposedTransaction은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private inline fun <T> withExposedTransaction(crossinline block: () -> T): T {
        val database = Database.connect(dataSource)
        return try {
            transaction(database) { block() }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }
}

/** migration과 live referenced-key verification이 완료된 뒤에만 readiness를 엽니다.
 * @property migration 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property keyPreflight 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property health 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class VoucherPoolStartupInitializer(
    /**
     * `migration`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val migration: VoucherPoolMigrationRunner,
    /**
     * `keyPreflight`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val keyPreflight: VoucherPoolReferencedKeyPreflight,
    /**
     * `health`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val health: VoucherPoolHealthState,
): SmartInitializingSingleton {

    companion object: KLogging()

    /**
     * afterSingletonsInstantiated은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun afterSingletonsInstantiated() {
        try {
            migration.migrate()
            health.recover(VoucherPoolHealthComponent.MIGRATION)
        } catch (failure: RuntimeException) {
            health.fail(
                VoucherPoolHealthComponent.MIGRATION,
                VoucherPoolHealthReason.MIGRATION_UNAVAILABLE
            )
            throw failure
        }
        try {
            keyPreflight.verify()
            health.recover(VoucherPoolHealthComponent.REFERENCED_KEYS)
        } catch (failure: RuntimeException) {
            health.fail(
                VoucherPoolHealthComponent.REFERENCED_KEYS,
                VoucherPoolHealthReason.REFERENCED_KEY_UNAVAILABLE,
            )
            throw failure
        }
        log.info { "voucher_pool_startup_authority_ready migration=001 referencedKeys=verified" }
    }
}
