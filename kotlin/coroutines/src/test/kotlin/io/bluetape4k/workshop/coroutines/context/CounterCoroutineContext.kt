package io.bluetape4k.workshop.coroutines.context

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.debug
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Counter 를 가지는 [CoroutineContext]
 *
 * @property name coroutine context name
 */
class CounterCoroutineContext(private val name: String): AbstractCoroutineContextElement(Key) {

    companion object Key: CoroutineContext.Key<CounterCoroutineContext> {
        private val log = KotlinLogging.logger { }
    }

    private val nextNumber = AtomicLong(0L)

    val number: Long get() = nextNumber.get()

    fun printNextCount() {
        log.debug { this }
        nextNumber.incrementAndGet()
    }

    override fun toString(): String {
        return "CounterCoroutineContext(name='$name', number=$number)"
    }
}

/**
 * Current CoroutineScope 에서 [CounterCoroutineContext]를 찾아서 [CounterCoroutineContext.number]를 출력합니다.
 */
internal suspend fun printNextCount() {
    coroutineContext[CounterCoroutineContext]?.printNextCount()
}
