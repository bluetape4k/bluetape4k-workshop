package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Tag("integration")
class JobIdempotencyConcurrencyTest {

    @Test
    fun `concurrent identical submit creates one owner and one job`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)

            val results =
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
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
                }

            results.map(SubmitJobResult::jobId).distinct().size shouldBeEqualTo 1
            results.count(SubmitJobResult::replayed) shouldBeEqualTo 1
            fixture.count("jobs") shouldBeEqualTo 1L
            fixture.count("job_requests") shouldBeEqualTo 1L
        }
    }
}
