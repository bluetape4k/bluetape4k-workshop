package io.bluetape4k.workshop.redisson.objects

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.redis.redisson.RedissonCodecs
import io.bluetape4k.redis.redisson.coroutines.coAwait
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeInRange
import org.amshove.kluent.shouldBeTrue
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.Test
import org.redisson.api.DeletedObjectListener
import org.redisson.api.RBucket
import org.redisson.api.listener.SetObjectListener
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * [RBucket] 예제
 *
 * RBucket 은 객체 저장소로 사용되며, 512Mb 이하의 객체를 저장할 수 있다. (AtomicReference 와 유사한 작업을 할 수 있다)
 *
 * 참고:
 * - [RBucket](https://github.com/redisson/redisson/wiki/6.-distributed-objects/#61-object-holder)
 */
class BucketExamples: AbstractRedissonTest() {

    companion object: KLogging()

    @Test
    fun `use bucket`() = runTest {
        val bucket: RBucket<String> = redisson.getBucket(randomName(), RedissonCodecs.String)

        // bucket에 object를 설정한다
        bucket.setAsync("000", 60.seconds.toJavaDuration()).coAwait()
        delay(100)

        // 기존 TTL을 유지하면서 신규 value로 변경한다
        bucket.setAndKeepTTLAsync("123").coAwait()

        // TTL 정보
        bucket.remainTimeToLiveAsync().coAwait() shouldBeInRange (0L until 60 * 1000L)

        // "123" 값을 가지고 있으면 "2032" 로 변경한다
        bucket.compareAndSetAsync("123", "2032").coAwait().shouldBeTrue()

        // 기존 값을 가져오고, 새로운 값으로 설정
        bucket.getAndSetAsync("5081").coAwait() shouldBeEqualTo "2032"

        // bucket에 object가 없을 때에 set을 수행한다 (이미 있으므로 실패)
        bucket.setIfAbsentAsync("7777", 60.seconds.toJavaDuration()).coAwait().shouldBeFalse()

        // 기존 값이 있을 때에만 새로 설정한다
        bucket.setIfExistsAsync("9999").coAwait().shouldBeTrue()

        // object size in bytes
        log.debug { "Current bucket size=${bucket.size()}" }
        bucket.get() shouldBeEqualTo "9999"
        bucket.size() shouldBeEqualTo "9999".toUtf8Bytes().size.toLong()  // String Codec 이어야만 함 

        bucket.deleteAsync().coAwait()
    }

    @Test
    fun `use bucket in coroutines`() = runTest {
        val bucket: RBucket<String> = redisson.getBucket(randomName())

        // bucket에 object를 설정한다
        bucket.setAsync("000", 60, TimeUnit.SECONDS).coAwait()

        val job = launch(Dispatchers.IO) {
            delay(10)
            bucket.compareAndSetAsync("000", "111").coAwait().shouldBeTrue()
        }

        delay(100)
        job.join()

        bucket.getAndDeleteAsync().coAwait() shouldBeEqualTo "111"

        bucket.deleteAsync().coAwait()
    }

    @Test
    fun `multiple buckets example`() = runTest {
        val buckets = redisson.buckets

        val bucketName1 = randomName()
        val bucketName2 = randomName()
        val bucketName3 = randomName()

        // 기존에 데이터를 가진 bucket 이 없다
        val existBuckets1 = buckets.getAsync<String>(bucketName1, bucketName2, bucketName3).coAwait()
        existBuckets1.size shouldBeEqualTo 0


        val map = mutableMapOf(
            bucketName1 to "object1",
            bucketName2 to "object2"
        )
        // 복수의 bucket에 한번에 데이터를 저장한다 (기존에 데이터가 있는 bucket 이 하나라도 있다면 실패한다)
        buckets.trySetAsync(map).coAwait().shouldBeTrue()

        // object를 가진 bucket 은 2개이다.
        val nameAndValues = buckets.getAsync<String>(bucketName1, bucketName2, bucketName3).coAwait()
        nameAndValues.size shouldBeEqualTo 2
        nameAndValues.map { it.key } shouldBeEqualTo listOf(bucketName1, bucketName2)

        val bucket1 = redisson.getBucket<String>(bucketName1)
        bucket1.get() shouldBeEqualTo "object1"

        // 기존에 데이터가 있는 bucket 이 하나라도 있다면 실패한다
        buckets.trySetAsync(map).coAwait().shouldBeFalse()

        redisson.getBucket<String>(bucketName1).deleteAsync().coAwait().shouldBeTrue()
        redisson.getBucket<String>(bucketName2).deleteAsync().coAwait().shouldBeTrue()
        redisson.getBucket<String>(bucketName3).deleteAsync().coAwait().shouldBeFalse()
    }

    @Test
    fun `redis config set`() {

    }

    /**
     * Redis의 notify-keyspace-events 설정이 필요함
     *
     * [Redis Keyspace notifications](https://redis.io/docs/latest/develop/use/keyspace-notifications/)
     *
     * Copilot에서는 notify-keyspace-events 설정에 대한 조언
     *
     * Redis의 keyspace notifications를 활성화하려면 Redis 설정 파일을 수정하거나 Redis CLI를 사용하여 설정을 변경해야 합니다.
     *
     * 1. Redis 설정 파일을 수정하는 방법:
     *    Redis 설정 파일(`redis.conf`)을 찾아서 열고, `notify-keyspace-events` 설정을 찾습니다. 이 설정이 주석 처리되어 있을 수 있으므로 주석을 제거하고 원하는 이벤트를 설정합니다. 예를 들어, 모든 이벤트를 활성화하려면 `notify-keyspace-events`를 `A`로 설정합니다.
     *
     *    ```
     *    notify-keyspace-events A
     *    ```
     *
     *    설정 파일을 수정한 후 Redis 서버를 재시작해야 합니다.
     *
     * 2. Redis CLI를 사용하여 설정을 변경하는 방법:
     *    Redis CLI를 열고 `CONFIG SET` 명령을 사용하여 `notify-keyspace-events` 설정을 변경합니다. 예를 들어, 모든 이벤트를 활성화하려면 다음 명령을 실행합니다.
     *
     *    ```bash
     *    redis-cli CONFIG SET notify-keyspace-events A
     *    ```
     *
     * 참고로, `notify-keyspace-events` 설정의 값은 다음 문자들의 조합이며 각 문자는 특정 유형의 이벤트를 나타냅니다:
     *
     * - `K`: keyspace events, 키 공간 이벤트
     * - `E`: keyevent events, 키 이벤트 이벤트
     * - `g`: generic commands (like DEL, EXPIRE, RENAME, ...)
     * - `l`: List commands
     * - `s`: Set commands
     * - `h`: Hash commands
     * - `z`: Sorted set commands
     * - `x`: Expired events (events generated every time a key expires)
     * - `e`: Evicted events (events generated when a key is evicted for maxmemory)
     * - `A`: Alias for g$lshzxe, so that the "AKE" string means all the events.
     */
    @Test
    fun `RBucket에 Listener 추가하기`() = runTest {
        // NOTE: BeforeAll 함수에서 `CONFIG SET notify-keyspace-events AKE` 를 실행합니다.

        val bucket = redisson.getBucket<String>(randomName())

        val added = atomic(0)
        val deleted = atomic(0)

        val listenerId1 = bucket.addListener(SetObjectListener { name ->
            log.debug { "Bucket[$name]'s object is set" }
            added.incrementAndGet()
        })

        val listenerId2 = bucket.addListener(DeletedObjectListener { name ->
            log.debug { "Bucket[$name]'s object is deleted" }
            deleted.incrementAndGet()
        })

        bucket.setAsync("123").coAwait()
        delay(10)
        await until { added.value > 0 }

        added.value shouldBeGreaterThan 0
        deleted.value shouldBeEqualTo 0

        bucket.andDeleteAsync.coAwait() shouldBeEqualTo "123"
        delay(10)
        await until { deleted.value > 0 }

        deleted.value shouldBeGreaterThan 0

        bucket.removeListenerAsync(listenerId1).coAwait()
        bucket.removeListenerAsync(listenerId2).coAwait()

        bucket.deleteAsync().coAwait()
    }
}
