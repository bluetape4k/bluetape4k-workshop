package io.bluetape4k.workshop.application.event.custom.listeners

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.application.event.custom.CustomEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.mono
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class AnnotatedCoroutineCustomEventListener {

    companion object : KLoggingChannel()

    /**
     * coroutine 친화적인 Reactor bridge 에서 [CustomEvent] 를 처리합니다.
     *
     * compiler 가 continuation parameter 를 추가하므로 suspend function 을 `@EventListener` method 로
     * 직접 사용할 수 없습니다. suspend 작업을 `mono { ... }` 로 감싸면 listener 가 Spring event 와
     * 호환됩니다.
     */
    @EventListener(classes = [CustomEvent::class])
    fun handleEvent(event: CustomEvent) = mono(Dispatchers.IO) {
        doHandleEvent(event)
    }

    private suspend fun doHandleEvent(event: CustomEvent) {
        log.debug { "Handle custom event by @EventListener with coroutines. $event" }
        delay(100)
    }
}
