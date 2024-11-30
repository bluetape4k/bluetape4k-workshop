package io.bluetape4k.workshop.messaging.kafka.listener

interface KafkaMessageHandler<M, R> {

    fun handle(message: M): R

}
