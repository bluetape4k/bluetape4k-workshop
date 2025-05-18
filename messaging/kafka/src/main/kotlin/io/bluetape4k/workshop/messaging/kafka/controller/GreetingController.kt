package io.bluetape4k.workshop.messaging.kafka.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.messaging.kafka.KafkaTopics
import io.bluetape4k.workshop.messaging.kafka.listener.LoggerMessageHandler
import io.bluetape4k.workshop.messaging.kafka.model.GreetingRequest
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/greeting")
class GreetingController: CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {

    companion object: KLoggingChannel()

    @Autowired
    private val kafkaTemplate: KafkaTemplate<String, Any?> = uninitialized()

    @Autowired
    private val loggerMessageHandler: LoggerMessageHandler = uninitialized()


    @GetMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun sendGreetingMessage(
        @RequestParam(name = "message", defaultValue = "Hello world") message: String,
    ): String {
        kafkaTemplate.send(KafkaTopics.TOPIC_SIMPLE, message)
        return message
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun sendGreetingRequest(@RequestBody greeting: GreetingRequest) {
        log.debug { "Send greeting: $greeting" }
        kafkaTemplate.send(KafkaTopics.TOPIC_GREETING, greeting)
    }

    @PreDestroy
    private fun destroy() {
        coroutineContext.cancel()
    }
}
