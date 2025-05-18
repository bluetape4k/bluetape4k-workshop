package io.bluetape4k.workshop.virtualthread.tomcat.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.apache.coyote.ProtocolHandler
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors

/**
 * Tomcat ProtocolHandler의 executor를 Virtual Thread를 사용하는 Executor를 사용하도록 설정
 */
@Configuration
class TomcatConfig {

    companion object: KLogging()

    /**
     * Tomcat ProtocolHandler의 executor 를 Virtual Thread 를 사용하는 Executor를 사용하도록 설정
     */
    @Bean
    fun protocolHandlerVirtualThreadExecutorCustomizer(): TomcatProtocolHandlerCustomizer<ProtocolHandler> {
        log.info { "Tomcat ProtocolHandler with VirtualThread created." }

        return TomcatProtocolHandlerCustomizer<ProtocolHandler> { protocolHandler ->
            protocolHandler.executor = Executors.newVirtualThreadPerTaskExecutor()

            // 이렇게 해도 thread name은 안 바뀝니다. (Tomcat 의 carrier thread name 이 출력됩니다)
            // val factory = Thread.ofVirtual().name("vt-executor-", 0).factory()
            // protocolHandler.executor = Executors.newThreadPerTaskExecutor(factory)
        }
    }
}
