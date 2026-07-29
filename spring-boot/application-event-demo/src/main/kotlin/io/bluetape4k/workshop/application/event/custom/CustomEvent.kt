package io.bluetape4k.workshop.application.event.custom

import io.bluetape4k.support.requireNotBlank
import org.springframework.context.ApplicationEvent
import java.io.Serializable

/**
 * direct controller flow 가 publish 하는 application event 입니다.
 *
 * listener 가 메시지를 user-visible event content 로 log 하고 처리하므로
 * payload 는 비어 있지 않아야 합니다.
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
