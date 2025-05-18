package io.bluetape4k.workshop.cache.caffeine.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler
import java.util.concurrent.Executors

/**
 * CountryPrefetcher 가 주기적으로 동작하도록 하기 위해 사용합니다.
 */
@Configuration
@EnableScheduling
class SchedulingConfig {

    companion object: KLoggingChannel()

    /**
     * Virtual Thread를 사용하는 Scheduled Thread Pool을 사용하도록 합니다.
     */
    @Bean
    fun taskScheduler(): TaskScheduler {
        return ConcurrentTaskScheduler(
            Executors.newScheduledThreadPool(
                1,
                Thread.ofVirtual().name("virtual-task-", 0).factory()
            )
        )
    }
}
