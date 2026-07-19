package io.bluetape4k.workshop.commerce.voucher.config

import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

internal data class VoucherRetentionProperties(
    val audit: Duration = Duration.ofDays(90),
    val terminalExtra: Duration = Duration.ofDays(7),
    val appliedEvent: Duration = Duration.ofDays(30),
    val purgeBatchSize: Int = 200,
)

internal data class VoucherRetentionCutoffs(
    val auditBefore: Instant,
    val terminalBefore: Instant,
    val appliedEventBefore: Instant,
)

/** Computes retention watermarks without permitting terminal replay to expire before a voucher. */
internal class VoucherRetentionPolicy(
    private val properties: VoucherRetentionProperties,
) {
    init {
        require(properties.audit >= Duration.ofDays(90)) { "audit retention must be at least 90 days" }
        require(properties.terminalExtra >= Duration.ofDays(7)) { "terminal extra retention must be at least 7 days" }
        require(properties.appliedEvent >= Duration.ofDays(30)) { "applied event retention must be at least 30 days" }
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

/** Reads every persisted generation/verification key reference before startup accepts a key ring. */
internal class PostgresReferencedKeyVersionSource(
    private val dataSource: DataSource,
) : ReferencedKeyVersionSource {
    override fun referencedVersions(): ReferencedKeyVersions {
        val generation = linkedSetOf<Int>()
        val verification = linkedSetOf<Int>()
        dataSource.connection.use { connection ->
            if (!connection.hasReferenceTables()) return ReferencedKeyVersions()
            connection.prepareStatement(REFERENCED_KEY_SQL).use { statement ->
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        rows.getInt("generation_key_version").takeUnless { rows.wasNull() }?.let(generation::add)
                        rows.getInt("verification_key_version").takeUnless { rows.wasNull() }?.let(verification::add)
                    }
                }
            }
        }
        return ReferencedKeyVersions(generation, verification)
    }

    /** A fresh database is validated before the migration callback runs and therefore has no references yet. */
    private fun java.sql.Connection.hasReferenceTables(): Boolean =
        prepareStatement(REFERENCE_TABLES_SQL).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }

    private companion object {
        val REFERENCE_TABLES_SQL =
            """
            SELECT to_regclass(current_schema() || '.voucher_claims') IS NOT NULL
               AND to_regclass(current_schema() || '.voucher_http_idempotency') IS NOT NULL
            """.trimIndent()

        val REFERENCED_KEY_SQL =
            """
            SELECT generation_key_version, verification_key_version
            FROM voucher_claims
            WHERE generation_key_version IS NOT NULL OR verification_key_version IS NOT NULL
            UNION ALL
            SELECT generation_key_version, verification_key_version
            FROM voucher_http_idempotency
            WHERE generation_key_version IS NOT NULL OR verification_key_version IS NOT NULL
            """.trimIndent()
    }
}

/** Rejects removal of a key version while any durable claim or replay row still references it. */
internal class VoucherKeyRotationPolicy(
    private val references: ReferencedKeyVersionSource,
) {
    fun canRetire(version: Int): Boolean {
        require(version > 0) { "version must be positive" }
        val referenced = references.referencedVersions()
        return version !in referenced.generation && version !in referenced.verification
    }
}
