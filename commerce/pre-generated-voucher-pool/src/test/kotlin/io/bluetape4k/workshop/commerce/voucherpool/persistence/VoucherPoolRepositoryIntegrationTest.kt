@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.SQLException
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import tools.jackson.databind.JsonNode

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolRepositoryIntegrationTest {
    private val dataSource = PGSimpleDataSource().apply {
        setURL(postgres.jdbcUrl)
        user = postgres.username ?: PostgreSQLServer.USERNAME
        password = postgres.password ?: PostgreSQLServer.PASSWORD
        currentSchema = SCHEMA
    }
    private lateinit var repository: JdbcVoucherPoolRepository

    @BeforeAll
    fun migrate() {
        adminConnection().use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE") }
            connection.createStatement().use { it.execute("CREATE SCHEMA $SCHEMA") }
        }
        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            537002L,
        ).migrate()
        repository = JdbcVoucherPoolRepository(dataSource)
    }

    @Test
    fun `stable digest is unique across campaigns`() {
        val digest = ByteArray(32) { 7 }
        insertDedup(TENANT, digest, 1)
        assertFailsWith<SQLException> {
            insertDedup(TENANT, digest, 1)
        }
    }

    @Test
    fun `foreground campaign guards are compatible shared locks with no exclusive waiter`() {
        val campaign = createCampaign(TENANT)
        val acquired = CyclicBarrier(3)
        val release = CyclicBarrier(3)
        val backendPids = ConcurrentLinkedQueue<Int>()
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val holders = (1..2).map {
                executor.submit {
                    dataSource.connection.use { connection ->
                        connection.autoCommit = false
                        backendPids += backendPid(connection)
                        repository.lockCampaignForShare(connection, TENANT, campaign)
                        acquired.await(2, TimeUnit.SECONDS)
                        release.await(2, TimeUnit.SECONDS)
                        connection.commit()
                    }
                }
            }
            acquired.await(2, TimeUnit.SECONDS)
            repository.sharedLockHolders(backendPids.toSet()) shouldBeEqualTo 2
            repository.exclusiveWaiters(backendPids.toSet()) shouldBeEqualTo 0
            release.await(2, TimeUnit.SECONDS)
            holders.forEach { it.get(2, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `exclusive policy update waits for shared foreground guard then advances revision`() {
        val campaign = createCampaign(TENANT)
        dataSource.connection.use { reader ->
            reader.autoCommit = false
            repository.lockCampaignForShare(reader, TENANT, campaign)
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val update = executor.submit<Int> { updateCampaignPolicy(TENANT, campaign) }
                Thread.sleep(100)
                update.isDone shouldBeEqualTo false
                reader.commit()
                update.get(2, TimeUnit.SECONDS) shouldBeEqualTo 1
            }
        }
    }

    @Test
    fun `skip locked allocation progresses past a held candidate`() {
        val campaign = createCampaign(TENANT)
        val batch = createBatch(TENANT, campaign)
        val first = createAvailableEntry(TENANT, campaign, batch, 1)
        val second = createAvailableEntry(TENANT, campaign, batch, 2)
        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            repository.lockEntry(blocker, TENANT, first)
            dataSource.connection.use { contender ->
                contender.autoCommit = false
                repository.selectAvailableEntrySkipLocked(contender, TENANT, campaign)?.entryId shouldBeEqualTo second
                contender.rollback()
            }
            blocker.rollback()
        }
    }

    @Test
    fun `allocation locks policy guards first and chooses the earliest active batch`() {
        val campaign = createCampaign(TENANT)
        val laterBatch = createBatch(
            TENANT,
            campaign,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.parse("2026-01-02T00:00:00Z"),
        )
        val earlierBatch = createBatch(
            TENANT,
            campaign,
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
            Instant.parse("2026-01-01T00:00:00Z"),
        )
        createAvailableEntry(TENANT, campaign, laterBatch, 1)
        val expected = createAvailableEntry(TENANT, campaign, earlierBatch, 99)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            repository.selectAvailableEntrySkipLocked(connection, TENANT, campaign)?.entryId shouldBeEqualTo expected
            connection.rollback()
        }
    }

    @Test
    fun `physical tenant foreign keys counters revisions nonce and entry contracts reject corruption`() {
        val campaign = createCampaign(TENANT)
        val batch = createBatch(TENANT, campaign)
        val entry = createAvailableEntry(TENANT, campaign, batch, 10)
        assertSqlFails("UPDATE voucher_pool_campaigns SET revision=-1 WHERE tenant_id='$TENANT' AND campaign_id='$campaign'")
        assertSqlFails("UPDATE voucher_pool_entries SET code_nonce=NULL WHERE tenant_id='$TENANT' AND entry_id='$entry'")
        assertSqlFails("INSERT INTO voucher_pool_user_limits(tenant_id,campaign_id,user_digest,active_reservations) VALUES ('$TENANT','$campaign',decode('01','hex'),-1)")
        assertSqlFails(
            """INSERT INTO voucher_pool_batches
                (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                 policy_version,activates_at,expected_count)
                VALUES ('other','$entry','$campaign','ACTIVE','GENERATED',decode('01','hex'),
                        decode('02','hex'),1,now(),0)""",
        )
        assertSqlFails(
            """INSERT INTO voucher_pool_entries
                (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                 verification_digest,verification_key_version,code_ciphertext,wrapped_dek,code_nonce,
                 wrap_nonce,kek_version)
                VALUES ('$TENANT',gen_random_uuid(),'$campaign','$batch',11,'AVAILABLE',decode(md5(random()::text),'hex'),
                        decode(md5(random()::text),'hex'),1,decode('01','hex'),decode('02','hex'),
                        (SELECT code_nonce FROM voucher_pool_entries WHERE tenant_id='$TENANT' AND entry_id='$entry'),
                        decode(substr(md5(random()::text),1,24),'hex'),'test-kek')""",
        )
    }

    @Test
    fun `campaign batch and entry schemas retain the complete operating contract`() {
        requiredColumns("voucher_pool_campaigns") shouldBeEqualTo setOf(
            "tenant_id", "campaign_id", "state", "starts_at", "ends_at", "per_user_limit",
            "reservation_ttl_seconds", "allocation_ttl_seconds", "replacement_allowance", "policy_version",
            "revision", "created_at", "updated_at",
        )
        requiredColumns("voucher_pool_batches") shouldBeEqualTo setOf(
            "tenant_id", "batch_id", "campaign_id", "state", "source_kind", "provenance_digest",
            "request_fingerprint", "policy_version", "activates_at", "expires_at", "next_source_ordinal",
            "expected_count", "accepted_count", "rejected_count", "checkpoint_digest", "last_failure_code",
            "revision", "created_at", "updated_at",
        )
        requiredColumns("voucher_pool_entries") shouldBeEqualTo setOf(
            "tenant_id", "entry_id", "campaign_id", "batch_id", "source_ordinal", "state",
            "stable_dedup_digest", "verification_digest", "verification_key_version", "code_ciphertext",
            "code_nonce", "wrapped_dek", "wrap_nonce", "kek_version", "reservation_id", "allocation_id",
            "user_digest", "reserved_at", "reservation_expires_at", "allocated_at", "allocation_expires_at",
            "revealed_at", "redeemed_at", "allocation_policy_version", "terminal_reason", "entitlement_root_id",
            "replacement_count", "quarantined_at", "revision", "created_at", "updated_at",
        )
    }

    @Test
    fun `audit rows are physically append only`() {
        val campaign = createCampaign(TENANT)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            repository.appendAudit(
                connection,
                VoucherPoolAuditRecord(TENANT, campaign, "CAMPAIGN", campaign, 0, 1, "SYSTEM", "CREATED"),
            )
            connection.commit()
        }
        assertSqlFails("UPDATE voucher_pool_audits SET reason_code='CHANGED' WHERE campaign_id='$campaign'")
        assertSqlFails("DELETE FROM voucher_pool_audits WHERE campaign_id='$campaign'")
    }

    @Test
    fun `reservation history permits terminal reuse but one active owner and nonce domains stay unique`() {
        val campaign = createCampaign(TENANT)
        val batch = createBatch(TENANT, campaign)
        val entry = createAvailableEntry(TENANT, campaign, batch, 20)
        val first = UUID.randomUUID()
        executeSql(reservationInsert(first, campaign, batch, entry, "RELEASED"))
        executeSql(reservationInsert(UUID.randomUUID(), campaign, batch, entry, "EXPIRED"))
        executeSql(reservationInsert(UUID.randomUUID(), campaign, batch, entry, "ACTIVE"))
        assertSqlFails(reservationInsert(UUID.randomUUID(), campaign, batch, entry, "ACTIVE"))
        assertSqlFails(
            """INSERT INTO voucher_pool_entries
                (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                 verification_digest,verification_key_version,code_ciphertext,code_nonce,wrapped_dek,
                 wrap_nonce,kek_version,revision)
                SELECT tenant_id,md5(random()::text)::uuid,campaign_id,batch_id,21,state,
                       decode(md5(random()::text),'hex'),decode(md5(random()::text),'hex'),verification_key_version,
                       code_ciphertext,code_nonce,wrapped_dek,decode(substr(md5(random()::text),1,24),'hex'),
                       kek_version,revision
                FROM voucher_pool_entries WHERE tenant_id='$TENANT' AND entry_id='$entry'""",
        )
    }

    @Test
    fun `replacement allocation requires one original entitlement and cannot consume a second replacement`() {
        val campaign = createCampaign(TENANT)
        val batch = createBatch(TENANT, campaign)
        val originalEntry = createAvailableEntry(TENANT, campaign, batch, 22)
        val replacementEntry = createAvailableEntry(TENANT, campaign, batch, 23)
        val rejectedEntry = createAvailableEntry(TENANT, campaign, batch, 24)
        val originalReservation = UUID.randomUUID()
        val replacementReservation = UUID.randomUUID()
        val rejectedReservation = UUID.randomUUID()
        val entitlement = UUID.randomUUID()
        val allocationScope = AllocationScope(campaign, batch, entitlement)
        executeSql(reservationInsert(originalReservation, campaign, batch, originalEntry, "ALLOCATED"))
        executeSql(reservationInsert(replacementReservation, campaign, batch, replacementEntry, "ALLOCATED"))
        executeSql(reservationInsert(rejectedReservation, campaign, batch, rejectedEntry, "ALLOCATED"))
        assertSqlFails(allocationInsert(replacementReservation, replacementEntry, allocationScope, 1))
        executeSql(allocationInsert(originalReservation, originalEntry, allocationScope, 0))
        executeSql(allocationInsert(replacementReservation, replacementEntry, allocationScope, 1))
        assertSqlFails(allocationInsert(rejectedReservation, rejectedEntry, allocationScope, 1))
    }

    @Test
    fun `durable lifecycle checks reject half owned claims and invalid state context`() {
        assertSqlFails(
            """INSERT INTO voucher_pool_worker_claims
                (tenant_id,worker_type,scope_id,owner_id,claim_until,attempt,next_attempt_at,checkpoint)
                VALUES ('$TENANT','RESERVATION_EXPIRY',gen_random_uuid(),'owner',NULL,0,now(),0)""",
        )
        assertSqlFails(
            """INSERT INTO voucher_pool_reconciliation_inbox
                (tenant_id,event_id,payload_digest,status,attempt,next_attempt_at,claim_owner)
                VALUES ('$TENANT',gen_random_uuid(),decode('01','hex'),'CLAIMED',0,now(),'owner')""",
        )
    }

    @Test
    fun `digest values defensively copy ingress and egress bytes`() {
        val source = byteArrayOf(1, 2, 3)
        val digest = DigestValue.of(source)
        source[0] = 9
        digest.copyBytes().toList() shouldBeEqualTo listOf<Byte>(1, 2, 3)
        val exposed = digest.copyBytes()
        exposed[1] = 9
        digest shouldBeEqualTo DigestValue.of(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `worker canonical chain exclusively relocks in order and rejects stale revision`() {
        val campaign = createCampaign(TENANT)
        val batch = createBatch(TENANT, campaign)
        val entry = createAvailableEntry(TENANT, campaign, batch, 30)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            repository.lockCanonicalChain(
                connection,
                WorkerCandidate(TENANT, campaign, batch, entry, 0, 0, 0),
            )?.entry?.entryId shouldBeEqualTo entry
            connection.rollback()
        }
        executeSql("UPDATE voucher_pool_entries SET revision=1 WHERE tenant_id='$TENANT' AND entry_id='$entry'")
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            repository.lockCanonicalChain(
                connection,
                WorkerCandidate(TENANT, campaign, batch, entry, 0, 0, 0),
            ) shouldBeEqualTo null
            connection.rollback()
        }
    }

    @Test
    fun `worker canonical chain locks sorted user limits and reservations before the entry`() {
        val campaign = createCampaign(TENANT)
        val batch = createBatch(TENANT, campaign)
        val entry = createAvailableEntry(TENANT, campaign, batch, 31)
        val firstUser = DigestValue.of(byteArrayOf(1))
        val secondUser = DigestValue.of(byteArrayOf(2))
        val firstReservation = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondReservation = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        executeSql(userLimitInsert(campaign, firstUser))
        executeSql(userLimitInsert(campaign, secondUser))
        executeSql(reservationInsert(firstReservation, campaign, batch, entry, "RELEASED"))
        executeSql(reservationInsert(secondReservation, campaign, batch, entry, "EXPIRED"))
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val chain = repository.lockCanonicalChain(
                connection,
                WorkerCandidate(
                    TENANT,
                    campaign,
                    batch,
                    entry,
                    0,
                    0,
                    0,
                    userLimits = listOf(ExpectedUserLimit(secondUser, 0), ExpectedUserLimit(firstUser, 0)),
                    reservations = listOf(
                        ExpectedReservation(secondReservation, 0),
                        ExpectedReservation(firstReservation, 0),
                    ),
                ),
            )
            chain?.userLimits?.map { it.userDigest } shouldBeEqualTo listOf(firstUser, secondUser)
            chain?.reservations?.map { it.reservationId } shouldBeEqualTo listOf(firstReservation, secondReservation)
            connection.rollback()
        }
    }

    @Test
    fun `production worker and pool depth queries return bounded authority rows`() {
        val campaign = createCampaign(TENANT)
        val batch = createBatch(TENANT, campaign)
        createAvailableEntry(TENANT, campaign, batch, 40)
        executeSql("INSERT INTO voucher_pool_pool_depth(tenant_id,batch_id,state,entry_count) VALUES ('$TENANT','$batch','AVAILABLE',1)")
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            repository.selectWorkerCandidates(connection, TENANT, batch, 100).size shouldBeEqualTo 1
            connection.rollback()
        }
        repository.poolDepth(TENANT, batch)[EntryState.AVAILABLE] shouldBeEqualTo 1L
    }

    @Test
    fun `Exposed audit append participates in the caller transaction`() {
        val campaign = createCampaign(TENANT)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            repository.appendAudit(
                connection,
                VoucherPoolAuditRecord(TENANT, campaign, "CAMPAIGN", campaign, 0, 1, "SYSTEM", "CREATED"),
            )
            queryLong(connection, "SELECT count(*) FROM voucher_pool_audits WHERE campaign_id='$campaign'") shouldBeEqualTo 1L
            connection.rollback()
        }
        queryLong("SELECT count(*) FROM voucher_pool_audits WHERE campaign_id='$campaign'") shouldBeEqualTo 0L
    }

    @Test
    fun `representative query plans stay indexed and bounded`() {
        val campaign = createCampaign(TENANT)
        val batch = createBatch(TENANT, campaign)
        loadRepresentativePlanFixture(campaign, batch)
        val plans = mapOf(
            "allocation-candidate" to explain(JdbcVoucherPoolRepository.ALLOCATION_CANDIDATE_SQL) {
                it.setString(1, TENANT); it.setObject(2, batch)
            },
            "worker-candidate" to dataSource.connection.use { connection ->
                explain(repository.workerCandidatePlanSql(connection, TENANT, batch, 100)) {}
            },
            "pool-depth" to dataSource.connection.use { connection ->
                explain(repository.poolDepthPlanSql(connection, TENANT, batch)) {}
            },
        )
        val directory = Path.of("build/reports/voucher-pool/query-plans")
        Files.createDirectories(directory)
        val ceilings = mapOf(
            "allocation-candidate" to Triple(1L, 4L, 128L),
            "worker-candidate" to Triple(100L, 200L, 512L),
            "pool-depth" to Triple(4L, 64L, 1_024L),
        )
        val allowedNodeSets = mapOf(
            "allocation-candidate" to setOf(setOf("Limit", "LockRows", "Index Scan")),
            "worker-candidate" to setOf(setOf("Limit", "Index Scan")),
            "pool-depth" to setOf(
                setOf("Aggregate", "Index Only Scan"),
                setOf("Aggregate", "Bitmap Heap Scan", "Bitmap Index Scan"),
            ),
        )
        plans.forEach { (name, plan) ->
            Files.writeString(directory.resolve("$name.json"), plan)
            val root = Jackson.defaultJsonMapper.readTree(plan).path(0).path("Plan")
            val nodes = planNodes(root)
            val nodeTypes = nodes.map { it.path("Node Type").stringValue() }.toSet()
            require(nodeTypes in checkNotNull(allowedNodeSets[name])) { "$name used an unapproved plan tree: $nodeTypes" }
            val scans = nodes.filter { it.path("Node Type").stringValue().contains("Scan") }
            require(scans.isNotEmpty()) { "$name omitted its bounded scan" }
            val (rowCeiling, heapCeiling, blockCeiling) = checkNotNull(ceilings[name])
            requiredMetric(root, "Actual Rows") shouldBeAtMost rowCeiling
            scans.sumOf { optionalHeapFetches(it) } shouldBeAtMost heapCeiling
            scans.sumOf { requiredMetric(it, "Shared Hit Blocks") + requiredMetric(it, "Shared Read Blocks") } shouldBeAtMost blockCeiling
        }
    }

    private fun loadRepresentativePlanFixture(campaign: UUID, batch: UUID) {
        val noiseCampaign = createCampaign("plan-noise")
        dataSource.connection.use { connection ->
            connection.createStatement().execute(
                """INSERT INTO voucher_pool_entries
                    (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                     verification_digest,verification_key_version,code_ciphertext,code_nonce,wrapped_dek,
                     wrap_nonce,kek_version,revision)
                    SELECT '$TENANT',md5('entry-'||n)::uuid,'$campaign','$batch',n,'AVAILABLE',
                           decode(md5('stable-'||n),'hex'),decode(md5('verify-'||n),'hex'),1,decode('01','hex'),
                           decode(substr(md5('code-'||n),1,24),'hex'),decode('02','hex'),
                           decode(substr(md5('wrap-'||n),1,24),'hex'),'test-kek',0
                    FROM generate_series(1,10000) n""",
            )
            connection.createStatement().execute("ANALYZE voucher_pool_entries")
            connection.createStatement().execute(
                "INSERT INTO voucher_pool_pool_depth(tenant_id,batch_id,state,entry_count) VALUES ('$TENANT','$batch','AVAILABLE',10000)",
            )
            connection.createStatement().execute(
                """INSERT INTO voucher_pool_batches
                    (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                     policy_version,activates_at,expected_count,revision)
                    SELECT 'plan-noise',md5('batch-'||n)::uuid,'$noiseCampaign','ACTIVE','GENERATED',
                           decode(md5('provenance-'||n),'hex'),decode(md5('fingerprint-'||n),'hex'),
                           1,transaction_timestamp(),0,0
                    FROM generate_series(1,1000) n""",
            )
            connection.createStatement().execute(
                """INSERT INTO voucher_pool_pool_depth(tenant_id,batch_id,state,entry_count)
                    SELECT tenant_id,batch_id,'AVAILABLE',0 FROM voucher_pool_batches
                    WHERE tenant_id='plan-noise' AND campaign_id='$noiseCampaign'""",
            )
            connection.createStatement().execute("ANALYZE voucher_pool_pool_depth")
        }
    }

    private fun assertSqlFails(sql: String) {
        assertFailsWith<SQLException> { dataSource.connection.use { it.createStatement().execute(sql) } }
    }

    private fun executeSql(sql: String) {
        dataSource.connection.use { it.createStatement().execute(sql) }
    }

    private fun createCampaign(tenantId: String): UUID = UUID.randomUUID().also { id ->
        executeSql(
            "INSERT INTO voucher_pool_campaigns(tenant_id,campaign_id,state,policy_version,revision) " +
                "VALUES ('$tenantId','$id','ACTIVE',1,0)",
        )
    }

    private fun createBatch(tenantId: String, campaignId: UUID): UUID =
        createBatch(tenantId, campaignId, UUID.randomUUID(), Instant.now().minusSeconds(60))

    private fun createBatch(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        activatesAt: Instant,
    ): UUID = batchId.also { id ->
        executeSql(
            """INSERT INTO voucher_pool_batches
                (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                 policy_version,activates_at,expected_count,revision)
                VALUES ('$tenantId','$id','$campaignId','ACTIVE','GENERATED',decode('01','hex'),
                        decode('02','hex'),1,'$activatesAt',0,0)""",
        )
    }

    private fun createAvailableEntry(tenantId: String, campaignId: UUID, batchId: UUID, ordinal: Long): UUID =
        UUID.randomUUID().also { id ->
            executeSql(
                """INSERT INTO voucher_pool_entries
                    (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                     verification_digest,verification_key_version,code_ciphertext,code_nonce,wrapped_dek,
                     wrap_nonce,kek_version,revision) VALUES ('$tenantId','$id','$campaignId','$batchId',$ordinal,
                    'AVAILABLE',decode(md5('$id-stable'),'hex'),decode(md5('$id-verification'),'hex'),1,
                    decode('01','hex'),decode(substr(md5('$id-code'),1,24),'hex'),decode('02','hex'),
                    decode(substr(md5('$id-wrap'),1,24),'hex'),'test-kek',0)""",
            )
        }

    private fun insertDedup(
        tenantId: String,
        digest: ByteArray,
        keyVersion: Int,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO voucher_pool_code_dedup
                    (tenant_id,stable_dedup_digest,first_campaign_id,first_batch_id,first_entry_id,key_version)
                    VALUES (?,?,?,?,?,?)""",
            ).use {
                it.setString(1, tenantId); it.setBytes(2, digest); it.setObject(3, UUID.randomUUID())
                it.setObject(4, UUID.randomUUID()); it.setObject(5, UUID.randomUUID())
                it.setInt(6, keyVersion); it.executeUpdate()
            }
        }
    }

    private fun updateCampaignPolicy(tenantId: String, campaignId: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """UPDATE voucher_pool_campaigns SET policy_version=policy_version+1,revision=revision+1
                WHERE tenant_id=? AND campaign_id=?""",
        ).use { statement ->
            statement.setString(1, tenantId); statement.setObject(2, campaignId); statement.executeUpdate()
        }
    }

    private fun backendPid(connection: java.sql.Connection): Int =
        connection.createStatement().executeQuery("SELECT pg_backend_pid()").use { it.next(); it.getInt(1) }

    private fun queryLong(connection: java.sql.Connection, sql: String): Long =
        connection.createStatement().executeQuery(sql).use { it.next(); it.getLong(1) }

    private fun queryLong(sql: String): Long = dataSource.connection.use { queryLong(it, sql) }

    private fun reservationInsert(
        reservationId: UUID,
        campaignId: UUID,
        batchId: UUID,
        entryId: UUID,
        state: String,
    ): String =
        """INSERT INTO voucher_pool_reservations
            (tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest,idempotency_owner_digest,
             state,reservation_expires_at,policy_version,revision)
            VALUES ('$TENANT','$reservationId','$campaignId','$batchId','$entryId',decode('01','hex'),
                    decode('02','hex'),'$state',now()+interval '1 hour',1,0)"""

    private fun allocationInsert(
        reservationId: UUID,
        entryId: UUID,
        scope: AllocationScope,
        replacementOrdinal: Int,
    ): String =
        """INSERT INTO voucher_pool_allocations
            (tenant_id,allocation_id,reservation_id,campaign_id,batch_id,entry_id,user_digest,
             entitlement_root_id,replacement_ordinal,allocation_expires_at,policy_version,revision)
            VALUES ('$TENANT',gen_random_uuid(),'$reservationId','${scope.campaignId}','${scope.batchId}','$entryId',
                    decode('01','hex'),'${scope.entitlementRootId}',$replacementOrdinal,now()+interval '1 hour',1,0)"""

    private fun userLimitInsert(campaignId: UUID, userDigest: DigestValue): String =
        """INSERT INTO voucher_pool_user_limits
            (tenant_id,campaign_id,user_digest,active_reservations,active_allocations,lifetime_consumed,revision)
            VALUES ('$TENANT','$campaignId',decode('${userDigest.copyBytes().toHexString()}','hex'),0,0,0,0)"""

    private fun requiredColumns(tableName: String): Set<String> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT column_name FROM information_schema.columns
                WHERE table_schema=? AND table_name=? ORDER BY ordinal_position""",
        ).use { statement ->
            statement.setString(1, SCHEMA)
            statement.setString(2, tableName)
            statement.executeQuery().use { result -> buildSet { while (result.next()) add(result.getString(1)) } }
        }
    }

    private fun explain(sql: String, bind: (java.sql.PreparedStatement) -> Unit): String =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) $sql").use { statement ->
                bind(statement)
                statement.executeQuery().use { result -> result.next(); result.getString(1) }
            }.also { connection.rollback() }
        }

    private infix fun Long.shouldBeAtMost(ceiling: Long) {
        require(this <= ceiling) { "plan metric $this exceeded ceiling $ceiling" }
    }

    private fun planNodes(root: JsonNode): List<JsonNode> = buildList {
        add(root)
        root.path("Plans").forEach { addAll(planNodes(it)) }
    }

    private fun requiredMetric(node: JsonNode, name: String): Long {
        require(node.has(name)) { "plan node ${node.path("Node Type").stringValue()} omitted $name" }
        return node.path(name).longValue()
    }

    private fun optionalHeapFetches(node: JsonNode): Long =
        if (node.has("Heap Fetches")) node.path("Heap Fetches").longValue()
        else {
            require(node.path("Node Type").stringValue() != "Index Only Scan") { "Index Only Scan omitted Heap Fetches" }
            0L
        }

    private fun adminConnection() = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username ?: PostgreSQLServer.USERNAME,
        postgres.password ?: PostgreSQLServer.PASSWORD,
    )

    companion object {
        private const val SCHEMA = "voucher_pool_task3"
        private const val TENANT = "tenant-a"
        private val postgres = PostgreSQLServer.Launcher.postgres
    }

    private data class AllocationScope(
        val campaignId: UUID,
        val batchId: UUID,
        val entitlementRootId: UUID,
    )
}
