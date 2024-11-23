package io.bluetape4k.workshop.virtualthread.tomcat.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.utils.Runtimex
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

    /**
     * Virtual Thread를 사용하는 Scheduled Thread Pool을 사용하도록 합니다.
     */
    @Bean
    fun taskScheduler(): TaskScheduler {
        val factory = Thread.ofVirtual().name("virtual-task-").factory()
        val poolSize = Runtimex.availableProcessors
        val executor = Executors.newScheduledThreadPool(poolSize, factory)

        log.info { "TaskScheduler with VirtualThread created. poolSize=$poolSize" }

        return ConcurrentTaskScheduler(executor)
    }
}
