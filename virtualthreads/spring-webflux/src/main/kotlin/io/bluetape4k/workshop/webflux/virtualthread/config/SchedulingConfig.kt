package io.bluetape4k.workshop.webflux.virtualthread.config

import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler
import java.util.concurrent.Executors

@Configuration
@EnableScheduling
class SchedulingConfig {

    companion object: KLogging()

    private val virtualThreadFactory = Thread.ofVirtual()
        .inheritInheritableThreadLocals(true)
        .name("vt-executor-", 0)
        .factory()

    /**
     * Virtual Thread를 사용하는 Scheduled Thread Pool을 사용하도록 합니다.
     */
    @Bean
    fun taskScheduler(): TaskScheduler {
        val executor = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() * 4,
            virtualThreadFactory
        )
        return ConcurrentTaskScheduler(executor)
    }
}
