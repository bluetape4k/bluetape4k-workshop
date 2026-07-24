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
        val ui = request("GET", "/").body()
        check(ui.contains("ETA is an estimate, not an SLA"))
        check(ui.contains("id=\"submitJob\""))
        check(ui.contains("response.body.getReader()"))
        check(request("GET", "/healthz").statusCode() == 200)
        val readiness = request("GET", "/readyz")
        check(readiness.statusCode() == 200)
        check(readiness.body().contains("\"redis\":\"DEGRADED\""))

        val submit = request("POST", "/v1/jobs", SUBMIT_BODY, idempotencyKey)
        check(submit.statusCode() == 202)
        val jobId = requireNotNull(JOB_ID.find(submit.body())?.groupValues?.get(1))
        val replay = request("POST", "/v1/jobs", SUBMIT_BODY, idempotencyKey)
        check(replay.statusCode() == 202)
        check(JOB_ID.find(replay.body())?.groupValues?.get(1) == jobId)
        verifyHeartbeat(jobId)
        awaitState(jobId, "succeeded")

        val cancellable = request("POST", "/v1/jobs", CANCELLABLE_BODY, "$idempotencyKey-cancel")
        val cancellableJobId = requireNotNull(JOB_ID.find(cancellable.body())?.groupValues?.get(1))
        awaitState(cancellableJobId, "running")
        val cancelled = request("POST", "/v1/jobs/$cancellableJobId/cancel")
        check(cancelled.statusCode() == 200)
        awaitState(cancellableJobId, "cancelled")
    }

    fun verifyProblemRequestIdUsesUuidV7() {
        val problem = request("POST", "/v1/jobs", INVALID_SUBMIT_BODY, "invalid-key")

        check(problem.statusCode() == 400)
        val requestId = requireNotNull(REQUEST_ID.find(problem.body())?.groupValues?.get(1))
        check(UUID.fromString(requestId).version() == 7) { "requestId must use UUID v7: $requestId" }
    }

    private fun verifyHeartbeat(jobId: String) {
        val request =
            HttpRequest.newBuilder(baseUri.resolve("/v1/jobs/$jobId/events"))
                .header("Accept", "text/event-stream")
                .header("X-Demo-Tenant", "tenant-a")
                .header("X-Demo-Submitter", "submitter-a")
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200)
        response.body().bufferedReader().use { reader ->
            val lines = generateSequence(reader::readLine).take(4).toList()
            check(lines.any { it.replace(" ", "") == "event:heartbeat" }) { "heartbeat event was not received: $lines" }
        }
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
        val REQUEST_ID = Regex("\\\"requestId\\\":\\\"([^\\\"]+)")
        const val SUBMIT_BODY = """{"jobType":"document_export","workUnits":3,"failureMode":"none"}"""
        const val INVALID_SUBMIT_BODY = """{"jobType":"document_export","workUnits":0,"failureMode":"none"}"""
        const val CANCELLABLE_BODY = """{"jobType":"document_export","workUnits":1000,"failureMode":"none"}"""
    }
}
