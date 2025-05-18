package io.bluetape4k.workshop.exposed.config

import org.apache.coyote.ProtocolHandler
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer
import org.springframework.context.annotation.Bean
import java.util.concurrent.Executors

/**
 * Tomcat ProtocolHandler의 executor를 Virtual Thread를 사용하는 Executor를 사용하도록 설정
 */
class TomcatConfig {

    /**
     * Tomcat ProtocolHandler의 executor 를 Virtual Thread 를 사용하는 Executor를 사용하도록 설정
     */
    @Bean
    fun protocolHandlerVirtualThreadExecutorCustomizer(): TomcatProtocolHandlerCustomizer<*> {
        return TomcatProtocolHandlerCustomizer<ProtocolHandler> { protocolHandler ->
            protocolHandler.executor = Executors.newVirtualThreadPerTaskExecutor()
        }
    }
}
