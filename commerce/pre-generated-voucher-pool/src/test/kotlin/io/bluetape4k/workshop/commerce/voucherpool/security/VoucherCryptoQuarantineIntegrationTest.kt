@file:Suppress("MaxLineLength", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcVoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.withExistingJdbcTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.core.io.ClassPathResource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherCryptoQuarantineIntegrationTest {

    companion object {
        private const val TENANT = "tenant-a"
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private val voucherSchema = "voucher_crypto_${Base58.randomString(8).lowercase()}"

    private val dataSource = postgres.getHikariDataSource {
        schema = voucherSchema
    }

    private val digests = VoucherDigestService.testFixture()
    private val crypto = AesGcmVoucherEnvelopeCrypto(
        VoucherKekRing.of(VoucherKek.of("k1", ByteArray(32) { (31 + it).toByte() })),
        digests,
    )
    private val repository = JdbcVoucherPoolRepository()
    private val storage = VoucherCryptoStorage(repository, crypto)

    @BeforeAll
    fun migrate() {
        adminConnection().use { conn ->
            conn.createStatement().execute("CREATE SCHEMA $voucherSchema")
        }
        VoucherPoolMigrationRunner(
            dataSource = dataSource,
            migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            advisoryLockKey = 537004L,
        ).migrate()
    }

    @AfterAll
    fun cleanup() {
        adminConnection().use { connection ->
            connection.createStatement().execute("DROP SCHEMA IF EXISTS $voucherSchema CASCADE")
        }
        dataSource.close()
    }

    @Test
    fun `repository crypto erasure destroys encrypted material`() {
        val code = CanonicalVoucherCode.of("POOL-ERASE")
        val identity = insertEncryptedEntry(code)

        dataSource.connection.use { conn ->
            conn.autoCommit = false

            val rolledBack = withExistingJdbcTransaction(conn) {
                storage.decryptAndErase(identity, expectedRevision = 0)
            }.shouldBeInstanceOf<VoucherCryptoStorageOutcome.Revealed>()
            rolledBack.code shouldBeEqualTo code
            conn.rollback()
        }
        assertCiphertextPresent(identity, expectedRevision = 0)

        val revealed = committedTransaction {
            storage.decryptAndErase(identity, expectedRevision = 0)
        }.shouldBeInstanceOf<VoucherCryptoStorageOutcome.Revealed>()
        revealed.code shouldBeEqualTo code

        assertCiphertextErased(identity)
    }

    @ParameterizedTest
    @EnumSource(CryptoCorruption::class)
    fun `cryptographic corruption preserves state and records redacted quarantine`(corruption: CryptoCorruption) {
        val code = CanonicalVoucherCode.of("POOL-QUARANTINE-$corruption")
        val identity = insertEncryptedEntry(code, corruption)

        val outcome = committedTransaction {
            storage.decryptAndErase(identity, expectedRevision = 0)
        }.shouldBeInstanceOf<VoucherCryptoStorageOutcome.Quarantined>()
        outcome.reason shouldBeEqualTo corruption.reason

        dataSource.connection.use { conn ->
            val sql = """
                SELECT e.state,e.revision,e.quarantined_at IS NOT NULL,e.code_ciphertext IS NOT NULL,
                       q.source_state,q.source_revision,q.reason_code,q.resolved_at
                  FROM voucher_pool_entries e JOIN voucher_pool_quarantines q USING (tenant_id,entry_id)
                 WHERE e.tenant_id=? 
                   AND e.entry_id=?
                """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, identity.tenantId)
                stmt.setObject(2, identity.entryId)

                stmt.executeQuery().use { result ->
                    result.next()
                    result.getString(1) shouldBeEqualTo "ALLOCATED"
                    result.getLong(2) shouldBeEqualTo 1L
                    result.getBoolean(3).shouldBeTrue()
                    result.getBoolean(4).shouldBeTrue()
                    result.getString(5) shouldBeEqualTo "ALLOCATED"
                    result.getLong(6) shouldBeEqualTo 0L
                    result.getString(7) shouldBeEqualTo corruption.reason.name
                    result.getObject(8).shouldBeNull()
                }
            }
        }
    }

    @Test
    fun `resolved quarantine can be reactivated after corruption recurs`() {
        val identity = insertEncryptedEntry(CanonicalVoucherCode("POOL-REQUARANTINE"), CryptoCorruption.TAG)
        val first = committedTransaction {
            storage.decryptAndErase(identity, 0)
        }.shouldBeInstanceOf<VoucherCryptoStorageOutcome.Quarantined>()
        first.reason shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG
        resolveQuarantine(identity)

        val second = committedTransaction {
            storage.decryptAndErase(identity, 1)
        }.shouldBeInstanceOf<VoucherCryptoStorageOutcome.Quarantined>()
        second.reason shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG

        dataSource.connection.use { conn ->
            val sql = """
                SELECT e.revision,e.quarantined_at IS NOT NULL,q.source_revision,q.reason_code,
                       q.resolved_at IS NULL,q.resolution IS NULL
                  FROM voucher_pool_entries e JOIN voucher_pool_quarantines q USING (tenant_id, entry_id)
                 WHERE e.tenant_id=? 
                   AND e.entry_id=?
                """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, identity.tenantId)
                stmt.setObject(2, identity.entryId)

                stmt.executeQuery().use { result ->
                    result.next()
                    result.getLong(1) shouldBeEqualTo 2L
                    result.getBoolean(2).shouldBeTrue()
                    result.getLong(3) shouldBeEqualTo 1L
                    result.getString(4) shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG.name
                    result.getBoolean(5).shouldBeTrue()
                    result.getBoolean(6).shouldBeTrue()
                }
            }
        }
    }

    @Test
    fun `stable digest tombstones reject physical mutation`() {
        val identity = insertEncryptedEntry(CanonicalVoucherCode("POOL-IMMUTABLE-DEDUP"))

        assertSqlFails("UPDATE voucher_pool_code_dedup SET key_version=2 WHERE tenant_id='${identity.tenantId}'")
        assertSqlFails("DELETE FROM voucher_pool_code_dedup WHERE tenant_id='${identity.tenantId}'")
    }

    private fun insertEncryptedEntry(
        code: CanonicalVoucherCode,
        corruption: CryptoCorruption? = null,
    ): EntryIdentity {
        val identity = EntryIdentity(
            tenantId = TENANT,
            campaignId = UUID.randomUUID(),
            batchId = UUID.randomUUID(),
            entryId = UUID.randomUUID(),
            sourceOrdinal = 0
        )
        val encrypted = crypto.encrypt(identity, code)
        val ciphertext = encrypted.copyCodeCiphertext().also {
            if (corruption == CryptoCorruption.TAG) it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val codeNonce = if (corruption == CryptoCorruption.NONCE) ByteArray(11) else encrypted.copyCodeNonce()

        val stableDigest = if (corruption == CryptoCorruption.DIGEST) {
            digests.stableDedup(TENANT, CanonicalVoucherCode.of("DIFFERENT-CODE"))
        } else {
            digests.stableDedup(TENANT, code)
        }

        val reservationId = UUID.randomUUID()
        val allocationId = UUID.randomUUID()
        val verificationDigest = digests.verification(TENANT, identity.campaignId, allocationId, code)
        val userDigest = ByteArray(32) { (47 + it).toByte() }

        val fixture = AllocatedEntryFixture(
            identity,
            stableDigest,
            verificationDigest,
            encrypted,
            ciphertext,
            codeNonce,
            reservationId,
            allocationId,
            userDigest,
        )
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            insertCampaignAndBatch(conn, identity)
            insertDedupLedger(conn, fixture)
            insertAllocatedEntry(conn, fixture)
            insertReservation(conn, identity, reservationId, userDigest)
            insertAllocation(conn, identity, reservationId, allocationId, userDigest)
            conn.commit()
        }
        return identity
    }

    private fun insertCampaignAndBatch(conn: java.sql.Connection, identity: EntryIdentity) {
        val insertPoolCampaign = """
            INSERT INTO voucher_pool_campaigns
                (tenant_id,campaign_id,state,user_identity_key_version,policy_version,revision)
                VALUES (?,?,'ACTIVE',1,1,0)
            """.trimIndent()
        conn.prepareStatement(insertPoolCampaign).use { stmt ->
            stmt.setString(1, TENANT)
            stmt.setObject(2, identity.campaignId)
            stmt.executeUpdate()
        }

        val insertPoolBatches = """
            INSERT INTO voucher_pool_batches
                (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                 policy_version,activates_at,expected_count,revision)
                VALUES (?,?,?,'ACTIVE','GENERATED',decode('01','hex'),decode('02','hex'),1,now(),1,0)
            """.trimIndent()
        conn.prepareStatement(insertPoolBatches).use { stmt ->
            stmt.setString(1, TENANT)
            stmt.setObject(2, identity.batchId)
            stmt.setObject(3, identity.campaignId)
            stmt.executeUpdate()
        }
    }

    private fun insertAllocatedEntry(
        conn: java.sql.Connection,
        fixture: AllocatedEntryFixture,
    ) {
        val sql = """
            INSERT INTO voucher_pool_entries
                    (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                     verification_digest,verification_key_version,code_ciphertext,code_nonce,wrapped_dek,
                     wrap_nonce,kek_version,reservation_id,allocation_id,user_digest,reserved_at,
                     reservation_expires_at,allocated_at,allocation_expires_at,allocation_policy_version,
                     entitlement_root_id,replacement_count,revision)
            VALUES (?,?,?,?,?,'ALLOCATED',?,?,?,?,?,?,?,?,?,?,?,transaction_timestamp(),
                            transaction_timestamp()+interval '1 hour',transaction_timestamp(),
                            transaction_timestamp()+interval '1 hour',?,?,0,0)
            """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, TENANT); stmt.setObject(2, fixture.identity.entryId)
            stmt.setObject(3, fixture.identity.campaignId); stmt.setObject(4, fixture.identity.batchId)
            stmt.setLong(5, fixture.identity.sourceOrdinal)
            stmt.setBytes(6, fixture.stableDigest.copyBytes())
            stmt.setBytes(7, fixture.verificationDigest.copyBytes())
            stmt.setInt(8, fixture.verificationDigest.keyVersion)
            stmt.setBytes(9, fixture.ciphertext)
            stmt.setBytes(10, fixture.codeNonce)
            stmt.setBytes(11, fixture.encrypted.copyWrappedDek())
            stmt.setBytes(12, fixture.encrypted.copyWrapNonce())
            stmt.setString(13, fixture.encrypted.kekVersion)
            stmt.setObject(14, fixture.reservationId)
            stmt.setObject(15, fixture.allocationId)
            stmt.setBytes(16, fixture.userDigest)
            stmt.setLong(17, 1)
            stmt.setObject(18, fixture.allocationId)
            stmt.executeUpdate()
        }
    }

    private fun insertDedupLedger(conn: java.sql.Connection, fixture: AllocatedEntryFixture) {
        val sql = """
            INSERT INTO voucher_pool_code_dedup
                (tenant_id,stable_dedup_digest,first_campaign_id,first_batch_id,first_entry_id,key_version)
            VALUES (?,?,?,?,?,?)
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, fixture.identity.tenantId)
            stmt.setBytes(2, fixture.stableDigest.copyBytes())
            stmt.setObject(3, fixture.identity.campaignId)
            stmt.setObject(4, fixture.identity.batchId)
            stmt.setObject(5, fixture.identity.entryId)
            stmt.setInt(6, fixture.stableDigest.keyVersion)
            stmt.executeUpdate()
        }
    }

    private fun insertReservation(
        conn: java.sql.Connection,
        identity: EntryIdentity,
        reservationId: UUID,
        userDigest: ByteArray,
    ) {
        val sql = """
            INSERT INTO voucher_pool_reservations
            (tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest,idempotency_owner_digest,
             state,reservation_expires_at,policy_version,revision)
            VALUES (?,?,?,?,?,?,?,'ALLOCATED',transaction_timestamp()+interval '1 hour',1,0)
            """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, TENANT)
            stmt.setObject(2, reservationId)
            stmt.setObject(3, identity.campaignId)
            stmt.setObject(4, identity.batchId)
            stmt.setObject(5, identity.entryId)
            stmt.setBytes(6, userDigest)
            stmt.setBytes(7, ByteArray(32) { (79 + it).toByte() })

            stmt.executeUpdate()
        }
    }

    private fun insertAllocation(
        conn: java.sql.Connection,
        identity: EntryIdentity,
        reservationId: UUID,
        allocationId: UUID,
        userDigest: ByteArray,
    ): Int {
        val sql = """
            INSERT INTO voucher_pool_allocations
            (tenant_id,allocation_id,reservation_id,campaign_id,batch_id,entry_id,user_digest,
             entitlement_root_id,replacement_ordinal,allocation_expires_at,policy_version,revision)
            VALUES (?,?,?,?,?,?,?,?,0,transaction_timestamp()+interval '1 hour',1,0)
            """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, TENANT)
            stmt.setObject(2, allocationId)
            stmt.setObject(3, reservationId)
            stmt.setObject(4, identity.campaignId)
            stmt.setObject(5, identity.batchId)
            stmt.setObject(6, identity.entryId)
            stmt.setBytes(7, userDigest)
            stmt.setObject(8, allocationId)
            stmt.executeUpdate()
        }
    }

    private fun assertCiphertextErased(identity: EntryIdentity) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT revealed_at IS NOT NULL,code_ciphertext IS NULL,code_nonce IS NULL,
                       wrapped_dek IS NULL,wrap_nonce IS NULL,kek_version IS NULL,revision
                  FROM voucher_pool_entries 
                 WHERE tenant_id=? 
                   AND entry_id=?
                """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, identity.tenantId)
                stmt.setObject(2, identity.entryId)

                stmt.executeQuery().use { result ->
                    assertErasedRow(result)
                }
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun assertCiphertextPresent(identity: EntryIdentity, expectedRevision: Long) {
        val sql = """
            SELECT revealed_at IS NULL,code_ciphertext IS NOT NULL,code_nonce IS NOT NULL,
                    wrapped_dek IS NOT NULL,wrap_nonce IS NOT NULL,kek_version IS NOT NULL,revision
              FROM voucher_pool_entries 
             WHERE tenant_id=? 
               AND entry_id=?
            """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, identity.tenantId)
                stmt.setObject(2, identity.entryId)

                stmt.executeQuery().use { result ->
                    assertErasedRow(result, expectedRevision)
                }
            }
        }
    }

    private fun resolveQuarantine(identity: EntryIdentity) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "UPDATE voucher_pool_entries SET quarantined_at=NULL WHERE tenant_id=? AND entry_id=?",
            ).use { statement ->
                statement.setString(1, identity.tenantId)
                statement.setObject(2, identity.entryId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                    UPDATE voucher_pool_quarantines SET resolved_at=transaction_timestamp(),resolution='CLEARED'
                     WHERE tenant_id=? AND entry_id=?
                     """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, identity.tenantId)
                stmt.setObject(2, identity.entryId)
                stmt.executeUpdate()
            }
            connection.commit()
        }
    }

    private inline fun <T> committedTransaction(crossinline block: () -> T): T =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
        try {
            withExistingJdbcTransaction(conn, block).also { conn.commit() }
        } catch (failure: Throwable) {
            runCatching { conn.rollback() }
            throw failure
        }
    }

    private fun assertSqlFails(sql: String) {
        assertFailsWith<java.sql.SQLException> {
            dataSource.connection.use { conn ->
                conn.createStatement().use {
                    it.execute(sql)
                }
            }
        }
    }

    private fun assertErasedRow(result: ResultSet, expectedRevision: Long = 1) {
        result.next()
        (1..6).forEach { result.getBoolean(it).shouldBeTrue() }
        result.getLong(7) shouldBeEqualTo expectedRevision
    }

    private data class AllocatedEntryFixture(
        val identity: EntryIdentity,
        val stableDigest: VoucherDigest,
        val verificationDigest: VoucherDigest,
        val encrypted: EncryptedVoucherCode,
        val ciphertext: ByteArray,
        val codeNonce: ByteArray,
        val reservationId: UUID,
        val allocationId: UUID,
        val userDigest: ByteArray,
    )

    enum class CryptoCorruption(val reason: VoucherCryptoFailureReason) {
        TAG(VoucherCryptoFailureReason.INVALID_TAG),
        DIGEST(VoucherCryptoFailureReason.DIGEST_MISMATCH),
        NONCE(VoucherCryptoFailureReason.INVALID_CIPHERTEXT),
    }

    private fun adminConnection(): Connection =
        DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username ?: PostgreSQLServer.USERNAME,
        postgres.password ?: PostgreSQLServer.PASSWORD,
    )
}

internal fun VoucherDigestService.Companion.testFixture(): VoucherDigestService =
    VoucherDigestService(
        stableDedupKey = DigestKey(1, ByteArray(32) { it.toByte() }),
        commandTombstoneKey = DigestKey(4, ByteArray(32) { (51 + it).toByte() }),
    rotatingKeys = ROTATING_TEST_PURPOSES.associateWith { purpose ->
        DigestKeyRing(
            DigestKey(
                purpose.ordinal + 1,
                ByteArray(32) { (purpose.ordinal * 17 + it).toByte() })
        )
    },
)

private val ROTATING_TEST_PURPOSES = setOf(
    DigestPurpose.VERIFICATION,
    DigestPurpose.USER_IDENTITY,
    DigestPurpose.REDIS_SIGNAL,
    DigestPurpose.AUDIT,
)
