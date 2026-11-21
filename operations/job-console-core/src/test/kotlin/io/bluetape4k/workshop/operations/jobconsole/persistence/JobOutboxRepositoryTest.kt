package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobEventType
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/** durable 상태 변경별 outbox event, bounded claim, stable logical ID, backlog 관측을 검증한다. */
/** durable 상태 변경별 outbox event, bounded claim, stable logical ID, backlog 관측을 검증한다. */
@Tag("integration")
class JobOutboxRepositoryTest {

    companion object: KLogging() {
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
/**
 * SCOPE은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val SCOPE = DemoCallerScope("tenant-a", "submitter-a")
    }

    @Test
/**
 * 이 시나리오는 "each durable change notifies both job and queue snapshot consumers" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `each durable change notifies both job and queue snapshot consumers`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            JobRepository(fixture.dataSource)
                .submit(SCOPE, "key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)

            val events = JobOutboxRepository(fixture.dataSource)
                .claim(10, 30.seconds())
                .events
                .onEach { log.debug { "event: $it" } }

            events.map { it.eventType }.toSet() shouldBeEqualTo
                    setOf(JobEventType.JOB_UPDATED, JobEventType.QUEUE_UPDATED)
        }
    }

    @Test
/**
 * 이 시나리오는 "claim is bounded and failed publication can be retried with stable event id" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `claim is bounded and failed publication can be retried with stable event id`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val jobs = JobRepository(fixture.dataSource)
            val outbox = JobOutboxRepository(fixture.dataSource)
            repeat(5) { index ->
                jobs.submit(SCOPE, "key-$index", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)
            }

            val firstClaim = outbox.claim(batchSize = 2, claimDuration = 30.seconds())
            firstClaim.events.size shouldBeEqualTo 2
            outbox.release(firstClaim.token, firstClaim.events.first().eventId)

            val retry = outbox.claim(batchSize = 1, claimDuration = 30.seconds()).events.single()
            retry.eventId shouldBeEqualTo firstClaim.events.first().eventId
            outbox.markPublished(firstClaim.token, firstClaim.events.last().eventId).shouldBeTrue()
            outbox.markPublished(firstClaim.token, firstClaim.events.last().eventId).shouldBeTrue()
        }
    }

    @Test
/**
 * 이 시나리오는 "oldest unpublished age is observable without exposing payload" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `oldest unpublished age is observable without exposing payload`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            JobRepository(fixture.dataSource).submit(SCOPE, "key", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)

            JobOutboxRepository(fixture.dataSource)
                .oldestUnpublishedAge(NOW + 10.seconds()) shouldBeEqualTo 10.seconds()
        }
    }

    @Test
/**
 * 이 시나리오는 "durable job and outbox identities use UUID version seven" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `durable job and outbox identities use UUID version seven`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val jobs = JobRepository(fixture.dataSource)
            val submitted =
                jobs.submit(
                    SCOPE,
                    "v7-key",
                    SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1),
                    NOW,
                )
            val lease = requireNotNull(jobs.claimNext(SCOPE.tenantId, 30.seconds()))
            val claimed = JobOutboxRepository(fixture.dataSource).claim(10, 30.seconds())

            submitted.jobId.version() shouldBeEqualTo 7
            lease.lease.token.version() shouldBeEqualTo 7
            claimed.token.version() shouldBeEqualTo 7
            claimed.events.map { it.eventId.version() }.toSet() shouldBeEqualTo setOf(7)
        }
    }
}
