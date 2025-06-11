package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.coroutines.support.suspendAwait
import io.bluetape4k.junit5.awaitility.coUntil
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RMap
import org.redisson.api.RedissonClient
import org.redisson.api.options.LocalCachedMapOptions
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration


/**
 * [RLocalCachedMap] 예제
 *
 * Near Cache 기능을 구현한 [RLocalCachedMap]를 이용하여, Near Cache를 사용하는 방법을 알아봅니다.
 *
 * 참고: [Local Cache](https://github.com/redisson/redisson/wiki/7.-distributed-collections#local-cache)
 */
class LocalCachedMapTest: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    private lateinit var redisson1: RedissonClient
    private lateinit var redisson2: RedissonClient

    private val cacheName = randomName()

    private val options1 = LocalCachedMapOptions.name<String, Int>(cacheName)
        .cacheSize(100)
        .evictionPolicy(LocalCachedMapOptions.EvictionPolicy.LFU)
        // .maxIdle(10.seconds.toJavaDuration())
        .timeToLive(5.seconds.toJavaDuration())


    private val options2 = LocalCachedMapOptions.name<String, Int>(cacheName)
        .cacheSize(100)
        .evictionPolicy(LocalCachedMapOptions.EvictionPolicy.LFU)
        // .maxIdle(10.seconds.toJavaDuration())
        .timeToLive(5.seconds.toJavaDuration())

    private val frontCache1: RLocalCachedMap<String, Int> by lazy { redisson1.getLocalCachedMap(options1) }
    private val frontCache2: RLocalCachedMap<String, Int> by lazy { redisson2.getLocalCachedMap(options2) }
    private val backCache: RMap<String, Int> by lazy { redisson.getMap(cacheName) }

    @BeforeAll
    fun setup() {
        redisson1 = newRedisson()
        redisson2 = newRedisson()
    }

    @AfterAll
    fun cleanup() {
        if (this::redisson1.isInitialized) {
            redisson1.shutdown()
        }
        if (this::redisson2.isInitialized) {
            redisson2.shutdown()
        }
    }

    @Test
    fun `frontCache1 에 cache item을 추가하면 frontCache2에 추가됩니다`() = runTest {
        val keyToAdd = randomName()

        log.debug { "front cache1: put key=$keyToAdd" }
        frontCache1.fastPutAsync(keyToAdd, 42).suspendAwait()
        await coUntil { backCache.containsKeyAsync(keyToAdd).suspendAwait() }

        log.debug { "front cache2: get key=$keyToAdd" }
        frontCache2.getAsync(keyToAdd).suspendAwait() shouldBeEqualTo 42
    }

    @Test
    fun `frontCache1의 cache item을 삭제하면 frontCache2에서도 삭제됩니다`() = runTest {
        val keyToRemove = randomName()

        log.debug { "front cache1: put $keyToRemove" }
        frontCache1.fastPutAsync(keyToRemove, 42).suspendAwait()
        await coUntil { backCache.containsKeyAsync(keyToRemove).suspendAwait() }

        frontCache2.getAsync(keyToRemove).suspendAwait() shouldBeEqualTo 42

        log.debug { "front cache1: remove $keyToRemove" }
        frontCache1.fastRemoveAsync(keyToRemove).suspendAwait()
        await coUntil { !backCache.containsKeyAsync(keyToRemove).suspendAwait() }

        frontCache2.getAsync(keyToRemove).suspendAwait().shouldBeNull()
    }

    @Test
    fun `backCache에 cache item을 추가하면 frontCache 에 반영된다`() = runTest {
        val key = randomName()

        // 초기에 frontCache에 존재하지 않는다.
        frontCache1.containsKeyAsync(key).suspendAwait().shouldBeFalse()
        frontCache2.containsKeyAsync(key).suspendAwait().shouldBeFalse()

        // bachCache에 cache 등록
        backCache.fastPutAsync(key, 42).suspendAwait().shouldBeTrue()
        // frontCache2에서도 추가될 때까지 대기 (pub/sub로 전파될 때까지)
        await atMost 1.seconds.toJavaDuration() coUntil { frontCache2.containsKeyAsync(key).suspendAwait() }

        // frontCache에 등록 반영
        frontCache1.containsKeyAsync(key).suspendAwait().shouldBeTrue()
        frontCache2.containsKeyAsync(key).suspendAwait().shouldBeTrue()

        // backCache에서 cache 삭제
        backCache.fastRemoveAsync(key).suspendAwait() shouldBeEqualTo 1L
        // frontCache1에서도 삭제될 때까지 대기 (pub/sub로 전파될 때까지)
        await atMost 1.seconds.toJavaDuration() coUntil { !frontCache1.containsKeyAsync(key).suspendAwait() }

        // frontCache에 삭제 반영
        frontCache1.containsKeyAsync(key).suspendAwait().shouldBeFalse()
        frontCache2.containsKeyAsync(key).suspendAwait().shouldBeFalse()
    }

    /**
     * Eviction은 PRO 버전에서만 지원합니다.
     *
     * [Map eviction, local cache and data partitioning](https://github.com/redisson/redisson/wiki/7.-distributed-collections#711-map-eviction-local-cache-and-data-partitioning)
     */
    @Disabled("PRO 버전에서만 지원합니다.")
    @Test
    fun `frontCache1의 cache item을 expire 되면 frontCache2에서도 삭제됩니다`() = runTest(timeout = 30.seconds) {
        val keyToEvict = randomName()

        log.debug { "front cache1: put $keyToEvict" }
        frontCache1.fastPutAsync(keyToEvict, 42).suspendAwait()
        await atMost 5.seconds.toJavaDuration() until { frontCache2.containsKey(keyToEvict) }

        frontCache2.getAsync(keyToEvict).suspendAwait() shouldBeEqualTo 42

        delay(5000L)
        log.debug { "front cache1: expired $keyToEvict" }
        await atMost 5.seconds.toJavaDuration() until { frontCache2.containsKey(keyToEvict).not() }
        frontCache2.getAsync(keyToEvict).suspendAwait().shouldBeNull()
    }
}
