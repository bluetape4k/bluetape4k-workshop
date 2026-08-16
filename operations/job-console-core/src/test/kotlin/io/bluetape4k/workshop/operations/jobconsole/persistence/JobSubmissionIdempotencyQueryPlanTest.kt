package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@Tag("integration")
class JobSubmissionIdempotencyQueryPlanTest {

    @Test
    fun `bounded wait admission retention and abandonment plans use dedicated indexes`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            seedAdmissionRows(fixture)
            seedRetentionRows(fixture)
            fixture.execute("ANALYZE job_requests")
            fixture.execute("ANALYZE job_request_waiters")

            val admissionPlan =
                fixture.queryLinesWithoutPlannerOverride(
                    """
                    EXPLAIN (FORMAT JSON, COSTS OFF)
                    SELECT count(*)
                    FROM job_request_waiters
                    WHERE tenant_id = 'tenant-admission-07'
                      AND submitter_hash = repeat('a', 64)
                      AND key_hash = repeat('b', 64)
                      AND generation = 1
                      AND expires_at > CURRENT_TIMESTAMP
                    """.trimIndent(),
                ).joinToString("\n")
            val retentionPlan =
                fixture.queryLinesWithoutPlannerOverride(
                    """
                    EXPLAIN (FORMAT JSON, COSTS OFF)
                    SELECT ctid
                    FROM job_requests
                    WHERE state = 'TERMINAL'
                      AND retained_until <= CURRENT_TIMESTAMP
                    ORDER BY updated_at
                    LIMIT 100
                    """.trimIndent(),
                ).joinToString("\n")
            val abandonmentPlan =
                fixture.queryLinesWithoutPlannerOverride(
                    """
                    EXPLAIN (FORMAT JSON, COSTS OFF)
                    SELECT ctid
                    FROM job_requests
                    WHERE state = 'ABANDONED'
                      AND abandoned_until <= CURRENT_TIMESTAMP
                    ORDER BY updated_at
                    LIMIT 100
                    """.trimIndent(),
                ).joinToString("\n")

            admissionPlan.contains("ix_job_request_waiters_admission") shouldBeEqualTo true
            retentionPlan.contains("ix_job_requests_terminal_retention") shouldBeEqualTo true
            abandonmentPlan.contains("ix_job_requests_abandoned_until") shouldBeEqualTo true
            listOf(admissionPlan, retentionPlan, abandonmentPlan).forEach { plan ->
                plan.contains("Seq Scan") shouldBeEqualTo false
            }

            val report =
                mapOf(
                    "admission" to admissionPlan,
                    "terminalRetention" to retentionPlan,
                    "abandoned" to abandonmentPlan,
                    "seqscanOverride" to false,
                )
            val reportPath = Path.of("build/reports/job-console-idempotency/query-plans.json")
            Files.createDirectories(reportPath.parent)
            Files.writeString(reportPath, jacksonObjectMapper().writeValueAsString(report) + "\n")
        }
    }

    private fun seedAdmissionRows(fixture: JobConsoleDatabaseFixture) {
        fixture.execute(
            """
            INSERT INTO job_requests(
                tenant_id, submitter_hash, key_hash, request_fingerprint, job_id,
                state, generation, owner_token, owner_lease_expires_at, updated_at
            )
            SELECT 'tenant-admission-' || lpad(parent::text, 2, '0'), repeat('a', 64), repeat('b', 64),
                   repeat('f', 64), md5(format('admission-%s', parent))::uuid,
                   'IN_FLIGHT', 1, md5(format('owner-%s', parent))::uuid,
                   CURRENT_TIMESTAMP + interval '1 hour', CURRENT_TIMESTAMP
            FROM generate_series(0, 19) parent
            """.trimIndent(),
        )
        fixture.execute(
            """
            INSERT INTO job_request_waiters(
                tenant_id, submitter_hash, key_hash, generation, waiter_token, expires_at
            )
            SELECT 'tenant-admission-' || lpad(parent::text, 2, '0'), repeat('a', 64), repeat('b', 64), 1,
                   md5(format('waiter-%s-%s', parent, waiter))::uuid,
                   CASE WHEN waiter < 5 THEN CURRENT_TIMESTAMP - interval '1 second'
                        ELSE CURRENT_TIMESTAMP + interval '1 hour' END
            FROM generate_series(0, 19) parent
            CROSS JOIN generate_series(0, 149) waiter
            """.trimIndent(),
        )
    }

    private fun seedRetentionRows(fixture: JobConsoleDatabaseFixture) {
        fixture.execute(
            """
            INSERT INTO job_requests(
                tenant_id, submitter_hash, key_hash, request_fingerprint, job_id,
                state, generation, retained_until, updated_at
            )
            SELECT 'tenant-terminal', lpad(to_hex(item), 64, '0'), lpad(to_hex(item + 10000), 64, '0'),
                   repeat('f', 64), md5(format('terminal-%s', item))::uuid,
                   'TERMINAL', 1,
                   CASE WHEN item < 100 THEN CURRENT_TIMESTAMP - interval '1 second'
                        ELSE CURRENT_TIMESTAMP + interval '1 hour' END,
                   CURRENT_TIMESTAMP - (item || ' seconds')::interval
            FROM generate_series(0, 4999) item
            """.trimIndent(),
        )
        fixture.execute(
            """
            INSERT INTO job_requests(
                tenant_id, submitter_hash, key_hash, request_fingerprint, job_id,
                state, generation, abandoned_until, updated_at
            )
            SELECT 'tenant-abandoned', lpad(to_hex(item), 64, '0'), lpad(to_hex(item + 20000), 64, '0'),
                   repeat('f', 64), md5(format('abandoned-%s', item))::uuid,
                   'ABANDONED', 1,
                   CASE WHEN item < 100 THEN CURRENT_TIMESTAMP - interval '1 second'
                        ELSE CURRENT_TIMESTAMP + interval '1 hour' END,
                   CURRENT_TIMESTAMP - (item || ' seconds')::interval
            FROM generate_series(0, 4999) item
            """.trimIndent(),
        )
    }
}
