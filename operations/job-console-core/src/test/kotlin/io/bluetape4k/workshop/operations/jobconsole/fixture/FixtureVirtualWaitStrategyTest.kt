package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.hours
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/** 가상 대기 fixture가 mutation과 wakeup 사이의 race를 놓치지 않는지 검증한다. */
/** 가상 대기 fixture가 mutation과 wakeup 사이의 race를 놓치지 않는지 검증한다. */
class FixtureVirtualWaitStrategyTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "mutation observed before await prevents a lost virtual wakeup" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `mutation observed before await prevents a lost virtual wakeup`() {
        val strategy = FixtureVirtualWaitStrategy { 0L }
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val waiter = executor.submit {
                strategy.beginWaiter()
                ready.countDown()
                release.await(1.seconds).shouldBeTrue()
                strategy.await(1.hours())
                strategy.endWaiter()
            }

            ready.await(1.seconds).shouldBeTrue()
            release.countDown()
            strategy.signal()
            waiter.get(1.seconds)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(1.seconds).shouldBeTrue()
        }
    }
}
