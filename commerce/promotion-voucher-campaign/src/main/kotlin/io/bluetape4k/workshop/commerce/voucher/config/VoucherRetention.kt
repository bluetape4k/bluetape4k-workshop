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

internal data class VoucherRetentionProperties(
    val audit: Duration = 90.days(),
    val terminalExtra: Duration = 7.days(),
    val appliedEvent: Duration = 30.days(),
    val purgeBatchSize: Int = 200,
)

internal data class VoucherRetentionCutoffs(
    val auditBefore: Instant,
    val terminalBefore: Instant,
    val appliedEventBefore: Instant,
)

/** voucher보다 terminal replay가 먼저 만료되지 않도록 retention watermark를 계산합니다. */
internal class VoucherRetentionPolicy(
    private val properties: VoucherRetentionProperties,
) {

    companion object: KLogging()

    init {
        require(properties.audit >= 90.days()) { "audit retention must be at least 90 days" }
        require(properties.terminalExtra >= 7.days()) { "terminal extra retention must be at least 7 days" }
        require(properties.appliedEvent >= 30.days()) { "applied event retention must be at least 30 days" }
        require(properties.purgeBatchSize in 1..200) { "purge batch size must contain 1..200" }
    }

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
internal class PostgresReferencedKeyVersionSource(
    private val dataSource: DataSource,
): ReferencedKeyVersionSource {

    private companion object: KLogging() {
        val REFERENCE_TABLES_SQL =
            """
            SELECT to_regclass(current_schema() || '.voucher_claims') IS NOT NULL
               AND to_regclass(current_schema() || '.voucher_http_idempotency') IS NOT NULL
            """.trimIndent()

    }

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
    private fun Connection.hasReferenceTables(): Boolean =
        prepareStatement(REFERENCE_TABLES_SQL).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }
}

/** durable claim이나 replay row가 참조하는 동안 key version 제거를 거부합니다. */
internal class VoucherKeyRotationPolicy(
    private val references: ReferencedKeyVersionSource,
) {
    fun canRetire(version: Int): Boolean {
        version.requirePositiveNumber("version")
        val referenced = references.referencedVersions()

        return version !in referenced.generation && version !in referenced.verification
    }
}
