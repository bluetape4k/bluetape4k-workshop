package io.bluetape4k.workshop.application.event.custom

import io.bluetape4k.support.requireNotBlank
import org.springframework.context.ApplicationEvent
import java.io.Serializable

/**
 * Application event published by the direct controller flow.
 *
 * The payload must be non-blank because listeners log and process the message as
 * user-visible event content.
 */
data class CustomEvent(
    private val src: Any,
    val message: String,
) : ApplicationEvent(src), Serializable {

    init {
        message.requireNotBlank("message")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    override fun toString(): String =
        "CustomEvent(src=${src.javaClass.name}, message=$message)"
}
