package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

@Tag("integration")
class JobSubmissionIdempotencyMigrationTest {

    @Test
    fun `V002 preserves legacy rows and is idempotent`() {
        JobConsoleDatabaseFixture().use { fixture ->
            val migrations = migrations()
            val runner = JobMigrationRunner(fixture.dataSource, migrations, advisoryLockKey = 520_003L)
            runner.migrate().size shouldBeEqualTo 2

            val jobId = UUID.randomUUID()
            fixture.execute(
                """
                INSERT INTO job_requests(tenant_id, submitter_hash, key_hash, request_fingerprint, job_id)
                VALUES ('tenant-a', repeat('a', 64), repeat('b', 64), repeat('c', 64), '$jobId')
                """.trimIndent(),
            )

            runner.migrate() shouldBeEqualTo listOf(JobMigrationResult.ALREADY_APPLIED, JobMigrationResult.ALREADY_APPLIED)
            fixture.count("job_requests") shouldBeEqualTo 1L
            fixture.count("job_request_waiters") shouldBeEqualTo 0L
            fixture.queryLines(
                """
                SELECT state || ':' || generation || ':' || (updated_at IS NOT NULL)
                FROM job_requests
                """.trimIndent(),
            ) shouldBeEqualTo listOf("TERMINAL:1:true")
        }
    }

    @Test
    fun `V002 exposes waiter foreign key and bounded response constraints`() {
        JobConsoleDatabaseFixture().use { fixture ->
            JobMigrationRunner(fixture.dataSource, migrations(), advisoryLockKey = 520_004L).migrate()

            fixture.execute(
                """
                INSERT INTO job_requests(tenant_id, submitter_hash, key_hash, request_fingerprint, job_id)
                VALUES ('tenant-a', repeat('a', 64), repeat('b', 64), repeat('c', 64), '$LEGACY_JOB_ID')
                """.trimIndent(),
            )
            fixture.execute(
                """
                INSERT INTO job_request_waiters(
                    tenant_id, submitter_hash, key_hash, generation, waiter_token, expires_at
                ) VALUES ('tenant-a', repeat('a', 64), repeat('b', 64), 1, '$WAITER_TOKEN', CURRENT_TIMESTAMP + interval '1 minute')
                """.trimIndent(),
            )
            fixture.count("job_request_waiters") shouldBeEqualTo 1L

            assertFailsWith<Exception> {
                fixture.execute(
                    """
                    INSERT INTO job_request_waiters(
                        tenant_id, submitter_hash, key_hash, generation, waiter_token, expires_at
                    ) VALUES ('tenant-a', repeat('a', 64), repeat('z', 64), 1, '$WAITER_TOKEN', CURRENT_TIMESTAMP + interval '1 minute')
                    """.trimIndent(),
                )
            }
            assertFailsWith<Exception> {
                fixture.execute(
                    """
                    UPDATE job_requests
                    SET response_body = decode(repeat('78', 65537), 'hex')
                    WHERE tenant_id = 'tenant-a' AND submitter_hash = repeat('a', 64) AND key_hash = repeat('b', 64)
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `V002 checksum drift fails closed after V001 and V002 are applied`() {
        JobConsoleDatabaseFixture().use { fixture ->
            JobMigrationRunner(fixture.dataSource, migrations(), advisoryLockKey = 520_005L).migrate()
            val drifted = JobMigration("002", "CREATE TABLE drifted_migration(id UUID)".toByteArray())

            val failure = assertFailsWith<JobMigrationException> {
                JobMigrationRunner(
                    fixture.dataSource,
                    listOf(JobMigration.classpath("001", "db/job-console/V001__job_console.sql"), drifted),
                    advisoryLockKey = 520_005L,
                ).migrate()
            }

            failure.code shouldBeEqualTo JobMigrationFailureCode.CHECKSUM_DRIFT
        }
    }

    private fun migrations(): List<JobMigration> =
        listOf(
            JobMigration.classpath("001", "db/job-console/V001__job_console.sql"),
            JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql"),
        )

    private companion object {
        val LEGACY_JOB_ID: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
        val WAITER_TOKEN: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ac")
    }
}
