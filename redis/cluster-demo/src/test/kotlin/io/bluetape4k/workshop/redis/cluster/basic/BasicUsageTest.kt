package io.bluetape4k.workshop.redis.cluster.basic

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContainSame
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.redis.cluster.AbstractRedisClusterTest
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.RedisOperations
import java.time.Duration

class BasicUsageTest(
    @param:Autowired private val operations: RedisOperations<String, String>,
) : AbstractRedisClusterTest() {

    companion object : KLoggingChannel()

    @BeforeAll
    fun beforeAll() {
        await atMost Duration.ofSeconds(3) untilAsserted {
            operations.execute { conn -> conn.serverCommands().dbSize() }.shouldNotBeNull()
        }
    }

    @BeforeEach
    fun beforeEach() {
        operations.execute { conn ->
            conn.flushMasterDatabases()
        }
    }

    /**
     * 단일 node 와 slot 에서 실행되는 operation 입니다.
     *
     * ```
     * -> SLOT 5798 served by 127.0.0.1:30002
     * ```
     */
    @Test
    fun `single slot operation`() {
        val key = "name"
        val value = randomValue()

        with(operations.opsForValue()) {
            set(key, value) // slot 5798
            get(key) shouldBeEqualTo value
        }
    }

    /**
     * 여러 node 와 slot 에 걸쳐 실행되는 operation 입니다.
     *
     * ```
     * -> SLOT 5798 served by 127.0.0.1:30002
     * -> SLOT 14594 served by 127.0.0.1:30003
     * ```
     */
    @Test
    fun `multi slot operations`() {
        val key1 = "name"
        val key2 = "nickname"
        val value1 = randomValue()
        val value2 = randomValue()

        with(operations.opsForValue()) {
            set(key1, value1) // slot 5798
            set(key2, value2) // slot 14594

            multiGet(listOf(key1, key2)).shouldNotBeNull() shouldContainSame listOf(value1, value2)
        }
    }

    /**
     * pinned slot key 때문에 단일 node 와 slot 에서 실행되는 operation 입니다.
     *
     * ```
     * -> SLOT 5798 served by 127.0.0.1:30002
     * ```
     */
    @Test
    fun `fixed slot operation`() {
        val key1 = "{user}.name"
        val key2 = "{user}.nickname"
        val value1 = randomValue()
        val value2 = randomValue()

        with(operations.opsForValue()) {
            set(key1, value1) // slot 5798
            set(key2, value2) // slot 5798

            multiGet(listOf(key1, key2)).shouldNotBeNull() shouldContainSame listOf(value1, value2)
        }
    }

    /**
     * 누적 result 를 조회하기 위해 cluster 전체에서 실행되는 operation 입니다.
     *
     * ```
     * -> KEY age served by 127.0.0.1:30001
     * -> KEY name served by 127.0.0.1:30002
     * -> KEY nickname served by 127.0.0.1:30003
     * ```
     */
    @Test
    fun `multi node operations`() {
        val key1 = "name"
        val key2 = "nickname"
        val key3 = "age"
        val value1 = randomValue()
        val value2 = randomValue()
        val value3 = randomValue()

        with(operations.opsForValue()) {
            set(key1, value1) // slot 5798
            set(key2, value2) // slot 14594
            set(key3, value3) // slot 741

            multiGet(listOf(key1, key2, key3)).shouldNotBeNull() shouldContainSame listOf(value1, value2, value3)
        }

        // 참고: lettuce-core 7.x 에서는 더 이상 동작하지 않습니다.
        // operations.keys("*").shouldNotBeNull() shouldContainAll setOf(key1, key2, key3)
    }
}
