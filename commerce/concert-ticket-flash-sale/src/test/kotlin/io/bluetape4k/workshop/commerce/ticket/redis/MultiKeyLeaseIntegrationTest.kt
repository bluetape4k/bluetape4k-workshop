package io.bluetape4k.workshop.commerce.ticket.redis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.api.StatefulRedisConnection
import org.junit.jupiter.api.Test
import java.time.Duration

internal class MultiKeyLeaseIntegrationTest {
    @Test
    fun `acquire is all or nothing for ip and user keys`() =
        withRedis { connection ->
            val adapter = MultiKeyLeaseAdapter(connection.sync())
            val request = request(owner(2, "owner-current"))
            connection.sync().set(request.keys.ip, owner(9, "other-owner").wireValue)

            adapter.acquire(request) shouldBeEqualTo LeaseDecision.Busy
            (connection.sync().exists(request.keys.user) == 1L).shouldBeFalse()
        }

    @Test
    fun `wrong owner cannot renew or release both keys`() =
        withRedis { connection ->
            val adapter = MultiKeyLeaseAdapter(connection.sync())
            val request = request(owner(2, "owner-current"))
            val wrongOwner = request(owner(2, "wrong-owner"))

            adapter.acquire(request) shouldBeEqualTo LeaseDecision.Acquired(version = 2)
            adapter.renew(wrongOwner).shouldBeFalse()
            adapter.release(wrongOwner).shouldBeFalse()
            adapter.renew(request).shouldBeTrue()
            adapter.release(request).shouldBeTrue()
            connection.sync().exists(request.keys.ip, request.keys.user) shouldBeEqualTo 0L
        }

    @Test
    fun `acquire response loss across key rotation reuses retained owner token`() =
        withRedis { connection ->
            val adapter = MultiKeyLeaseAdapter(connection.sync())
            val oldOwner = owner(1, "retained-owner")
            val firstRequest = request(oldOwner)
            val rotatedRequest = request(owner(2, "new-owner"), oldOwner)

            adapter.acquire(firstRequest) shouldBeEqualTo LeaseDecision.Acquired(version = 1)
            adapter.acquire(rotatedRequest) shouldBeEqualTo LeaseDecision.AlreadyOwned(version = 1)
            connection.sync().get(rotatedRequest.keys.ip) shouldBeEqualTo oldOwner.wireValue
            connection.sync().get(rotatedRequest.keys.user) shouldBeEqualTo oldOwner.wireValue
            adapter.release(rotatedRequest).shouldBeTrue()
        }

    @Test
    fun `retained owner repairs a partially evicted lease without changing ownership`() =
        withRedis { connection ->
            val adapter = MultiKeyLeaseAdapter(connection.sync())
            val oldOwner = owner(1, "retained-owner")
            val rotatedRequest = request(owner(2, "new-owner"), oldOwner)

            adapter.acquire(request(oldOwner)) shouldBeEqualTo LeaseDecision.Acquired(version = 1)
            connection.sync().del(rotatedRequest.keys.user)

            adapter.acquire(rotatedRequest) shouldBeEqualTo LeaseDecision.AlreadyOwned(version = 1)
            connection.sync().get(rotatedRequest.keys.user) shouldBeEqualTo oldOwner.wireValue
        }

    private fun request(vararg owners: LeaseOwner): LeaseRequest =
        LeaseRequest(
            keys = LeaseKeys(
                ip = "ticket:{sale-safe}:inflight:ip:ip-safe",
                user = "ticket:{sale-safe}:inflight:user:user-safe",
            ),
            ownerCandidates = owners.toList(),
            ttl = Duration.ofSeconds(5),
        )

    private fun owner(version: Int, token: String) = LeaseOwner(version, token.padEnd(43, 'x'))

    private fun withRedis(block: (StatefulRedisConnection<String, String>) -> Unit) {
        val redis = RedisServer.Launcher.redis
        val client = LettuceClients.clientOf(redis.url)
        val connection = LettuceClients.connect(client)
        try {
            connection.sync().flushdb()
            block(connection)
        } finally {
            connection.close()
            LettuceClients.shutdown(client)
        }
    }
}
