package io.bluetape4k.workshop.application.event.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.utils.Runtimex
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ReactorResourceFactory
import reactor.netty.http.server.HttpServer
import reactor.netty.resources.ConnectionProvider
import reactor.netty.resources.LoopResources
import java.time.Duration

/**
 * Provides Netty settings for the WebFlux application.
 *
 * Tune these options when the example needs higher connection concurrency.
 */
@Configuration(proxyBeanMethods = false)
class NettyConfig {

    companion object : KLoggingChannel()

    @Bean
    fun nettyReactiveWebServerFactory(): NettyReactiveWebServerFactory {
        return NettyReactiveWebServerFactory().apply {
            addServerCustomizers(EventLoopNettyCustomer())
        }
    }

    class EventLoopNettyCustomer : NettyServerCustomizer {
        override fun apply(httpServer: HttpServer): HttpServer {
            return httpServer
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.SO_BACKLOG, 1000)
                .doOnConnection { conn ->
                    conn.addHandlerLast(ReadTimeoutHandler(10))
                    conn.addHandlerLast(WriteTimeoutHandler(10))
                }
        }
    }

    @Bean
    fun reactorResourceFactory(): ReactorResourceFactory {
        return ReactorResourceFactory().apply {
            isUseGlobalResources = false
            connectionProvider = ConnectionProvider.builder("http")
                .maxConnections(10_000)
                .maxIdleTime(Duration.ofSeconds(10))
                .build()

            loopResources = LoopResources.create(
                "event-loop",
                4,
                maxOf(Runtimex.availableProcessors * 2, 8),
                true
            )
        }
    }
}
