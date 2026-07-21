package io.bluetape4k.workshop.leader.jobsafety.coordination.redis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.leader.jobsafety.coordination.FenceAcquireResult
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
    fun `takeover increments the fence and renewal preserves it`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection.sync(), NamespaceEpoch(1L))
            val first = adapter.acquire(KEY, owner("a"), TTL).acquiredLease()

            adapter.renew(first, TTL) shouldBeEqualTo FenceRenewResult.Renewed(first.token)
            connection.sync().del(adapter.keysFor(KEY).lease)
            val second = adapter.acquire(KEY, owner("b"), TTL).acquiredLease()

            (second.token > first.token).shouldBeTrue()
        }

    @Test
    fun `ambiguous retry reuses the active owner fence`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection.sync(), NamespaceEpoch(1L))
            val first = adapter.acquire(KEY, owner("a"), TTL).acquiredLease()
            val retry = adapter.acquire(KEY, owner("a"), TTL)

            (retry is FenceAcquireResult.AlreadyOwned).shouldBeTrue()
            (retry as FenceAcquireResult.AlreadyOwned).lease.token shouldBeEqualTo first.token
        }

    @Test
    fun `stale owner cannot renew or release a newer generation`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection.sync(), NamespaceEpoch(1L))
            val first = adapter.acquire(KEY, owner("a"), TTL).acquiredLease()
            connection.sync().del(adapter.keysFor(KEY).lease)
            adapter.acquire(KEY, owner("b"), TTL).acquiredLease()

            adapter.renew(first, TTL) shouldBeEqualTo FenceRenewResult.OwnershipLost
            adapter.release(first) shouldBeEqualTo FenceReleaseResult.OwnershipLost
        }

    @Test
    fun `release removes only the lease and preserves counter history`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection.sync(), NamespaceEpoch(1L))
            val first = adapter.acquire(KEY, owner("a"), TTL).acquiredLease()

            adapter.release(first) shouldBeEqualTo FenceReleaseResult.Released
            val keys = adapter.keysFor(KEY)
            connection.sync().exists(keys.lease) shouldBeEqualTo 0L
            connection.sync().exists(keys.counter) shouldBeEqualTo 1L
            val second = adapter.acquire(KEY, owner("b"), TTL).acquiredLease()
            (second.token > first.token).shouldBeTrue()
        }

    @Test
    fun `malformed lease missing counter and counter overflow fail closed`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection.sync(), NamespaceEpoch(1L))
            val keys = adapter.keysFor(KEY)

            connection.sync().set(keys.epoch, "1")
            connection.sync().set(keys.lease, "missing-separator")
            (adapter.acquire(KEY, owner("a"), TTL) is FenceAcquireResult.BackendFailure).shouldBeTrue()

            connection.sync().set(keys.lease, "owner|7")
            connection.sync().del(keys.counter)
            (adapter.acquire(KEY, owner("a"), TTL) is FenceAcquireResult.BackendFailure).shouldBeTrue()

            connection.sync().del(keys.lease)
            connection.sync().set(keys.counter, Long.MAX_VALUE.toString())
            (adapter.acquire(KEY, owner("a"), TTL) is FenceAcquireResult.BackendFailure).shouldBeTrue()
        }

    @Test
    fun `namespace mismatch fails closed and all script keys share a hash slot`() =
        withRedis { connection ->
            val epochOne = RedisJobFencingLeaseAdapter(connection.sync(), NamespaceEpoch(1L))
            val epochTwo = RedisJobFencingLeaseAdapter(connection.sync(), NamespaceEpoch(2L))
            val keys = epochOne.keysFor(KEY)

            epochOne.acquire(KEY, owner("a"), TTL).acquiredLease()
            (epochTwo.acquire(KEY, owner("b"), TTL) is FenceAcquireResult.BackendFailure).shouldBeTrue()
            listOf(keys.lease, keys.counter, keys.epoch).map(::hashTag).distinct().size shouldBeEqualTo 1
        }

    @Test
    fun `script flush falls back to source without changing the contract`() =
        withRedis { connection ->
            val adapter = RedisJobFencingLeaseAdapter(connection.sync(), NamespaceEpoch(1L))
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

    private fun hashTag(key: String): String = key.substringAfter('{').substringBefore('}')

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
