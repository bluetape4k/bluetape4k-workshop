@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolIdempotencyIntegrationTest {
    private val schema = "voucher_idempotency_${Base58.randomString(8).lowercase()}"
    private val dataSource = PGSimpleDataSource().apply {
        setURL(postgres.jdbcUrl)
        user = postgres.username ?: PostgreSQLServer.USERNAME
        password = postgres.password ?: PostgreSQLServer.PASSWORD
        currentSchema = schema
    }
    private lateinit var database: Database
    private lateinit var repository: JdbcVoucherPoolIdempotencyRepository

    @BeforeAll
    fun migrate() {
        adminConnection().use { connection -> connection.createStatement().execute("CREATE SCHEMA $schema") }
        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            537005L,
        ).migrate()
        dataSource.connection.use { connection ->
            connection.createStatement().execute(
                "CREATE TABLE voucher_pool_test_effects(effect_id UUID PRIMARY KEY, marker VARCHAR(64) NOT NULL)",
            )
        }
        database = Database.connect(dataSource)
        repository = JdbcVoucherPoolIdempotencyRepository(digestService(), LEASE, COMMAND_TIMEOUT, RETENTION)
    }

    @AfterAll
    fun cleanup() {
        adminConnection().use { connection -> connection.createStatement().execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    @BeforeEach
    fun resetIdempotencyFixtures() {
        dataSource.connection.use { connection ->
            connection.createStatement().execute(
                "TRUNCATE voucher_pool_http_idempotency,voucher_pool_command_tombstones,voucher_pool_test_effects",
            )
        }
    }

    @Test
    fun `same key and fingerprint replays while a different fingerprint conflicts`() {
        val fixture = fixture("same-replay")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }
        val descriptor = descriptor(fixture.effectId)

        tx { repository.finalize(owner.owner, descriptor, EffectReference.effect(fixture.effectId)) }.shouldBeTrue()
        tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) } shouldBeEqualTo
            IdempotencyDecision.Replay(descriptor)
        tx {
            repository.acquire(
                fixture.scope,
                fixture.rawKey,
                VoucherPoolFingerprint.command("reserve", mapOf("campaign" to "different")),
            )
        } shouldBeEqualTo IdempotencyDecision.FingerprintConflict
    }

    @Test
    fun `active owner blocks duplicate execution and stale owner is taken over`() {
        val fixture = fixture("stale-takeover")
        val first = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }

        val active = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) }
        (active as IdempotencyDecision.InProgress).retryAfter.isNegative.shouldBeFalse()
        expireLease(first.owner)
        val second = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }

        (first.owner == second.owner).shouldBeFalse()
        assertFailsWith<IdempotencyOwnershipLostException> {
            tx {
                insertEffect(fixture.effectId, "stale-owner-must-rollback")
                repository.finalize(first.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId))
            }
        }
        effectCount(fixture.effectId) shouldBeEqualTo 0
        tx {
            repository.lockOwnerForExecution(second.owner)
            repository.finalize(second.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId))
        }.shouldBeTrue()
    }

    @Test
    fun `retryable release allows the same fingerprint to acquire a fresh owner`() {
        val fixture = fixture("retryable-release")
        val first = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }

        tx { repository.releaseRetryable(first.owner) }.shouldBeTrue()
        val second = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }

        (first.owner == second.owner).shouldBeFalse()
    }

    @Test
    fun `business marker descriptor and tombstone commit atomically`() {
        val fixture = fixture("same-transaction")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }

        tx {
            repository.lockOwnerForExecution(owner.owner)
            insertEffect(fixture.effectId, "committed")
            repository.finalize(owner.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId))
        }.shouldBeTrue()

        effectCount(fixture.effectId) shouldBeEqualTo 1
        tombstoneCount(owner.owner) shouldBeEqualTo 1
        tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) } shouldBeEqualTo
            IdempotencyDecision.Replay(descriptor(fixture.effectId))
    }

    @Test
    fun `commit loss rolls back business marker descriptor and tombstone together`() {
        val fixture = fixture("commit-loss")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }

        assertFailsWith<SimulatedCommitLoss> {
            tx {
                repository.lockOwnerForExecution(owner.owner)
                insertEffect(fixture.effectId, "must-rollback")
                repository.finalize(owner.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId))
                throw SimulatedCommitLoss()
            }
        }

        effectCount(fixture.effectId) shouldBeEqualTo 0
        tombstoneCount(owner.owner) shouldBeEqualTo 0
        descriptorCount(owner.owner) shouldBeEqualTo 0
        tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) }
            .let { it is IdempotencyDecision.InProgress }.shouldBeTrue()
    }

    @Test
    fun `elapsed deadline inside one business transaction rejects finalize and rolls back its marker`() {
        val shortRepository = JdbcVoucherPoolIdempotencyRepository(
            digestService(),
            lease = Duration.ofMillis(250),
            commandTimeout = Duration.ofMillis(400),
            descriptorRetention = Duration.ofSeconds(1),
        )
        val fixture = fixture("elapsed-deadline")
        val owner = tx {
            shortRepository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute
        }

        assertFailsWith<IdempotencyOwnershipLostException> {
            tx {
                shortRepository.lockOwnerForExecution(owner.owner)
                insertEffect(fixture.effectId, "deadline-must-rollback")
                currentConnection().createStatement().use { it.execute("SELECT pg_sleep(0.6)") }
                shortRepository.finalize(
                    owner.owner,
                    descriptor(fixture.effectId),
                    EffectReference.effect(fixture.effectId),
                )
            }
        }

        effectCount(fixture.effectId) shouldBeEqualTo 0
        tombstoneCount(owner.owner) shouldBeEqualTo 0
        descriptorCount(owner.owner) shouldBeEqualTo 0
    }

    @Test
    fun `descriptor purge retains a replay fence and existing effect reference`() {
        val fixture = fixture("purge-fence")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }
        tx { repository.finalize(owner.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId)) }
        expireDescriptor(owner.owner)

        tx { repository.purgeDescriptors(limit = 1) } shouldBeEqualTo 1
        tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) } shouldBeEqualTo
            IdempotencyDecision.Expired(fixture.effectId, null)
        tx {
            repository.acquire(
                fixture.scope,
                fixture.rawKey,
                VoucherPoolFingerprint.command("reserve", mapOf("campaign" to "changed")),
            )
        } shouldBeEqualTo IdempotencyDecision.FingerprintConflict
    }

    @Test
    fun `purge refuses descriptor deletion when a completed row has no durable tombstone`() {
        val fixture = fixture("missing-tombstone")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }
        completeWithoutTombstone(owner.owner, descriptorJson(fixture.effectId))

        tx { repository.purgeDescriptors(limit = 1) } shouldBeEqualTo 0
        descriptorCount(owner.owner) shouldBeEqualTo 1
    }

    @Test
    fun `command tombstones reject update and delete for tenant lifetime`() {
        val fixture = fixture("immutable-tombstone")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }
        tx { repository.finalize(owner.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId)) }

        assertFailsWith<SQLException> {
            mutateTombstone(owner.owner, "UPDATE voucher_pool_command_tombstones SET key_version=key_version+1")
        }
        assertFailsWith<SQLException> {
            mutateTombstone(owner.owner, "DELETE FROM voucher_pool_command_tombstones")
        }
        tombstoneCount(owner.owner) shouldBeEqualTo 1
    }

    @Test
    fun `tombstone key version mismatch fails closed during finalize lookup and purge`() {
        val fixture = fixture("wrong-key-version")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }
        insertTombstone(owner.owner, fixture.effectId, owner.owner.keyVersion + 1)

        val finalizeFailure = assertFailsWith<IdempotencyTombstoneKeyVersionException> {
            tx {
                repository.lockOwnerForExecution(owner.owner)
                insertEffect(fixture.effectId, "version-mismatch-must-rollback")
                repository.finalize(owner.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId))
            }
        }
        finalizeFailure.message.orEmpty() shouldNotContain owner.owner.keyVersion.toString()
        effectCount(fixture.effectId) shouldBeEqualTo 0

        completeWithoutTombstone(owner.owner, descriptorJson(fixture.effectId))
        assertFailsWith<IdempotencyTombstoneKeyVersionException> {
            tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) }
        }
        assertFailsWith<IdempotencyTombstoneKeyVersionException> {
            tx { repository.purgeDescriptors(limit = 1) }
        }
        descriptorCount(owner.owner) shouldBeEqualTo 1
    }

    @Test
    fun `stored descriptors require exact fields and JSON types`() {
        val effectId = UUID.randomUUID()
        val malformed = listOf(
            """{"status":"201","outcome":"RESERVED","effectId":"$effectId","revision":1}""",
            """{"status":201,"outcome":"RESERVED","effectId":"$effectId"}""",
            """{"status":409,"terminalCode":"POOL_EXHAUSTED","outcome":"FORBIDDEN"}""",
            """{"status":201,"outcome":"RESERVED","effectId":"$effectId","revision":1,"secret":"must-not-leak"}""",
        )

        malformed.forEachIndexed { index, json ->
            val fixture = fixture("malformed-descriptor-$index")
            val owner = tx {
                repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute
            }
            tx { repository.finalize(owner.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId)) }
            replaceDescriptor(owner.owner, json)

            val failure = assertFailsWith<IdempotencyDescriptorCorruptedException> {
                tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) }
            }
            failure.message.orEmpty() shouldNotContain "must-not-leak"
        }
    }

    @Test
    fun `purge skips a descriptor while its tombstone row is locked`() {
        val fixture = fixture("locked-tombstone")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }
        tx { repository.finalize(owner.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId)) }
        expireDescriptor(owner.owner)

        dataSource.connection.use { lockingConnection ->
            lockingConnection.autoCommit = false
            lockingConnection.prepareStatement(
                "SELECT 1 FROM voucher_pool_command_tombstones WHERE tenant_id=? AND operation=? AND scoped_key_digest=? FOR UPDATE",
            ).use { statement ->
                owner.owner.bindScope(statement)
                statement.executeQuery().use { result -> result.next().shouldBeTrue() }
            }
            tx { repository.purgeDescriptors(limit = 1) } shouldBeEqualTo 0
            lockingConnection.commit()
        }

        tx { repository.purgeDescriptors(limit = 1) } shouldBeEqualTo 1
    }

    @Test
    fun `concurrent first acquire grants exactly one owner`() {
        val fixture = fixture("concurrent-acquire")
        val barrier = CyclicBarrier(9)
        val decisions = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = (1..8).map {
                executor.submit<IdempotencyDecision> {
                    barrier.await(5, TimeUnit.SECONDS)
                    tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) }
                }
            }
            barrier.await(5, TimeUnit.SECONDS)
            futures.map { it.get(10, TimeUnit.SECONDS) }
        }

        decisions.count { it is IdempotencyDecision.Execute } shouldBeEqualTo 1
        decisions.count { it is IdempotencyDecision.InProgress } shouldBeEqualTo 7
    }

    @Test
    fun `concurrent purge and retry never grants a second execution`() {
        val fixture = fixture("concurrent-purge")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }
        tx { repository.finalize(owner.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId)) }
        expireDescriptor(owner.owner)
        val barrier = CyclicBarrier(3)

        val retry = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val purge = executor.submit<Int> {
                barrier.await(5, TimeUnit.SECONDS)
                tx { repository.purgeDescriptors(limit = 1) }
            }
            val acquire = executor.submit<IdempotencyDecision> {
                barrier.await(5, TimeUnit.SECONDS)
                tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) }
            }
            barrier.await(5, TimeUnit.SECONDS)
            val decision = acquire.get(10, TimeUnit.SECONDS)
            purge.get(10, TimeUnit.SECONDS)
            decision
        }

        (retry is IdempotencyDecision.Execute).shouldBeFalse()
        tx { repository.purgeDescriptors(limit = 1) }
        tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) } shouldBeEqualTo
            IdempotencyDecision.Expired(fixture.effectId, null)
    }

    @Test
    fun `terminal error replay fence retains only bounded code after descriptor purge`() {
        val fixture = fixture("terminal-error")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }
        val terminal = VoucherPoolErrorCode.POOL_EXHAUSTED
        tx {
            repository.finalize(
                owner.owner,
                SafeResponseDescriptor.terminal(status = 409, terminalCode = terminal),
                EffectReference.terminal(terminal),
            )
        }
        expireDescriptor(owner.owner)
        tx { repository.purgeDescriptors(limit = 1) }

        tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) } shouldBeEqualTo
            IdempotencyDecision.Expired(null, terminal)
    }

    @Test
    fun `storage excludes raw keys owner capabilities and arbitrary response bodies`() {
        val fixture = fixture("raw-storage", rawKey = "never-store-this-idempotency-key")
        val owner = tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) as IdempotencyDecision.Execute }
        tx { repository.finalize(owner.owner, descriptor(fixture.effectId), EffectReference.effect(fixture.effectId)) }

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT descriptor::text,encode(scoped_key_digest,'hex'),encode(owner_token_digest,'hex') FROM voucher_pool_http_idempotency WHERE tenant_id=? AND operation=?",
            ).use { statement ->
                statement.setString(1, fixture.scope.tenantId)
                statement.setString(2, fixture.scope.operation)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getString(1) shouldNotContain fixture.rawKey
                    result.getString(2) shouldNotContain fixture.rawKey
                    result.getString(3).shouldBeNull()
                }
            }
        }
        owner.owner.toString() shouldNotContain "never-store"
    }

    @Test
    fun `raw idempotency key rejects control characters before persistence`() {
        val fixture = fixture("invalid-key", rawKey = "request-key\ninvalid")

        assertFailsWith<IllegalArgumentException> {
            tx { repository.acquire(fixture.scope, fixture.rawKey, fixture.fingerprint) }
        }
        idempotencyCount() shouldBeEqualTo 0
    }

    @Test
    fun `safe descriptor rejects retryable errors and invalid success statuses`() {
        assertFailsWith<IllegalArgumentException> {
            SafeResponseDescriptor.terminal(status = 503, terminalCode = VoucherPoolErrorCode.POOL_BUSY)
        }
        assertFailsWith<IllegalArgumentException> {
            SafeResponseDescriptor.success(
                status = 500,
                outcome = "RESERVED",
                effectId = UUID.randomUUID(),
                revision = 1,
            )
        }
    }

    private fun fixture(suffix: String, rawKey: String = "request-key-$suffix") = Fixture(
        scope = CommandScope("tenant-$suffix", "reserve"),
        rawKey = rawKey,
        fingerprint = VoucherPoolFingerprint.command("reserve", mapOf("campaign" to suffix)),
        effectId = UUID.randomUUID(),
    )

    private fun descriptor(effectId: UUID): SafeResponseDescriptor =
        SafeResponseDescriptor.success(status = 201, outcome = "RESERVED", effectId = effectId, revision = 1)

    private fun expireLease(owner: IdempotencyOwner) = updateOwnerRow(
        owner,
        "UPDATE voucher_pool_http_idempotency SET lease_until=transaction_timestamp()-interval '1 second' WHERE tenant_id=? AND operation=? AND scoped_key_digest=?",
    )

    private fun expireDescriptor(owner: IdempotencyOwner) = updateOwnerRow(
        owner,
        "UPDATE voucher_pool_http_idempotency SET expires_at=transaction_timestamp()-interval '1 second' WHERE tenant_id=? AND operation=? AND scoped_key_digest=?",
    )

    private fun completeWithoutTombstone(owner: IdempotencyOwner, descriptorJson: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE voucher_pool_http_idempotency
                    SET status='COMPLETED',owner_token_digest=NULL,lease_until=NULL,descriptor=?::jsonb,
                        expires_at=statement_timestamp()-interval '1 second'
                    WHERE tenant_id=? AND operation=? AND scoped_key_digest=?""",
            ).use { statement ->
                statement.setString(1, descriptorJson)
                owner.bindScope(statement, start = 2)
                statement.executeUpdate() shouldBeEqualTo 1
            }
        }
    }

    private fun mutateTombstone(owner: IdempotencyOwner, mutation: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "$mutation WHERE tenant_id=? AND operation=? AND scoped_key_digest=?",
            ).use { statement ->
                owner.bindScope(statement)
                statement.executeUpdate()
            }
        }
    }

    private fun insertTombstone(owner: IdempotencyOwner, effectId: UUID, keyVersion: Int) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO voucher_pool_command_tombstones
                    (tenant_id,operation,key_version,scoped_key_digest,fingerprint,effect_id,terminal_code)
                    VALUES (?,?,?,?,?,?,NULL)""",
            ).use { statement ->
                statement.setString(1, owner.scope.tenantId)
                statement.setString(2, owner.scope.operation)
                statement.setInt(3, keyVersion)
                statement.setBytes(4, owner.copyScopedKeyDigest())
                statement.setBytes(5, owner.fingerprint.copyBytes())
                statement.setObject(6, effectId)
                statement.executeUpdate() shouldBeEqualTo 1
            }
        }
    }

    private fun replaceDescriptor(owner: IdempotencyOwner, descriptorJson: String) {
        updateOwnerRow(
            owner,
            "UPDATE voucher_pool_http_idempotency SET descriptor=?::jsonb WHERE tenant_id=? AND operation=? AND scoped_key_digest=?",
            scopeStart = 2,
        ) { statement -> statement.setString(1, descriptorJson) }
    }

    private fun descriptorJson(effectId: UUID): String =
        """{"status":201,"outcome":"RESERVED","effectId":"$effectId","revision":1}"""

    private fun updateOwnerRow(
        owner: IdempotencyOwner,
        sql: String,
        scopeStart: Int = 1,
        bindPrefix: (java.sql.PreparedStatement) -> Unit = {},
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bindPrefix(statement)
                owner.bindScope(statement, start = scopeStart)
                statement.executeUpdate() shouldBeEqualTo 1
            }
        }
    }

    private fun IdempotencyOwner.bindScope(statement: java.sql.PreparedStatement, start: Int = 1) {
        statement.setString(start, scope.tenantId)
        statement.setString(start + 1, scope.operation)
        statement.setBytes(start + 2, copyScopedKeyDigest())
    }

    private fun insertEffect(effectId: UUID, marker: String) {
        val connection = currentConnection()
        connection.prepareStatement("INSERT INTO voucher_pool_test_effects(effect_id,marker) VALUES (?,?)").use {
            it.setObject(1, effectId)
            it.setString(2, marker)
            it.executeUpdate()
        }
    }

    private fun currentConnection(): Connection = TransactionManager.current().connection.connection as Connection

    private fun effectCount(effectId: UUID): Int = scalarCount(
        "SELECT count(*) FROM voucher_pool_test_effects WHERE effect_id=?",
    ) { it.setObject(1, effectId) }

    private fun tombstoneCount(owner: IdempotencyOwner): Int = scalarCount(
        "SELECT count(*) FROM voucher_pool_command_tombstones WHERE tenant_id=? AND operation=? AND scoped_key_digest=?",
    ) { statement -> owner.bindScope(statement) }

    private fun descriptorCount(owner: IdempotencyOwner): Int = scalarCount(
        "SELECT count(*) FROM voucher_pool_http_idempotency WHERE tenant_id=? AND operation=? AND scoped_key_digest=? AND descriptor IS NOT NULL",
    ) { statement -> owner.bindScope(statement) }

    private fun idempotencyCount(): Int = scalarCount(
        "SELECT count(*) FROM voucher_pool_http_idempotency",
    ) { }

    private fun scalarCount(sql: String, bind: (java.sql.PreparedStatement) -> Unit): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
        }

    private fun <T> tx(block: () -> T): T = transaction(database) { block() }

    private fun digestService(): VoucherDigestService = VoucherDigestService(
        stableDedupKey = DigestKey.of(7, keyBytes(7)),
        commandTombstoneKey = DigestKey.of(4, keyBytes(4)),
        rotatingKeys = mapOf(
            DigestPurpose.VERIFICATION to DigestKeyRing.of(DigestKey.of(1, keyBytes(1))),
            DigestPurpose.USER_IDENTITY to DigestKeyRing.of(DigestKey.of(2, keyBytes(2))),
            DigestPurpose.REDIS_SIGNAL to DigestKeyRing.of(DigestKey.of(3, keyBytes(3))),
            DigestPurpose.AUDIT to DigestKeyRing.of(DigestKey.of(5, keyBytes(5))),
        ),
    )

    private fun keyBytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    private fun adminConnection() = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username ?: PostgreSQLServer.USERNAME,
        postgres.password ?: PostgreSQLServer.PASSWORD,
    )

    private data class Fixture(
        val scope: CommandScope,
        val rawKey: String,
        val fingerprint: CommandFingerprint,
        val effectId: UUID,
    )

    private class SimulatedCommitLoss : RuntimeException()

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres
        private val LEASE = Duration.ofSeconds(30)
        private val COMMAND_TIMEOUT = Duration.ofMinutes(1)
        private val RETENTION = Duration.ofHours(24)
    }
}
