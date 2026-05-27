package io.bluetape4k.workshop.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.redis.redisson.redissonClient
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.bluetape4k.workshop.lock.domain.InventoryStore
import io.bluetape4k.workshop.lock.fenced.FencedResources
import io.bluetape4k.workshop.lock.service.FencedInventoryService
import io.bluetape4k.workshop.lock.service.LockedInventoryService
import io.bluetape4k.workshop.lock.service.SuspendingFencedInventoryService
import io.bluetape4k.workshop.lock.service.UnsafeInventoryService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.redisson.api.RedissonClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractDistributedLockTest {

    companion object : KLoggingChannel() {
        val redis = RedisServer.Launcher.redis
        val redisUrl: String get() = redis.url
    }

    protected val redisson: RedissonClient by lazy {
        redissonClient {
            useSingleServer().setAddress(redisUrl)
        }.also {
            ShutdownQueue.register { runCatching { it.shutdown() } }
        }
    }

    protected val store = InventoryStore()
    protected val fencedResources = FencedResources()

    protected val unsafeService: UnsafeInventoryService by lazy { UnsafeInventoryService(store) }
    protected val lockedService: LockedInventoryService by lazy { LockedInventoryService(redisson, store) }
    protected val fencedService: FencedInventoryService by lazy {
        FencedInventoryService(redisson, store, fencedResources)
    }
    protected val suspendingService: SuspendingFencedInventoryService by lazy {
        SuspendingFencedInventoryService(redisson, store, fencedResources)
    }

    @BeforeEach
    fun resetState() {
        store.resetAll()
        fencedResources.resetAll()
    }

    protected fun randomName(prefix: String = "test") = "$prefix-${Base58.randomString(8)}"
}
