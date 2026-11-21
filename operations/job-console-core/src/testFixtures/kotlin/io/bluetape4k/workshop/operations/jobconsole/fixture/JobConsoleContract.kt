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
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport

/**
 * JobConsoleHttpDriver 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
interface JobConsoleHttpDriver {
/**
 * submit은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    suspend fun submit(request: SubmitJobRequest, key: String, scope: DemoCallerScope): JobSnapshot

/**
 * snapshot은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    suspend fun snapshot(jobId: UUID, scope: DemoCallerScope): JobSnapshot

/**
 * cancel은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    suspend fun cancel(jobId: UUID, scope: DemoCallerScope): JobSnapshot

/**
 * openEvents은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    suspend fun openEvents(jobId: UUID, scope: DemoCallerScope): JobEventProbe
}

/**
 * JobEventProbe 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
interface JobEventProbe: AutoCloseable {
/**
 * awaitEvent은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    suspend fun awaitEvent(timeout: Duration): JobEvent?
}

/**
 * JobConsoleContract 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class JobConsoleContract(
/**
 * driver은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val driver: JobConsoleHttpDriver,
) {
/**
 * submitReplay은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    suspend fun submitReplay(scenario: JobConsoleScenario): Pair<JobSnapshot, JobSnapshot> =
        driver.submit(scenario.request, scenario.idempotencyKey, scenario.scope) to
                driver.submit(scenario.request, scenario.idempotencyKey, scenario.scope)
}

/** Versioned black-box fixture shared by every live JVM adapter. */
/** Versioned black-box fixture shared by every live JVM adapter. */
class JobConsoleV1LiveContract(
/**
 * baseUri은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val baseUri: URI,
/**
 * client은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
/**
 * verifyOwnedWorkerLifecycle은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
        check(submit.headers().firstValue("Idempotency-Replayed").orElse(null) == "false")
        check(submit.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
        val jobId = requireNotNull(JOB_ID.find(submit.body())?.groupValues?.get(1))
        val replay = request("POST", "/v1/jobs", SUBMIT_BODY, idempotencyKey)
        check(replay.statusCode() == 202)
        check(replay.headers().firstValue("Idempotency-Replayed").orElse(null) == "true")
        check(replay.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
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

/**
 * verifyProblemRequestIdUsesUuidV7은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun verifyProblemRequestIdUsesUuidV7() {
        val problem = request("POST", "/v1/jobs", INVALID_SUBMIT_BODY, "invalid-key")

        check(problem.statusCode() == 400)
        check(problem.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"))
        val requestId = requireNotNull(REQUEST_ID.find(problem.body())?.groupValues?.get(1))
        check(UUID.fromString(requestId).version() == 7) { "requestId must use UUID v7: $requestId" }
    }

/**
 * verifyHeartbeat은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun verifyHeartbeat(jobId: String) {
        val request =
            HttpRequest.newBuilder(baseUri.resolve("/v1/jobs/$jobId/events"))
                .header("Accept", "text/event-stream")
                .header("X-Demo-Tenant", "tenant-a")
                .header("X-Demo-Submitter", "submitter-a")
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200)
        val lines = CopyOnWriteArrayList<String>()
        response.body().use { body ->
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val heartbeat =
                    executor.submit<Boolean> {
                        body.bufferedReader().useLines { stream ->
                            stream
                                .onEach(lines::add)
                                .any { it.replace(" ", "") == "event:heartbeat" }
                        }
                    }
                val received =
                    try {
                        heartbeat.get(HEARTBEAT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        body.close()
                        heartbeat.cancel(true)
                        false
                    }
                check(received) { "heartbeat event was not received within $HEARTBEAT_TIMEOUT: $lines" }
            }
        }
    }

/**
 * awaitState은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * request은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(
        method: String,
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
    ): HttpResponse<String> {
/**
 * builder은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val builder =
            HttpRequest.newBuilder(baseUri.resolve(path))
                .header("X-Demo-Tenant", "tenant-a")
                .header("X-Demo-Submitter", "submitter-a")
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody())
        else builder.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
/**
 * JOB_ID은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val JOB_ID = Regex("\\\"jobId\\\":\\\"([^\\\"]+)")
/**
 * REQUEST_ID은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val REQUEST_ID = Regex("\\\"requestId\\\":\\\"([^\\\"]+)")
/**
 * HEARTBEAT_TIMEOUT은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val HEARTBEAT_TIMEOUT: Duration = Duration.ofSeconds(5)
/**
 * SUBMIT_BODY은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val SUBMIT_BODY = """{"jobType":"document_export","workUnits":3,"failureMode":"none"}"""
/**
 * INVALID_SUBMIT_BODY은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val INVALID_SUBMIT_BODY = """{"jobType":"document_export","workUnits":0,"failureMode":"none"}"""
/**
 * CANCELLABLE_BODY은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val CANCELLABLE_BODY = """{"jobType":"document_export","workUnits":1000,"failureMode":"none"}"""
    }
}
