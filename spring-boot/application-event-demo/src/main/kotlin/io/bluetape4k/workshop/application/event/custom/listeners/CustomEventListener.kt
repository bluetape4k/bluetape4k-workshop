package io.bluetape4k.workshop.application.event.custom.listeners

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.application.event.custom.CustomEvent
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.context.ApplicationListener
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

@Component
class CustomEventListener : ApplicationListener<CustomEvent>, DisposableBean {

    companion object : KLoggingChannel()

    private val listenerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("custom-event-listener")
    )

    override fun onApplicationEvent(event: CustomEvent) {
        listenerScope.launch {
            saveEvent(event)
        }
    }

    override fun destroy() {
        listenerScope.cancel()
    }

    suspend fun saveEvent(event: CustomEvent) {
        log.debug { "Handle custom event. $event" }
        delay(100)
    }
}
