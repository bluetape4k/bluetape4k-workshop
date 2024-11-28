package io.bluetape4k.workshop.exposed.virtualthread.config.virtualthread

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler
import java.util.concurrent.Executors

@Configuration
@EnableScheduling
class SchedulingConfig {

    /**
     * Virtual Thread를 사용하는 Scheduled Thread Pool을 사용하도록 합니다.
     */
    @Bean
    fun taskScheduler(): TaskScheduler {
        return ConcurrentTaskScheduler(
            Executors.newScheduledThreadPool(4, Thread.ofVirtual().name("virtual-task-", 0).factory())
        )
    }
}
