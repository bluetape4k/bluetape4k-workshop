package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.locks.LockSupport

interface JobConsoleHttpDriver {
    suspend fun submit(request: SubmitJobRequest, key: String, scope: DemoCallerScope): JobSnapshot

    suspend fun snapshot(jobId: UUID, scope: DemoCallerScope): JobSnapshot

    suspend fun cancel(jobId: UUID, scope: DemoCallerScope): JobSnapshot

    suspend fun openEvents(jobId: UUID, scope: DemoCallerScope): JobEventProbe
}

interface JobEventProbe : AutoCloseable {
    suspend fun awaitEvent(timeout: Duration): JobEvent?
}

class JobConsoleContract(
    private val driver: JobConsoleHttpDriver,
) {
    suspend fun submitReplay(scenario: JobConsoleScenario): Pair<JobSnapshot, JobSnapshot> =
        driver.submit(scenario.request, scenario.idempotencyKey, scenario.scope) to
            driver.submit(scenario.request, scenario.idempotencyKey, scenario.scope)
}

/** Versioned black-box fixture shared by every live JVM adapter. */
class JobConsoleV1LiveContract(
    private val baseUri: URI,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    fun verifyOwnedWorkerLifecycle(idempotencyKey: String) {
        check(request("GET", "/").body().contains("ETA is an estimate, not an SLA"))
        check(request("GET", "/healthz").statusCode() == 200)
        val readiness = request("GET", "/readyz")
        check(readiness.statusCode() == 200)
        check(readiness.body().contains("\"redis\":\"DEGRADED\""))

        val submit = request("POST", "/v1/jobs", SUBMIT_BODY, idempotencyKey)
        check(submit.statusCode() == 202)
        val jobId = requireNotNull(JOB_ID.find(submit.body())?.groupValues?.get(1))
        awaitState(jobId, "succeeded")
    }

    private fun awaitState(jobId: String, expected: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        do {
            val snapshot = request("GET", "/v1/jobs/$jobId")
            check(snapshot.statusCode() == 200) {
                "snapshot failed status=${snapshot.statusCode()} body=${snapshot.body()}"
            }
            if (snapshot.body().contains("\"state\":\"$expected\"")) return
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos())
        } while (System.nanoTime() < deadline)
        error("job $jobId did not reach $expected")
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(baseUri.resolve(path))
                .header("X-Demo-Tenant", "tenant-a")
                .header("X-Demo-Submitter", "submitter-a")
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody())
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        val JOB_ID = Regex("\\\"jobId\\\":\\\"([^\\\"]+)")
        const val SUBMIT_BODY = """{"jobType":"document_export","workUnits":3,"failureMode":"none"}"""
    }
}
