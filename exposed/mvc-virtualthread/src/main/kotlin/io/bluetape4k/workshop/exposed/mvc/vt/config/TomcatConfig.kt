package io.bluetape4k.workshop.exposed.mvc.vt.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.utils.ShutdownQueue
import org.apache.coyote.ProtocolHandler
import org.springframework.boot.tomcat.TomcatProtocolHandlerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration(proxyBeanMethods = false)
class TomcatConfig {

    companion object : KLogging()

    /** Shared virtual thread executor — injected into all repos/services */
    @Bean(destroyMethod = "shutdown")
    fun virtualThreadExecutor(): ExecutorService =
        Executors.newVirtualThreadPerTaskExecutor().apply {
            ShutdownQueue.register(this)
        }

    @Bean
    fun tomcatProtocolHandlerCustomizer(executor: ExecutorService): TomcatProtocolHandlerCustomizer<*> =
        TomcatProtocolHandlerCustomizer<ProtocolHandler> { handler ->
            handler.executor = executor
        }
}
