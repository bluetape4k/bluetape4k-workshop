package io.bluetape4k.workshop.redisson.objects

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test

/**
 * Id generator examples
 *
 * Redis 기반 Java ID 생성기 RIdGenerator는 고유 번호를 생성하지만 단조롭게 증가하지는 않습니다.
 * 첫 번째 요청 시 ID 번호 배치를 할당하고 소진될 때까지 Java 측에 캐시합니다.
 * 이 접근 방식을 사용하면 `RAtomicLong`보다 빠르게 아이디를 생성할 수 있습니다.
 *
 * 참고:
 * - [Id generator](https://github.com/redisson/redisson/wiki/6.-distributed-objects#614-id-generator)
 */
class IdGeneratorExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `generate identifier`() {
        val generator = redisson.getIdGenerator(randomName())

        // 초기값=42, 할당 갯수 5,000개를 미리 할당합니다. (이미 초기화 되어 있다면, False를 반환하고 무시됨)
        generator.tryInit(42, 5_000)

        val ids = List(100) { generator.nextId() }
        ids.distinct() shouldHaveSize 100
    }


    @Test
    fun `generate identifier asynchronous`() = runTest {
        val generator = redisson.getIdGenerator(randomName())

        // 초기값=42, 할당 갯수 5,000개를 미리 할당합니다. (이미 초기화 되어 있다면, False를 반환하고 무시됨)
        generator.tryInitAsync(42, 5_000).await()

        val ids = List(100) { generator.nextIdAsync().await() }
        ids.distinct() shouldHaveSize 100
    }
}
