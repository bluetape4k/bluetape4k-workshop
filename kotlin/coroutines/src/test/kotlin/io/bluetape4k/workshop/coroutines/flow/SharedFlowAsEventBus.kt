package io.bluetape4k.workshop.coroutines.flow

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SharedFlowAsEventBus {

    companion object: KLoggingChannel()

    /**
     * 여러 리스너에 이벤트를 브로드캐스트하기 위해 shared flow 를 사용하는 event bus 구현입니다.
     */
    class EventBus<T> {
        // 여러 리스너에 이벤트를 브로드캐스트할 shared flow 입니다.
        private val _events = MutableSharedFlow<T>(replay = 0, extraBufferCapacity = 64)

        // 외부 구독자가 이벤트를 수신할 때 사용하는 공개 flow 입니다.
        val events: Flow<T> = _events.asSharedFlow()

        suspend fun sendEvent(event: T) {
            _events.emit(event)
        }
    }

    sealed class Event {
        data object EventA: Event()
        data object EventB: Event()
        data class EventC(val value: Int): Event()
    }

    class EventListener(
        private val name: String,
        private val eventBus: EventBus<Event>,
        private val scope: CoroutineScope,
    ) {

        companion object: KLogging()

        init {
            // onEach 연산자로 events flow 를 구독합니다.
            eventBus.events
                .onEach { event ->
                    when (event) {
                        is Event.EventA -> handleEventA(
                            event
                        )

                        is Event.EventB -> handleEventB(
                            event
                        )

                        is Event.EventC -> handleEventC(
                            event
                        )
                    }
                }
                // 전달된 coroutine scope 안에서 이벤트 리스너를 실행합니다.
                // scope 가 더 이상 유효하지 않으면 구독을 취소할 수 있습니다.
                // `scope.coroutineContext.cancelChildren()` 을 호출하면 중단됩니다.
                .launchIn(scope)
        }

        private fun handleEventA(event: Event.EventA) {
            log.debug { "$name: EventA received. event=$event" }
        }

        private fun handleEventB(event: Event.EventB) {
            log.debug { "$name: EventB received. event=$event" }

        }

        private fun handleEventC(event: Event.EventC) {
            log.debug { "$name: EventC received. event=$event" }
        }
    }

    @Suppress("UNUSED_VARIABLE")
    @Test
    fun `event bus example`() = runTest {

        val eventBus =
            EventBus<Event>()

        // 이벤트 리스너를 생성합니다.
        val listener1 = EventListener(
            "#1",
            eventBus,
            this
        )
        val listener2 = EventListener(
            "#2",
            eventBus,
            this
        )

        val job = launch(Dispatchers.Default) {
            // 이벤트를 발행합니다.
            delay(100)
            eventBus.sendEvent(Event.EventA)
            delay(100)
            eventBus.sendEvent(Event.EventB)
            delay(100)
            eventBus.sendEvent(
                Event.EventC(
                    42
                )
            )
        }

        job.join()
        // Wait for the listeners to process the events
        delay(500)

        coroutineContext.cancelChildren()
    }
}
