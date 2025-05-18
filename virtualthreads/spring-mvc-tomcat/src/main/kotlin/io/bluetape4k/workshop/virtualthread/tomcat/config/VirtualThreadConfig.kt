package io.bluetape4k.workshop.virtualthread.tomcat.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class VirtualThreadConfig {

    @Bean
    fun taskExecutor(): ExecutorService {
        val factory = Thread.ofVirtual().name("vt-executor-", 0).factory()
        return Executors.newThreadPerTaskExecutor(factory)

        // return Executors.newVirtualThreadPerTaskExecutor(factory)
    }
}
