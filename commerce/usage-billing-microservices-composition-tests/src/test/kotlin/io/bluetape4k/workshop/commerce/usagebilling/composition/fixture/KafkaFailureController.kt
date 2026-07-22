package io.bluetape4k.workshop.commerce.usagebilling.composition.fixture

class KafkaFailureController {
    private val blockedTopics = mutableSetOf<String>()

    fun block(topic: String) {
        blockedTopics += topic
    }

    fun unblock(topic: String) {
        blockedTopics -= topic
    }

    fun isBlocked(topic: String): Boolean = topic in blockedTopics
}
