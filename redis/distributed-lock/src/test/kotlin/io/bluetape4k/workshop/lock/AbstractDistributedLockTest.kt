package io.bluetape4k.workshop.lock

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.lock.domain.InventoryStore
import io.bluetape4k.workshop.lock.fenced.FencedResources
import io.bluetape4k.workshop.lock.service.FencedInventoryService
import io.bluetape4k.workshop.lock.service.LockedInventoryService
import io.bluetape4k.workshop.lock.service.SuspendingFencedInventoryService
import io.bluetape4k.workshop.lock.service.UnsafeInventoryService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractDistributedLockTest {

    companion object : KLoggingChannel() {
        val redis = RedisServer.Launcher.redis
        val redisUrl: String get() = redis.url
    }

    protected val redisson: RedissonClient by lazy {
        Redisson.create(
            Config().apply {
                useSingleServer().setAddress(redisUrl)
            }
        )
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

    @AfterAll
    fun shutdown() {
        runCatching { redisson.shutdown() }
    }

    protected fun randomName(prefix: String = "test") = "$prefix-${UUID.randomUUID()}"
}
