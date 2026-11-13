package io.bluetape4k.workshop.commerce.order.config

import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import kotlin.time.toKotlinDuration

@Configuration(proxyBeanMethods = false)
internal class OrderLifecycleConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean(destroyMethod = "")
    fun orderLifecycleExecutor(): ExecutorService =
        VirtualThreads.executorService().also {
            log.info { "order_lifecycle_executor_created virtualThreads=true" }
        }

    @Bean
    fun orderLifecycleExecutorShutdown(executor: ExecutorService) = ExecutorShutdown(executor)

    companion object: KLogging()
}

internal class ExecutorShutdown(
    private val executor: ExecutorService,
    private val timeout: Duration = 10.seconds(),
): AutoCloseable {

    companion object: KLogging()

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeout.toKotlinDuration())) {
                log.warn { "order_lifecycle_executor_forced_shutdown timeoutMillis=${timeout.toMillis()}" }
                executor.shutdownNow()
            }
        } catch (interrupted: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        log.info { "order_lifecycle_executor_shutdown" }
    }
}
