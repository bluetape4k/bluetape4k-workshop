@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.javatimes.days
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.idempotency.HttpIdempotencyTable
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimTable
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * VoucherRetentionProperties은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherRetentionProperties(
    /**
     * `audit`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val audit: Duration = 90.days(),
    /**
     * `terminalExtra`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val terminalExtra: Duration = 7.days(),
    /**
     * `appliedEvent`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val appliedEvent: Duration = 30.days(),
    /**
     * `purgeBatchSize`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val purgeBatchSize: Int = 200,
)

/**
 * VoucherRetentionCutoffs은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherRetentionCutoffs(
    /**
     * `auditBefore`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val auditBefore: Instant,
    /**
     * `terminalBefore`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val terminalBefore: Instant,
    /**
     * `appliedEventBefore`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val appliedEventBefore: Instant,
)

/** voucher보다 terminal replay가 먼저 만료되지 않도록 retention watermark를 계산합니다. */
/** voucher보다 terminal replay가 먼저 만료되지 않도록 retention watermark를 계산합니다. */
internal class VoucherRetentionPolicy(
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: VoucherRetentionProperties,
) {

    companion object: KLogging()

    init {
        require(properties.audit >= 90.days()) { "audit retention must be at least 90 days" }
        require(properties.terminalExtra >= 7.days()) { "terminal extra retention must be at least 7 days" }
        require(properties.appliedEvent >= 30.days()) { "applied event retention must be at least 30 days" }
        require(properties.purgeBatchSize in 1..200) { "purge batch size must contain 1..200" }
    }

    /**
     * cutoffs은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun cutoffs(
        now: Instant,
        maximumVoucherTtl: Duration,
    ): VoucherRetentionCutoffs {
        require(!maximumVoucherTtl.isNegative) { "maximumVoucherTtl must not be negative" }

        return VoucherRetentionCutoffs(
            auditBefore = now.minus(properties.audit),
            terminalBefore = now.minus(maximumVoucherTtl.plus(properties.terminalExtra)),
            appliedEventBefore = now.minus(properties.appliedEvent),
        )
    }
}

/** startup이 key ring을 수락하기 전에 persisted generation/verification key reference를 모두 읽습니다. */
/** startup이 key ring을 수락하기 전에 persisted generation/verification key reference를 모두 읽습니다. */
internal class PostgresReferencedKeyVersionSource(
    /**
     * `dataSource`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
): ReferencedKeyVersionSource {

    private companion object: KLogging() {
        /**
         * `REFERENCE_TABLES_SQL`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val REFERENCE_TABLES_SQL =
            """
            SELECT to_regclass(current_schema() || '.voucher_claims') IS NOT NULL
               AND to_regclass(current_schema() || '.voucher_http_idempotency') IS NOT NULL
            """.trimIndent()

    }

    /**
     * referencedVersions은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun referencedVersions(): ReferencedKeyVersions {
        val generation = linkedSetOf<Int>()
        val verification = linkedSetOf<Int>()

        val database = Database.connect(dataSource)
        return try {
            transaction(database) {
                val connection = TransactionManager.current().connection.connection as Connection
                if (!connection.hasReferenceTables()) {
                    ReferencedKeyVersions()
                } else {
                    ClaimTable
                        .select(ClaimTable.generationKeyVersion, ClaimTable.verificationKeyVersion)
                        .where {
                            ClaimTable.generationKeyVersion.isNotNull() or
                                    ClaimTable.verificationKeyVersion.isNotNull()
                        }
                        .forEach { row ->
                            row[ClaimTable.generationKeyVersion]?.let(generation::add)
                            row[ClaimTable.verificationKeyVersion]?.let(verification::add)
                        }
                    HttpIdempotencyTable
                        .select(HttpIdempotencyTable.generationKeyVersion, HttpIdempotencyTable.verificationKeyVersion)
                        .where {
                            HttpIdempotencyTable.generationKeyVersion.isNotNull() or
                                    HttpIdempotencyTable.verificationKeyVersion.isNotNull()
                        }
                        .forEach { row ->
                            row[HttpIdempotencyTable.generationKeyVersion]?.let(generation::add)
                            row[HttpIdempotencyTable.verificationKeyVersion]?.let(verification::add)
                        }
                    ReferencedKeyVersions(generation, verification)
                }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    /** fresh database는 migration callback 실행 전에 검증되므로 아직 reference가 없습니다. */
    /** fresh database는 migration callback 실행 전에 검증되므로 아직 reference가 없습니다. */
    private fun Connection.hasReferenceTables(): Boolean =
        prepareStatement(REFERENCE_TABLES_SQL).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }
}

/** durable claim이나 replay row가 참조하는 동안 key version 제거를 거부합니다. */
/** durable claim이나 replay row가 참조하는 동안 key version 제거를 거부합니다. */
internal class VoucherKeyRotationPolicy(
    /**
     * `references`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val references: ReferencedKeyVersionSource,
) {
    /**
     * canRetire은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param version 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun canRetire(version: Int): Boolean {
        version.requirePositiveNumber("version")
        val referenced = references.referencedVersions()

        return version !in referenced.generation && version !in referenced.verification
    }
}
