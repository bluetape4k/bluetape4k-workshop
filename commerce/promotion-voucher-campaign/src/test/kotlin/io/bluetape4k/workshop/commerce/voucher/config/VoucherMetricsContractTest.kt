package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

internal class VoucherMetricsContractTest {
    @Test
    fun `metric names and tags stay bounded while operational paths update values`() {
        val registry = SimpleMeterRegistry()
        val metrics = VoucherMetrics(registry)

        metrics.recordCommand("ALLOCATE", "ACCEPTED", Duration.ofMillis(12))
        metrics.databaseRejected("FOREGROUND")
        metrics.redisDegraded("RATE_LIMIT")
        metrics.reviewOpen(3)
        metrics.backlogOldestAge(Duration.ofSeconds(9))
        metrics.workerSucceeded(1_700_000_000L, attempts = 2)
        metrics.sseOpened()
        metrics.sseRejected("CAPACITY")
        metrics.leaderState(VoucherLeaderState.ELECTED)

        val names = registry.meters.map { it.id.name }.toSet()
        REQUIRED_NAMES.all(names::contains) shouldBeEqualTo true
        registry.get("voucher.command.duration").timer().count() shouldBeEqualTo 1L
        registry.get("voucher.db.bulkhead.rejected").counter().count() shouldBeGreaterThan 0.0
        registry.get("voucher.review.open").gauge().value() shouldBeEqualTo 3.0
        registry.get("voucher.sse.active").gauge().value() shouldBeEqualTo 1.0
        registry.meters.flatMap { it.id.tags }.none { it.key in FORBIDDEN_TAGS } shouldBeEqualTo true
    }

    private companion object {
        val REQUIRED_NAMES =
            setOf(
                "voucher.command.duration",
                "voucher.db.bulkhead.rejected",
                "voucher.redis.degraded",
                "voucher.review.open",
                "voucher.backlog.oldest.age",
                "voucher.worker.last.success",
                "voucher.worker.attempts",
                "voucher.sse.active",
                "voucher.sse.rejected",
                "voucher.leader.state",
            )
        val FORBIDDEN_TAGS = setOf("tenant", "campaign", "user", "digest", "revision", "secret", "code")
    }
}
