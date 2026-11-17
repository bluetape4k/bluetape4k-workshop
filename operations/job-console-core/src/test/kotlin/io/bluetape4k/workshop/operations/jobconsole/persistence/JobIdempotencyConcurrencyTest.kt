package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Tag("integration")
class JobIdempotencyConcurrencyTest {

    companion object: KLogging()

    @Test
    fun `concurrent identical submit creates one owner and one job`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)

            val results = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                List(2) {
                    executor.submit<SubmitJobResult> {
                        ready.countDown()
                        start.await()
                        repository.submit(
                            DemoCallerScope("tenant-a", "submitter-a"),
                            "same-key",
                            SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10),
                            Instant.parse("2026-07-21T00:00:00Z"),
                        )
                    }
                }.also {
                    ready.await()
                    start.countDown()
                }.map { it.get() }
            }.onEach { log.debug { "JobResult=$it" } }


            results.map(SubmitJobResult::jobId).distinct().size shouldBeEqualTo 1
            results.count(SubmitJobResult::replayed) shouldBeEqualTo 1
            fixture.count("jobs") shouldBeEqualTo 1L
            fixture.count("job_requests") shouldBeEqualTo 1L
        }
    }

    @Test
    fun `concurrent distinct submits allocate unique enqueue sequences for one tenant`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val ready = CountDownLatch(8)
            val start = CountDownLatch(1)

            val results = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                List(8) { index ->
                    executor.submit<SubmitJobResult> {
                        ready.countDown()
                        start.await()
                        repository.submit(
                            scope,
                            "distinct-key-$index",
                            SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10),
                            Instant.parse("2026-07-21T00:00:00Z"),
                        )
                    }
                }.also {
                    ready.await()
                    start.countDown()
                }.map { it.get() }
            }

            results.map(SubmitJobResult::enqueueSequence).distinct().size shouldBeEqualTo 8
            results.map(SubmitJobResult::enqueueSequence).sorted() shouldBeEqualTo (1L..8L).toList()
            fixture.count("jobs") shouldBeEqualTo 8L
            fixture.count("job_requests") shouldBeEqualTo 8L
        }
    }
}
