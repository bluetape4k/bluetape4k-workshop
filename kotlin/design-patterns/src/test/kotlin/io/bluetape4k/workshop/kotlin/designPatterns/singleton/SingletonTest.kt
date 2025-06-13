package io.bluetape4k.workshop.kotlin.designPatterns.singleton


import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.utils.Runtimex
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/**
 * Singleton Instance를 생성하는 테스트를 제공합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)  // 메소드 별로 싱글턴 인스턴스를 생성해야 한다.
@Execution(ExecutionMode.CONCURRENT)
abstract class AbstractSingletonTest<T>(private val singletonInstanceMethod: () -> T) {

    companion object: KLoggingChannel()

    @Test
    fun `여러번 호출해도 동일한 Thread 안에서 동일한 객체를 반환한다`() {
        log.debug { "Create singleton instance" }

        val instance1 = singletonInstanceMethod()
        val instance2 = singletonInstanceMethod()
        val instance3 = singletonInstanceMethod()

        assertTrue { instance1 === instance2 }
        assertTrue { instance2 === instance3 }
        assertTrue { instance1 === instance3 }
    }

    @Test
    fun `멀티 스레드 환경에서 싱글턴 객체를 생성한다`() {
        val instances = CopyOnWriteArrayList<T>()

        MultithreadingTester()
            .numThreads(Runtimex.availableProcessors * 2)
            .roundsPerThread(2)
            .add {
                instances.add(singletonInstanceMethod())
            }
            .run()

        val expectedInstance = singletonInstanceMethod()
        instances.all { it === expectedInstance }.shouldBeTrue()
    }

    @Test
    fun `Virtual 스레드 환경에서 싱글턴 객체를 생성한다`() {
        val instances = CopyOnWriteArrayList<T>()

        StructuredTaskScopeTester()
            .roundsPerTask(Runtimex.availableProcessors * 4)
            .add {
                instances.add(singletonInstanceMethod())
            }
            .run()

        val expectedInstance = singletonInstanceMethod()
        instances.all { it === expectedInstance }.shouldBeTrue()
    }

    @Test
    fun `멀티 Job 에서 여러번 호출해도 동일한 객체를 반환한다`() = runTest {
        val instances = CopyOnWriteArrayList<T>()

        SuspendedJobTester()
            .numThreads(Runtimex.availableProcessors * 2)
            .roundsPerJob(Runtimex.availableProcessors * 2 * 2)
            .add {
                instances.add(singletonInstanceMethod())
            }
            .run()

        val expectedInstance = singletonInstanceMethod()
        instances.all { it === expectedInstance }.shouldBeTrue()
    }
}


class EnumIvoryTowerTest: AbstractSingletonTest<EnumIvoryTower>({
    EnumIvoryTower.INSTANCE
})

class IvoryTowerTest: AbstractSingletonTest<IvoryTower>({
    IvoryTower.getInstance()
})

class InitializingOnDemandHolderIdiomTest: AbstractSingletonTest<InitializingOnDemandHolderIdiom>({
    InitializingOnDemandHolderIdiom.getInstance()
})

class ThreadSafeLazyLoadedIvoryTowerTest: AbstractSingletonTest<ThreadSafeLazyLoadedIvoryTower>({
    ThreadSafeLazyLoadedIvoryTower.getInstance()
})

class ThreadSafeDoubleCheckLockingTest: AbstractSingletonTest<ThreadSafeDoubleCheckLocking>({
    ThreadSafeDoubleCheckLocking.getInstance()
})
