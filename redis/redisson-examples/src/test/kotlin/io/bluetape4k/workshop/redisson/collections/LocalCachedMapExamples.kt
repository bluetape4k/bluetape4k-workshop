package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContainSame
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.redis.redisson.cache.localCachedMap
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import org.junit.jupiter.api.Test
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RMap
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.codec.CompositeCodec
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val intCodec = CompositeCodec(
    RedissonCodecs.String,
    RedissonCodecs.Int,
    RedissonCodecs.Int,
)

private val doubleCodec = CompositeCodec(
    RedissonCodecs.String,
    RedissonCodecs.Double,
    RedissonCodecs.Double,
)


/**
 * Redisson [RLocalCachedMap] 은 NearCache 와 같은 역할을 수행한다.
 *
 * 참고: [Redisson 7.-Distributed-collections](https://github.com/redisson/redisson/wiki/7.-Distributed-collections)
 *
 * 숫자 증분 예제는 String key와 숫자 value를 같은 [CompositeCodec]으로 구성합니다.
 * Redisson의 `addAndGetAsync`는 Redis hash field에 `HINCRBYFLOAT`를 실행하므로,
 * Int와 Double map을 섞지 않고 각 타입에 맞는 value codec을 사용해야 합니다.
 */
class LocalCachedMapExamples : AbstractRedissonTest() {

    companion object : KLoggingChannel()

    @Test
    fun `simple local cached map`() = runSuspendIO(timeout = 60.seconds) {
        // Local cache 설정
        val cachedMapName = "local:${Base58.randomString(8)}"
        val cachedMap: RLocalCachedMap<String, Int> = localCachedMap(cachedMapName, redisson) {
            cacheSize(10000)
            evictionPolicy(LocalCachedMapOptions.EvictionPolicy.LRU)
            maxIdle(10.seconds.toJavaDuration())
            timeToLive(60.seconds.toJavaDuration())
            retryAttempts(3)
            retryDelay { attempt -> Duration.ofMillis(attempt * 10L + 10) }
            timeout(Duration.ofSeconds(10))
            codec(intCodec)
        }

        // NOTE: fastPutAsync 의 결과는 new insert 인 경우는 true, update 는 false 를 반환한다.
        awaitRedis(cachedMap.fastPutAsync("a", 1)).shouldBeTrue()
        awaitRedis(cachedMap.fastPutAsync("b", 2)).shouldBeTrue()
        awaitRedis(cachedMap.fastPutAsync("c", 3)).shouldBeTrue()

        awaitRedis(cachedMap.containsKeyAsync("a")).shouldBeTrue()

        awaitRedis(cachedMap.getAsync("c")) shouldBeEqualTo 3

        // 저장된 Int 형태의 저장 크기 (Codec 에 따라 다르다)
        awaitRedis(cachedMap.valueSizeAsync("c")) shouldBeGreaterThan 0

        val keys = setOf("a", "b", "c")

        val mapSlice = awaitRedis(cachedMap.getAllAsync(keys))
        mapSlice shouldBeEqualTo mapOf("a" to 1, "b" to 2, "c" to 3)

        awaitRedis(cachedMap.readAllKeySetAsync()) shouldBeEqualTo setOf("a", "b", "c")
        awaitRedis(cachedMap.readAllValuesAsync()) shouldContainSame listOf(1, 2, 3)
        awaitRedis(cachedMap.readAllEntrySetAsync())
            .sortedBy { it.key }
            .associate { it.key to it.value } shouldBeEqualTo mapOf("a" to 1, "b" to 2, "c" to 3)

        // 신규 Item일 경우 true, Update 시에는 false 를 반환한다
        awaitRedis(cachedMap.fastPutAsync("a", 100)).shouldBeFalse()
        awaitRedis(cachedMap.fastPutAsync("d", 33)).shouldBeTrue()

        // 삭제 시에는 삭제된 갯수를 반환
        awaitRedis(cachedMap.fastRemoveAsync("b")) shouldBeEqualTo 1L

        // Remote 에 저장되었나 본다
        val backendMap: RMap<String, Int> = redisson.getMap(cachedMapName, intCodec)
        awaitRedis(backendMap.containsKeyAsync("a")).shouldBeTrue()

        // cachedMap을 삭제한다.
        awaitRedis(cachedMap.deleteAsync())

        // 삭제된 cachedMap은 존재하지 않는다.
        redisson.getMap<String, Int>(cachedMapName).isExists.shouldBeFalse()
    }

    @Test
    fun `empty Int key is initialized by addAndGetAsync`() = runSuspendIO(timeout = 60.seconds) {
        val name = randomName()
        val cachedMap: RLocalCachedMap<String, Int> = redisson.getLocalCachedMap(
            LocalCachedMapOptions.name<String, Int>(name).codec(intCodec)
        )

        val first = awaitRedis(cachedMap.addAndGetAsync("count", 32))
        val second = awaitRedis(cachedMap.addAndGetAsync("count", 10))
        val backendMap: RMap<String, Int> = redisson.getMap(name, intCodec)

        first shouldBeEqualTo 32
        second shouldBeEqualTo 42
        awaitRedis(backendMap.getAsync("count")) shouldBeEqualTo 42
    }

    @Test
    fun `empty Double key is initialized by HINCRBYFLOAT`() = runSuspendIO(timeout = 60.seconds) {
        val name = randomName()
        val cachedMap: RLocalCachedMap<String, Double> = redisson.getLocalCachedMap(
            LocalCachedMapOptions.name<String, Double>(name).codec(doubleCodec)
        )
        val backendMap: RMap<String, Double> = redisson.getMap(name, doubleCodec)

        awaitRedis(cachedMap.addAndGetAsync("ratio", 0.25)) shouldBeEqualTo 0.25
        awaitRedis(backendMap.getAsync("ratio")) shouldBeEqualTo 0.25
    }
}
