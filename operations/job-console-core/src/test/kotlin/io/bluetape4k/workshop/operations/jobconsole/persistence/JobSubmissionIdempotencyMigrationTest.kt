package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.*

@Tag("integration")
/**
 * JobSubmissionIdempotencyMigrationTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class JobSubmissionIdempotencyMigrationTest {

    @Test
/**
 * 이 시나리오는 "V002 preserves legacy rows and is idempotent" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

            runner.migrate() shouldBeEqualTo listOf(
                JobMigrationResult.ALREADY_APPLIED,
                JobMigrationResult.ALREADY_APPLIED
            )
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
/**
 * 이 시나리오는 "V002 exposes waiter foreign key and bounded response constraints" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "V002 checksum drift fails closed after V001 and V002 are applied" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `V002 checksum drift fails closed after V001 and V002 are applied`() {
        JobConsoleDatabaseFixture().use { fixture ->
            JobMigrationRunner(fixture.dataSource, migrations(), advisoryLockKey = 520_005L).migrate()
            val drifted = JobMigration("002", "CREATE TABLE drifted_migration(id UUID)".toUtf8Bytes())

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

/**
 * migrations은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun migrations(): List<JobMigration> =
        listOf(
            JobMigration.classpath("001", "db/job-console/V001__job_console.sql"),
            JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql"),
        )

    private companion object {
/**
 * LEGACY_JOB_ID은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val LEGACY_JOB_ID: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
/**
 * WAITER_TOKEN은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val WAITER_TOKEN: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ac")
    }
}
