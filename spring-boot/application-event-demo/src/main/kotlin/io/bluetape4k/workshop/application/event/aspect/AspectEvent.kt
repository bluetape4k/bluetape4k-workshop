package io.bluetape4k.workshop.application.event.aspect

import org.springframework.context.ApplicationEvent
import java.io.Serializable

/**
 * [AspectEventEmitter] advice 가 발생시키는 application event 입니다.
 */
data class AspectEvent(
    val src: Any,
    val message: Any,
) : ApplicationEvent(src), Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    override fun toString(): String =
        "AspectEvent(src=${src.javaClass.name}, message=$message)"
}
