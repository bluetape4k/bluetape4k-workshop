package io.bluetape4k.workshop.leader.jobsafety.config

import java.net.http.HttpClient
import java.time.Duration

/** Java 25 [HttpClient]의 장시간 `close()` 대신 bounded shutdown 계약을 소유합니다. */
class JobSafetyAuditHttpClientLifecycle(
    private val client: HttpClient,
) {
    /** pending request를 취소하고 client shutdown을 시작합니다. */
    fun shutdownNow() {
        client.shutdownNow()
    }

    /** 지정한 남은 시간 안에서 client 종료를 기다립니다. */
    fun awaitTermination(timeout: Duration): Boolean = client.awaitTermination(timeout)

    /** client가 모든 작업을 종료했는지 확인합니다. */
    fun isTerminated(): Boolean = client.isTerminated
}
