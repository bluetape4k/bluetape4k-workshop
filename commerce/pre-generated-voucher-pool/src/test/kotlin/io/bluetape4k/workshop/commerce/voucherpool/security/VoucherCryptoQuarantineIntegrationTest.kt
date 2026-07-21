@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcVoucherPoolRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherCryptoQuarantineIntegrationTest {
    private val schema = "voucher_crypto_${Base58.randomString(8).lowercase()}"
    private val dataSource = PGSimpleDataSource().apply {
        setURL(postgres.jdbcUrl)
        user = postgres.username ?: PostgreSQLServer.USERNAME
        password = postgres.password ?: PostgreSQLServer.PASSWORD
        currentSchema = schema
    }
    private val digests = VoucherDigestService.testFixture()
    private val crypto = AesGcmVoucherEnvelopeCrypto(
        VoucherKekRing.of(VoucherKek.of("k1", ByteArray(32) { (31 + it).toByte() })),
        digests,
    )
    private val repository = JdbcVoucherPoolRepository(dataSource)
    private val storage = VoucherCryptoStorage(repository, crypto)

    @BeforeAll
    fun migrate() {
        adminConnection().use { connection -> connection.createStatement().execute("CREATE SCHEMA $schema") }
        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            537004L,
        ).migrate()
    }

    @AfterAll
    fun cleanup() {
        adminConnection().use { connection -> connection.createStatement().execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    @Test
    fun `repository crypto erasure destroys encrypted material`() {
        val code = CanonicalVoucherCode.of("POOL-ERASE")
        val identity = insertEncryptedEntry(code)

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val rolledBack = repository.withExistingConnection(connection) {
                storage.decryptAndErase(connection, identity, expectedRevision = 0)
            }
            (rolledBack as VoucherCryptoStorageOutcome.Revealed).code shouldBeEqualTo code
            connection.rollback()
        }
        assertCiphertextPresent(identity, expectedRevision = 0)

        val revealed = committedTransaction { connection ->
            storage.decryptAndErase(connection, identity, expectedRevision = 0)
        }
        (revealed as VoucherCryptoStorageOutcome.Revealed).code shouldBeEqualTo code
        assertCiphertextErased(identity)
    }

    @ParameterizedTest
    @EnumSource(CryptoCorruption::class)
    fun `cryptographic corruption preserves state and records redacted quarantine`(corruption: CryptoCorruption) {
        val code = CanonicalVoucherCode.of("POOL-QUARANTINE-$corruption")
        val identity = insertEncryptedEntry(code, corruption)

        val outcome = committedTransaction { connection ->
            storage.decryptAndErase(connection, identity, expectedRevision = 0)
        }
        (outcome as VoucherCryptoStorageOutcome.Quarantined).reason shouldBeEqualTo corruption.reason

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT e.state,e.revision,e.quarantined_at IS NOT NULL,e.code_ciphertext IS NOT NULL,
                    q.source_state,q.source_revision,q.reason_code,q.resolved_at
                    FROM voucher_pool_entries e JOIN voucher_pool_quarantines q USING (tenant_id,entry_id)
                    WHERE e.tenant_id=? AND e.entry_id=?""",
            ).use { statement ->
                statement.setString(1, identity.tenantId); statement.setObject(2, identity.entryId)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getString(1) shouldBeEqualTo "ALLOCATED"
                    result.getLong(2) shouldBeEqualTo 1L
                    result.getBoolean(3) shouldBeEqualTo true
                    result.getBoolean(4) shouldBeEqualTo true
                    result.getString(5) shouldBeEqualTo "ALLOCATED"
                    result.getLong(6) shouldBeEqualTo 0L
                    result.getString(7) shouldBeEqualTo corruption.reason.name
                    result.getObject(8) shouldBeEqualTo null
                }
            }
        }
    }

    @Test
    fun `resolved quarantine can be reactivated after corruption recurs`() {
        val identity = insertEncryptedEntry(CanonicalVoucherCode.of("POOL-REQUARANTINE"), CryptoCorruption.TAG)
        val first = committedTransaction { connection -> storage.decryptAndErase(connection, identity, 0) }
        (first as VoucherCryptoStorageOutcome.Quarantined).reason shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG
        resolveQuarantine(identity)

        val second = committedTransaction { connection -> storage.decryptAndErase(connection, identity, 1) }
        (second as VoucherCryptoStorageOutcome.Quarantined).reason shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT e.revision,e.quarantined_at IS NOT NULL,q.source_revision,q.reason_code,
                    q.resolved_at IS NULL,q.resolution IS NULL
                    FROM voucher_pool_entries e JOIN voucher_pool_quarantines q USING (tenant_id,entry_id)
                    WHERE e.tenant_id=? AND e.entry_id=?""",
            ).use { statement ->
                statement.setString(1, identity.tenantId); statement.setObject(2, identity.entryId)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getLong(1) shouldBeEqualTo 2L
                    result.getBoolean(2) shouldBeEqualTo true
                    result.getLong(3) shouldBeEqualTo 1L
                    result.getString(4) shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG.name
                    result.getBoolean(5) shouldBeEqualTo true
                    result.getBoolean(6) shouldBeEqualTo true
                }
            }
        }
    }

    @Test
    fun `stable digest tombstones reject physical mutation`() {
        val identity = insertEncryptedEntry(CanonicalVoucherCode.of("POOL-IMMUTABLE-DEDUP"))

        assertSqlFails("UPDATE voucher_pool_code_dedup SET key_version=2 WHERE tenant_id='${identity.tenantId}'")
        assertSqlFails("DELETE FROM voucher_pool_code_dedup WHERE tenant_id='${identity.tenantId}'")
    }

    private fun insertEncryptedEntry(
        code: CanonicalVoucherCode,
        corruption: CryptoCorruption? = null,
    ): EntryIdentity {
        val identity = EntryIdentity(TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0)
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
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            insertCampaignAndBatch(connection, identity)
            insertDedupLedger(connection, fixture)
            insertAllocatedEntry(connection, fixture)
            insertReservation(connection, identity, reservationId, userDigest)
            insertAllocation(connection, identity, reservationId, allocationId, userDigest)
            connection.commit()
        }
        return identity
    }

    private fun insertCampaignAndBatch(connection: java.sql.Connection, identity: EntryIdentity) {
        connection.prepareStatement(
            """INSERT INTO voucher_pool_campaigns
                (tenant_id,campaign_id,state,user_identity_key_version,policy_version,revision)
                VALUES (?,?,'ACTIVE',1,1,0)""",
        ).use { statement ->
            statement.setString(1, TENANT); statement.setObject(2, identity.campaignId); statement.executeUpdate()
        }
        connection.prepareStatement(
            """INSERT INTO voucher_pool_batches
                (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                 policy_version,activates_at,expected_count,revision)
                VALUES (?,?,?,'ACTIVE','GENERATED',decode('01','hex'),decode('02','hex'),1,now(),1,0)""",
        ).use { statement ->
            statement.setString(1, TENANT); statement.setObject(2, identity.batchId)
            statement.setObject(3, identity.campaignId); statement.executeUpdate()
        }
    }

    private fun insertAllocatedEntry(
        connection: java.sql.Connection,
        fixture: AllocatedEntryFixture,
    ) {
        connection.prepareStatement(
                """INSERT INTO voucher_pool_entries
                    (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                     verification_digest,verification_key_version,code_ciphertext,code_nonce,wrapped_dek,
                     wrap_nonce,kek_version,reservation_id,allocation_id,user_digest,reserved_at,
                     reservation_expires_at,allocated_at,allocation_expires_at,allocation_policy_version,
                     entitlement_root_id,replacement_count,revision)
                    VALUES (?,?,?,?,?,'ALLOCATED',?,?,?,?,?,?,?,?,?,?,?,transaction_timestamp(),
                            transaction_timestamp()+interval '1 hour',transaction_timestamp(),
                            transaction_timestamp()+interval '1 hour',?,?,0,0)""",
        ).use { statement ->
            statement.setString(1, TENANT); statement.setObject(2, fixture.identity.entryId)
            statement.setObject(3, fixture.identity.campaignId); statement.setObject(4, fixture.identity.batchId)
            statement.setLong(5, fixture.identity.sourceOrdinal)
            statement.setBytes(6, fixture.stableDigest.copyBytes())
            statement.setBytes(7, fixture.verificationDigest.copyBytes())
            statement.setInt(8, fixture.verificationDigest.keyVersion)
            statement.setBytes(9, fixture.ciphertext)
            statement.setBytes(10, fixture.codeNonce)
            statement.setBytes(11, fixture.encrypted.copyWrappedDek())
            statement.setBytes(12, fixture.encrypted.copyWrapNonce())
            statement.setString(13, fixture.encrypted.kekVersion)
            statement.setObject(14, fixture.reservationId)
            statement.setObject(15, fixture.allocationId)
            statement.setBytes(16, fixture.userDigest)
            statement.setLong(17, 1)
            statement.setObject(18, fixture.allocationId)
            statement.executeUpdate()
        }
    }

    private fun insertDedupLedger(connection: java.sql.Connection, fixture: AllocatedEntryFixture) =
        connection.prepareStatement(
            """INSERT INTO voucher_pool_code_dedup
                (tenant_id,stable_dedup_digest,first_campaign_id,first_batch_id,first_entry_id,key_version)
                VALUES (?,?,?,?,?,?)""",
        ).use { statement ->
            statement.setString(1, fixture.identity.tenantId)
            statement.setBytes(2, fixture.stableDigest.copyBytes())
            statement.setObject(3, fixture.identity.campaignId)
            statement.setObject(4, fixture.identity.batchId)
            statement.setObject(5, fixture.identity.entryId)
            statement.setInt(6, fixture.stableDigest.keyVersion)
            statement.executeUpdate()
        }

    private fun insertReservation(
        connection: java.sql.Connection,
        identity: EntryIdentity,
        reservationId: UUID,
        userDigest: ByteArray,
    ) = connection.prepareStatement(
        """INSERT INTO voucher_pool_reservations
            (tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest,idempotency_owner_digest,
             state,reservation_expires_at,policy_version,revision)
            VALUES (?,?,?,?,?,?,?,'ALLOCATED',transaction_timestamp()+interval '1 hour',1,0)""",
    ).use { statement ->
        statement.setString(1, TENANT); statement.setObject(2, reservationId)
        statement.setObject(3, identity.campaignId); statement.setObject(4, identity.batchId)
        statement.setObject(5, identity.entryId); statement.setBytes(6, userDigest)
        statement.setBytes(7, ByteArray(32) { (79 + it).toByte() }); statement.executeUpdate()
    }

    private fun insertAllocation(
        connection: java.sql.Connection,
        identity: EntryIdentity,
        reservationId: UUID,
        allocationId: UUID,
        userDigest: ByteArray,
    ) = connection.prepareStatement(
        """INSERT INTO voucher_pool_allocations
            (tenant_id,allocation_id,reservation_id,campaign_id,batch_id,entry_id,user_digest,
             entitlement_root_id,replacement_ordinal,allocation_expires_at,policy_version,revision)
            VALUES (?,?,?,?,?,?,?,?,0,transaction_timestamp()+interval '1 hour',1,0)""",
    ).use { statement ->
        statement.setString(1, TENANT); statement.setObject(2, allocationId)
        statement.setObject(3, reservationId); statement.setObject(4, identity.campaignId)
        statement.setObject(5, identity.batchId); statement.setObject(6, identity.entryId)
        statement.setBytes(7, userDigest); statement.setObject(8, allocationId); statement.executeUpdate()
    }

    private fun assertCiphertextErased(identity: EntryIdentity) = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT revealed_at IS NOT NULL,code_ciphertext IS NULL,code_nonce IS NULL,
                wrapped_dek IS NULL,wrap_nonce IS NULL,kek_version IS NULL,revision
                FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=?""",
        ).use { statement ->
            statement.setString(1, identity.tenantId); statement.setObject(2, identity.entryId)
            statement.executeQuery().use { result ->
                assertErasedRow(result)
            }
        }
    }

    private fun assertCiphertextPresent(identity: EntryIdentity, expectedRevision: Long) =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT revealed_at IS NULL,code_ciphertext IS NOT NULL,code_nonce IS NOT NULL,
                    wrapped_dek IS NOT NULL,wrap_nonce IS NOT NULL,kek_version IS NOT NULL,revision
                    FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=?""",
            ).use { statement ->
                statement.setString(1, identity.tenantId); statement.setObject(2, identity.entryId)
                statement.executeQuery().use { result ->
                    assertErasedRow(result, expectedRevision)
                }
            }
        }

    private fun resolveQuarantine(identity: EntryIdentity) = dataSource.connection.use { connection ->
        connection.autoCommit = false
        connection.prepareStatement(
            "UPDATE voucher_pool_entries SET quarantined_at=NULL WHERE tenant_id=? AND entry_id=?",
        ).use { statement ->
            statement.setString(1, identity.tenantId); statement.setObject(2, identity.entryId); statement.executeUpdate()
        }
        connection.prepareStatement(
            """UPDATE voucher_pool_quarantines SET resolved_at=transaction_timestamp(),resolution='CLEARED'
                WHERE tenant_id=? AND entry_id=?""",
        ).use { statement ->
            statement.setString(1, identity.tenantId); statement.setObject(2, identity.entryId); statement.executeUpdate()
        }
        connection.commit()
    }

    private fun <T> committedTransaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            repository.withExistingConnection(connection) {
                block(connection)
            }.also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        }
    }

    private fun assertSqlFails(sql: String) {
        io.bluetape4k.assertions.assertFailsWith<java.sql.SQLException> {
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
        }
    }

    private fun assertErasedRow(result: ResultSet, expectedRevision: Long = 1) {
        result.next()
        (1..6).forEach { result.getBoolean(it) shouldBeEqualTo true }
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

    private fun adminConnection() = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username ?: PostgreSQLServer.USERNAME,
        postgres.password ?: PostgreSQLServer.PASSWORD,
    )

    companion object {
        private const val TENANT = "tenant-a"
        private val postgres = PostgreSQLServer.Launcher.postgres
    }
}

internal fun VoucherDigestService.Companion.testFixture(): VoucherDigestService = VoucherDigestService(
    stableDedupKey = DigestKey.of(1, ByteArray(32) { it.toByte() }),
    commandTombstoneKey = DigestKey.of(4, ByteArray(32) { (51 + it).toByte() }),
    rotatingKeys = ROTATING_TEST_PURPOSES.associateWith { purpose ->
        DigestKeyRing.of(DigestKey.of(purpose.ordinal + 1, ByteArray(32) { (purpose.ordinal * 17 + it).toByte() }))
    },
)

private val ROTATING_TEST_PURPOSES = setOf(
    DigestPurpose.VERIFICATION,
    DigestPurpose.USER_IDENTITY,
    DigestPurpose.REDIS_SIGNAL,
    DigestPurpose.AUDIT,
)
