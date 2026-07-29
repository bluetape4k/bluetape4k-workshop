package io.bluetape4k.workshop.stomp.websocket.config

import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {

    companion object : KLogging()

    /**
     * message broker option 을 설정합니다.
     */
    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic")
        registry.setApplicationDestinationPrefixes("/app")
    }

    /**
     * 각 STOMP endpoint 를 특정 URL 에 mapping 하고,
     * 필요하면 SockJS fallback option 을 활성화하고 설정합니다.
     */
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/gs-guide-websocket").withSockJS()
    }
}
