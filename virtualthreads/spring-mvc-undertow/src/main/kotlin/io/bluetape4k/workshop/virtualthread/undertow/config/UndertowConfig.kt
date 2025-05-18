package io.bluetape4k.workshop.virtualthread.undertow.config

import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class UndertowConfig {

    /**
     * Undertow 에
     */
    @Bean
    fun undertowVirtualThreadConfig(): WebServerFactoryCustomizer<UndertowServletWebServerFactory> {
        return WebServerFactoryCustomizer { factory ->
            factory.setIoThreads(64)
            factory.setWorkerThreads(1024)
            factory.addBuilderCustomizers(
                { builder ->
                    builder.setWorkerThreads(256)
                    builder.setIoThreads(16)
                    builder.setBufferSize(1024)
                    builder.setDirectBuffers(true)
                }
            )
        }
    }
}
