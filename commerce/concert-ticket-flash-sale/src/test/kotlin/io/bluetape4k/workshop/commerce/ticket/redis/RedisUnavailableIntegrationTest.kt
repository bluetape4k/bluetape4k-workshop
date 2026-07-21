package io.bluetape4k.workshop.commerce.ticket.redis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.ticket.config.TicketProperties
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisConfiguration
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisProperties
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisResources
import io.lettuce.core.RedisException
import org.junit.jupiter.api.Test
import java.time.Duration

internal class RedisUnavailableIntegrationTest {
    @Test
    fun `distributed route limit rejects excess calls independently of the lease`() {
        val redis = RedisServer.Launcher.redis
        val cleanupClient = LettuceClients.clientOf(redis.url)
        try {
            LettuceClients.connect(cleanupClient).sync().flushdb()
        } finally {
            LettuceClients.shutdown(cleanupClient)
        }
        val properties =
            TicketProperties(
                redis =
                    TicketRedisProperties(
                        uri = redis.url,
                        rateLimitCapacity = 2,
                        rateLimitPeriod = Duration.ofHours(1),
                    ),
            )

        TicketRedisResources.open(properties.redis).use { resources ->
            val limiter = TicketRedisConfiguration().ticketRateLimiter(resources, properties)

            limiter.consume("purchase:safe-subject", 1).isConsumed.shouldBeTrue()
            limiter.consume("purchase:safe-subject", 1).isConsumed.shouldBeTrue()
            limiter.consume("purchase:safe-subject", 1).isRejected.shouldBeTrue()
        }
    }

    @Test
    fun `new purchase fails closed while existing database workflow remains runnable`() {
        val unavailable = MultiKeyLeasePort { throw RedisException("redis unavailable") }
        val gate = ForegroundLeaseGate(unavailable)
        var reconciled = 0

        assertFailsWith<AdmissionTemporarilyUnavailable> {
            gate.acquire(request())
        }
        reconciled += 1

        reconciled shouldBeEqualTo 1
    }

    @Test
    fun `busy lease is a conflict instead of a redis outage`() {
        val busy = MultiKeyLeasePort { LeaseDecision.Busy }

        assertFailsWith<PurchaseApprovalInProgress> {
            ForegroundLeaseGate(busy).acquire(request())
        }
    }

    private fun request() =
        LeaseRequest(
            keys = LeaseKeys("ticket:{sale}:inflight:ip:ip", "ticket:{sale}:inflight:user:user"),
            ownerCandidates = listOf(LeaseOwner(1, "owner".padEnd(43, 'x'))),
            ttl = Duration.ofSeconds(5),
        )
}
