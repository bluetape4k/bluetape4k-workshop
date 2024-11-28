package io.bluetape4k.workshop.virtualthread.undertow.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class VirtualThreadConfig {

    @Bean
    fun taskExecutor(): ExecutorService {
        return Executors.newVirtualThreadPerTaskExecutor()
    }
}
