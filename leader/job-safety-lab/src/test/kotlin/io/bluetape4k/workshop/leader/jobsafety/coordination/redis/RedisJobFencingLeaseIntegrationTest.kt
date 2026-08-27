package io.bluetape4k.workshop.leader.jobsafety.coordination.redis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.leader.jobsafety.coordination.FenceAcquireResult
import io.bluetape4k.workshop.leader.jobsafety.coordination.FenceBootstrapResult
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingOwnerId
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.lettuce.core.api.StatefulRedisConnection
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

@Tag("integration")
internal class RedisJobFencingLeaseIntegrationTest {
    @Test
    fun `bootstrap is explicit and idempotent for the approved epoch`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection, NamespaceEpoch(1L))

            adapter.bootstrap(KEY) shouldBeEqualTo FenceBootstrapResult.Ready
            adapter.bootstrap(KEY) shouldBeEqualTo FenceBootstrapResult.Ready
        }

    @Test
    fun `takeover increments the tuple fence and renewal preserves it`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection, NamespaceEpoch(1L))
            adapter.bootstrap(KEY)
            val first = adapter.acquire(KEY, owner("a"), TTL).acquiredLease()

            adapter.renew(first, TTL) shouldBeEqualTo FenceRenewResult.Renewed(first.token)
            adapter.release(first) shouldBeEqualTo FenceReleaseResult.Released
            val second = adapter.acquire(KEY, owner("b"), TTL).acquiredLease()

            (second.token > first.token).shouldBeTrue()
            second.token.epoch shouldBeEqualTo first.token.epoch
        }

    @Test
    fun `ambiguous retry reuses the active owner fence`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection, NamespaceEpoch(1L))
            adapter.bootstrap(KEY)
            val first = adapter.acquire(KEY, owner("a"), TTL).acquiredLease()
            val retry = adapter.acquire(KEY, owner("a"), TTL)

            (retry is FenceAcquireResult.AlreadyOwned).shouldBeTrue()
            (retry as FenceAcquireResult.AlreadyOwned).lease.token shouldBeEqualTo first.token
        }

    @Test
    fun `stale owner cannot renew or release a newer generation`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection, NamespaceEpoch(1L))
            adapter.bootstrap(KEY)
            val first = adapter.acquire(KEY, owner("a"), TTL).acquiredLease()
            adapter.release(first) shouldBeEqualTo FenceReleaseResult.Released
            adapter.acquire(KEY, owner("b"), TTL).acquiredLease()

            adapter.renew(first, TTL) shouldBeEqualTo FenceRenewResult.OwnershipLost
            adapter.release(first) shouldBeEqualTo FenceReleaseResult.OwnershipLost
        }

    @Test
    fun `a new approved epoch is explicit and rejects stale leases`() =
        withRedis { connection ->
            val epochOne = RedisJobFencingLeaseAdapter(connection, NamespaceEpoch(1L))
            val epochTwo = RedisJobFencingLeaseAdapter(connection, NamespaceEpoch(2L))
            epochOne.bootstrap(KEY)
            val first = epochOne.acquire(KEY, owner("a"), TTL).acquiredLease()

            (epochTwo.acquire(KEY, owner("b"), TTL) is FenceAcquireResult.BackendFailure).shouldBeTrue()
            epochTwo.bootstrap(KEY) shouldBeEqualTo FenceBootstrapResult.Ready
            val second = epochTwo.acquire(KEY, owner("b"), TTL).acquiredLease()
            second.token.epoch shouldBeEqualTo 2L
            (epochTwo.renew(first, TTL) is FenceRenewResult.BackendFailure).shouldBeTrue()
            (epochTwo.release(first) is FenceReleaseResult.BackendFailure).shouldBeTrue()
        }

    @Test
    fun `script flush falls back to the shared source without changing the contract`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection, NamespaceEpoch(1L))
            adapter.bootstrap(KEY)
            val first = adapter.acquire(KEY, owner("a"), TTL).acquiredLease()
            connection.sync().scriptFlush()

            adapter.renew(first, TTL) shouldBeEqualTo FenceRenewResult.Renewed(first.token)
            connection.sync().scriptFlush()
            adapter.release(first) shouldBeEqualTo FenceReleaseResult.Released
        }

    private fun FenceAcquireResult.acquiredLease() =
        when (this) {
            is FenceAcquireResult.Acquired -> lease
            is FenceAcquireResult.AlreadyOwned -> lease
            else -> error("expected acquired lease but was $this")
        }

    private fun owner(value: String) = FencingOwnerId("owner-$value")

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

    companion object {
        private val KEY = ConflictKey.of("summary:tenant-a:2026-07")
        private val TTL: Duration = Duration.ofSeconds(5)
    }
}
