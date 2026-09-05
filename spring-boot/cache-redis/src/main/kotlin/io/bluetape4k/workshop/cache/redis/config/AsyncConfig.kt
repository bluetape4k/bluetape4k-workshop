package io.bluetape4k.workshop.cache.redis.config

import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.core.task.TaskDecorator
import org.springframework.core.task.support.TaskExecutorAdapter
import org.springframework.scheduling.annotation.EnableAsync
import java.util.concurrent.ExecutorService

/**
 * `@Async` 메서드와 Lettuce가 공유하는 VirtualThreads executor의 생성·종료 설정입니다.
 *
 * @see [org.springframework.scheduling.annotation.Async]
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {

    companion object: KLoggingChannel() {
        const val CACHE_REDIS_EXECUTOR_BEAN_NAME = "cacheRedisVirtualThreadExecutor"
    }

    @Bean(name = [CACHE_REDIS_EXECUTOR_BEAN_NAME], destroyMethod = "shutdown")
    fun cacheRedisVirtualThreadExecutor(): ExecutorService = VirtualThreads.executorService().also {
        log.info { "Managed async executor created by VirtualThreads runtime=${VirtualThreads.runtimeName()}." }
    }

    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    @Primary
    fun asyncTaskExecutor(
        @Qualifier(CACHE_REDIS_EXECUTOR_BEAN_NAME) executorService: ExecutorService,
    ): AsyncTaskExecutor {
        return TaskExecutorAdapter(executorService).apply {
            setTaskDecorator(LoggingTaskDecorator())
        }
    }

    class LoggingTaskDecorator: TaskDecorator {
        override fun decorate(task: Runnable): Runnable {
            val callerThreadContext = MDC.getCopyOfContextMap()
            return kotlinx.coroutines.Runnable {
                val workerThreadContext = MDC.getCopyOfContextMap()
                try {
                    if (callerThreadContext == null) {
                        MDC.clear()
                    } else {
                        MDC.setContextMap(callerThreadContext)
                    }
                    task.run()
                } finally {
                    if (workerThreadContext == null) {
                        MDC.clear()
                    } else {
                        MDC.setContextMap(workerThreadContext)
                    }
                }
            }
        }
    }
}
