package io.bluetape4k.workshop.cache.redis.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.task.MdcTaskDecorator
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AsyncConfigTest {

    companion object: KLogging()

    @Test
    fun `managed executor rejects new work after bounded context close and lets in-flight work finish`() {
        val context = AnnotationConfigApplicationContext(AsyncConfig::class.java)
        val executor = context.getBean<ExecutorService>(AsyncConfig.CACHE_REDIS_EXECUTOR_BEAN_NAME)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()

        executor.submit {
            started.countDown()
            try {
                release.await()
            } catch (_: InterruptedException) {
                interrupted.set(true)
                Thread.currentThread().interrupt()
            }
        }
        val closer = Executors.newSingleThreadExecutor()

        try {
            started.await(2.seconds).shouldBeTrue()
            val closeFuture = closer.submit { context.close() }
            val closeStarted = System.nanoTime()
            closeFuture.get(1.seconds)
            val closeMillis = (System.nanoTime() - closeStarted).nanos().toMillis()

            closeMillis shouldBeLessThan 2_000L
            executor.isShutdown.shouldBeTrue()
            interrupted.get().shouldBeFalse()

            assertFailsWith<RejectedExecutionException> {
                executor.submit {}
            }
            VirtualThreads.runtimeName().shouldNotBeEmpty()
        } finally {
            release.countDown()
            closer.shutdown()
            closer.awaitTermination(1.seconds).shouldBeTrue()
            if (context.isActive) {
                context.close()
            }
        }

        executor.awaitTermination(1.seconds).shouldBeTrue()
        interrupted.get().shouldBeFalse()
    }

    @Test
    fun `actual Lettuce adapter and delegate beans are destroyed in dependency order`() {
        val recorder = DestructionRecorder()
        val context = AnnotationConfigApplicationContext().apply {
            environment.propertySources.addFirst(
                MapPropertySource(
                    "issue-940",
                    mapOf("spring.data.redis.host" to "127.0.0.1", "spring.data.redis.port" to "6379"),
                ),
            )
            beanFactory.addBeanPostProcessor(recorder)
            register(AsyncConfig::class.java, LettuceRedisCacheConfiguration::class.java)
            refresh()
        }
        val executor = context.getBean<ExecutorService>(AsyncConfig.CACHE_REDIS_EXECUTOR_BEAN_NAME)
        val beanFactory = context.beanFactory

        assertTrue {
            TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME in
                    beanFactory.getDependentBeans(AsyncConfig.CACHE_REDIS_EXECUTOR_BEAN_NAME)
        }
        assertTrue {
            "lettuceConnectionFactory" in
                    beanFactory.getDependentBeans(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
        }

        context.close()

        val lettuceIndex = recorder.destroyed.indexOf("lettuceConnectionFactory")
        val adapterIndex =
            recorder.destroyed.indexOf(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
        val delegateIndex = recorder.destroyed.indexOf(AsyncConfig.CACHE_REDIS_EXECUTOR_BEAN_NAME)

        log.debug { "lettuceIndex=$lettuceIndex" }
        log.debug { "adapterIndex=$adapterIndex" }
        log.debug { "delegateIndex=$delegateIndex" }

        lettuceIndex shouldBeGreaterOrEqualTo 0
        adapterIndex shouldBeGreaterOrEqualTo 0
        delegateIndex shouldBeGreaterOrEqualTo 0

        lettuceIndex shouldBeLessThan adapterIndex
        adapterIndex shouldBeLessThan delegateIndex
        executor.isShutdown.shouldBeTrue()
    }

    @Test
    fun `MDC decorator restores reused worker context after success error and null caller`() {
        MDC.clear()
        val decorator = MdcTaskDecorator()
        val worker = Executors.newSingleThreadExecutor()

        try {
            MDC.put("requestId", "request-1")
            val success = decorator.decorate {
                MDC.get("requestId") shouldBeEqualTo "request-1"
                MDC.put("taskOnly", "dirty")
            }
            val afterSuccess = AtomicReference<Map<String, String>?>()
            worker.submit {
                MDC.put("worker", "baseline")
                success.run()
                afterSuccess.set(MDC.getCopyOfContextMap())
                MDC.clear()
            }.get(2.seconds)
            afterSuccess.get() shouldBeEqualTo mapOf("worker" to "baseline")

            MDC.clear()
            val failure = decorator.decorate {
                MDC.getCopyOfContextMap().isNullOrEmpty().shouldBeTrue()
                MDC.put("taskOnly", "dirty")
                error("expected")
            }
            val afterFailure = AtomicReference<Map<String, String>?>()
            worker.submit {
                MDC.put("worker", "baseline")
                assertFailsWith<IllegalStateException> {
                    failure.run()
                }
                afterFailure.set(MDC.getCopyOfContextMap())
                MDC.clear()
            }.get(1.seconds)
            afterFailure.get() shouldBeEqualTo mapOf("worker" to "baseline")
        } finally {
            MDC.clear()
            worker.shutdown()
            worker.awaitTermination(1.seconds).shouldBeTrue()
        }
    }

    private class DestructionRecorder: DestructionAwareBeanPostProcessor {
        val destroyed = ConcurrentLinkedDeque<String>()

        override fun postProcessBeforeDestruction(bean: Any, beanName: String) {
            if (beanName in TARGETS) {
                destroyed += beanName
            }
        }

        override fun requiresDestruction(bean: Any): Boolean = true

        companion object {
            private val TARGETS = setOf(
                "lettuceConnectionFactory",
                TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME,
                AsyncConfig.CACHE_REDIS_EXECUTOR_BEAN_NAME,
            )
        }
    }
}
