@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
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
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        repository.insertDedup(TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), digest, 1)
        assertFailsWith<SQLException> {
            repository.insertDedup(TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), digest, 1)
        }
    }

    @Test
    fun `foreground campaign guards are compatible shared locks with no exclusive waiter`() {
        val campaign = repository.createCampaign(TENANT)
        val acquired = CyclicBarrier(3)
        val release = CyclicBarrier(3)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val holders = (1..2).map {
                executor.submit {
                    dataSource.connection.use { connection ->
                        connection.autoCommit = false
                        repository.lockCampaignForShare(connection, TENANT, campaign)
                        acquired.await(2, TimeUnit.SECONDS)
                        release.await(2, TimeUnit.SECONDS)
                        connection.commit()
                    }
                }
            }
            acquired.await(2, TimeUnit.SECONDS)
            repository.sharedLockHolders() shouldBeEqualTo 2
            repository.exclusiveWaiters(TENANT, campaign) shouldBeEqualTo 0
            release.await(2, TimeUnit.SECONDS)
            holders.forEach { it.get(2, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `exclusive policy update waits for shared foreground guard then advances revision`() {
        val campaign = repository.createCampaign(TENANT)
        dataSource.connection.use { reader ->
            reader.autoCommit = false
            repository.lockCampaignForShare(reader, TENANT, campaign)
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val update = executor.submit<Int> { repository.updateCampaignPolicy(TENANT, campaign) }
                Thread.sleep(100)
                update.isDone shouldBeEqualTo false
                reader.commit()
                update.get(2, TimeUnit.SECONDS) shouldBeEqualTo 1
            }
        }
    }

    @Test
    fun `skip locked allocation progresses past a held candidate`() {
        val campaign = repository.createCampaign(TENANT)
        val batch = repository.createBatch(TENANT, campaign)
        val first = repository.createAvailableEntry(TENANT, campaign, batch, 1)
        val second = repository.createAvailableEntry(TENANT, campaign, batch, 2)
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
    fun `physical tenant foreign keys counters revisions nonce and entry contracts reject corruption`() {
        val campaign = repository.createCampaign(TENANT)
        val batch = repository.createBatch(TENANT, campaign)
        val entry = repository.createAvailableEntry(TENANT, campaign, batch, 10)
        assertSqlFails("UPDATE voucher_pool_campaigns SET revision=-1 WHERE tenant_id='$TENANT' AND campaign_id='$campaign'")
        assertSqlFails("UPDATE voucher_pool_entries SET nonce=NULL WHERE tenant_id='$TENANT' AND entry_id='$entry'")
        assertSqlFails("INSERT INTO voucher_pool_user_limits(tenant_id,campaign_id,user_digest,active_reservations) VALUES ('$TENANT','$campaign',decode('01','hex'),-1)")
        assertSqlFails("INSERT INTO voucher_pool_batches(tenant_id,batch_id,campaign_id,state,activates_at) VALUES ('other','$entry','$campaign','ACTIVE',now())")
        assertSqlFails("INSERT INTO voucher_pool_entries(tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,code_ciphertext,wrapped_dek,nonce) VALUES ('$TENANT',gen_random_uuid(),'$campaign','$batch',11,'AVAILABLE',decode('01','hex'),decode('02','hex'),(SELECT nonce FROM voucher_pool_entries WHERE tenant_id='$TENANT' AND entry_id='$entry'))")
    }

    @Test
    fun `representative query plans stay indexed and bounded`() {
        val campaign = repository.createCampaign(TENANT)
        val batch = repository.createBatch(TENANT, campaign)
        dataSource.connection.use { connection ->
            connection.createStatement().execute(
                """INSERT INTO voucher_pool_entries(tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,code_ciphertext,wrapped_dek,nonce,revision)
                    SELECT '$TENANT',gen_random_uuid(),'$campaign','$batch',n,'AVAILABLE',decode('01','hex'),decode('02','hex'),gen_random_bytes(12),0
                    FROM generate_series(1,10000) n""",
            )
            connection.createStatement().execute("ANALYZE voucher_pool_entries")
            connection.createStatement().execute(
                "INSERT INTO voucher_pool_pool_depth(tenant_id,batch_id,state,entry_count) VALUES ('$TENANT','$batch','AVAILABLE',10000)",
            )
            connection.createStatement().execute("ANALYZE voucher_pool_pool_depth")
        }
        val plans = mapOf(
            "allocation-candidate" to "SELECT entry_id FROM voucher_pool_entries WHERE tenant_id='$TENANT' AND campaign_id='$campaign' AND state='AVAILABLE' AND quarantined_at IS NULL ORDER BY batch_id,source_ordinal,entry_id LIMIT 1",
            "worker-candidate" to "SELECT entry_id FROM voucher_pool_entries WHERE tenant_id='$TENANT' AND batch_id='$batch' AND state='AVAILABLE' ORDER BY source_ordinal,entry_id LIMIT 100",
            "pool-depth" to "SELECT state,entry_count FROM voucher_pool_pool_depth WHERE tenant_id='$TENANT' AND batch_id='$batch' ORDER BY state LIMIT 4",
        )
        val directory = Path.of("build/reports/voucher-pool/query-plans")
        Files.createDirectories(directory)
        val ceilings = mapOf(
            "allocation-candidate" to Triple(1L, 4L, 128L),
            "worker-candidate" to Triple(100L, 200L, 512L),
            "pool-depth" to Triple(4L, 64L, 1_024L),
        )
        plans.forEach { (name, sql) ->
            val plan = queryString("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) $sql")
            Files.writeString(directory.resolve("$name.json"), plan)
            require(!Regex("\"Node Type\"\\s*:\\s*\"Seq Scan\"[\\s\\S]{0,200}\"Relation Name\"\\s*:\\s*\"voucher_pool_entries\"").containsMatchIn(plan)) {
                "$name used a sequential entry scan"
            }
            require(plan.contains("Index Scan") || plan.contains("Index Only Scan") || plan.contains("Bitmap")) {
                "$name did not use a bounded index plan"
            }
            val (rowCeiling, heapCeiling, blockCeiling) = checkNotNull(ceilings[name])
            metric(plan, "Actual Rows") shouldBeAtMost rowCeiling
            metric(plan, "Heap Fetches") shouldBeAtMost heapCeiling
            (metric(plan, "Shared Hit Blocks") + metric(plan, "Shared Read Blocks")) shouldBeAtMost blockCeiling
        }
    }

    private fun assertSqlFails(sql: String) {
        assertFailsWith<SQLException> { dataSource.connection.use { it.createStatement().execute(sql) } }
    }

    private fun queryString(sql: String): String = dataSource.connection.use { connection ->
        connection.createStatement().execute("SET enable_seqscan=off")
        connection.createStatement().executeQuery(sql).use { result -> result.next(); result.getString(1) }
    }

    private infix fun Long.shouldBeAtMost(ceiling: Long) {
        require(this <= ceiling) { "plan metric $this exceeded ceiling $ceiling" }
    }

    private fun metric(plan: String, name: String): Long =
        Regex("\"$name\"\\s*:\\s*([0-9]+)").find(plan)?.groupValues?.get(1)?.toLong() ?: 0L

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
}
