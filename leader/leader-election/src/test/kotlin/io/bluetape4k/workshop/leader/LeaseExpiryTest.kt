package io.bluetape4k.workshop.leader

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58
import kotlin.time.Duration.Companion.milliseconds

/**
 * T5: lease expiry 교육용 smoke test입니다.
 *
 * leader가 leaseTime TTL보다 오래 lock을 보유할 때 어떤 일이 생기는지 보여줍니다.
 * 결과(두 번째 elector의 성공 또는 실패)는 library contract에 따라 달라지고 Redis timing에도
 * 영향을 받을 수 있습니다. 이 테스트는 strict assertion이 아니라 의도적으로 교육용입니다.
 *
 * `junit.jupiter.execution.exclude.tags=smoke`로 기본 `test` task에서 제외됩니다.
 * nightly 또는 다음 명령으로 명시 실행합니다. `./gradlew test -Djunit.jupiter.execution.exclude.tags=`
 */
@Tag("smoke")
class LeaseExpiryTest : AbstractLeaderElectionTest() {

    @Test
    fun `leader holding lock beyond leaseTime allows second elector to attempt acquisition`() {
        val lockName = "test:t5:${Base58.randomString(8)}"
        val shortLeaseOptions = LeaderElectionOptions(
            waitTime = 50.milliseconds,
            leaseTime = 200.milliseconds,
        )
        val elector1 = newElector(shortLeaseOptions)
        val elector2 = newElector(shortLeaseOptions)

        // elector1이 lock을 획득하고 leaseTime보다 오래 sleep합니다. sleep 중 TTL이 만료됩니다.
        val result1 = elector1.runIfLeader(lockName) {
            log.info { "[T5] Leader acquired. Sleeping 500ms (leaseTime=200ms)..." }
            Thread.sleep(500)
            "leader-done"
        }
        log.info { "[T5] elector1 result: $result1" }

        // elector1이 끝난 뒤 elector2가 획득을 시도합니다. 동작은 library contract에 따릅니다.
        val result2 = elector2.runIfLeader(lockName) { "second-acquired" }
        log.info { "[T5] elector2 result (null=skipped, non-null=acquired): $result2" }
        // strict assertion은 없습니다. 이 테스트는 library의 lease-expiry 동작을 문서화합니다.
    }

    companion object : KLogging()
}
