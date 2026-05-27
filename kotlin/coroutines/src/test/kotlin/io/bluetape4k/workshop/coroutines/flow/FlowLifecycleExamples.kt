package io.bluetape4k.workshop.coroutines.flow

import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext

class FlowLifecycleExamples {

    companion object: KLoggingChannel()

    @Test
    fun `onEach - react on flowing value`() = runTest {
        var sum = 0
        flowOf(1, 2, 3, 4).log(1)
            .onEach { sum += it }
            .collect()

        sum shouldBeEqualTo 10

        flowOf(1, 2).log(2)
            .onEach {
                delay(1000)
            }
            .collect {
                log.debug { "collect $it" }
            }
    }

    @Test
    fun `onStart - starting flow`() = runTest {
        flowOf(1, 2)
            .onEach { delay(1000) }
            .onStart { log.debug { "Starting" } }  // 한번만 호출된다
            .collect { log.debug { "Collect" } }
    }

    @Test
    fun `onCompletion - call on complete flow`() = runTest {
        flowOf(1, 2)
            .onEach { delay(1000) }
            .onStart { log.debug { "Starting" } }  // 한번만 호출된다
            .onCompletion { log.debug { "Completed" } }
            .collect { log.debug { "Collect" } }
    }

    @Test
    fun `onEmpty - call on not existing element`() = runTest {
        val emitted = flow<List<Int>> { delay(1000) }
            .onEmpty { emit(emptyList()) }
            .toList()

        emitted shouldBeEqualTo listOf(emptyList())

        // Same action
        val fallback = flow<List<Int>> { delay(1000) }
            .onEmpty { emit(emptyList()) }
            .toList()

        fallback shouldBeEqualTo listOf(emptyList())
    }

    @Test
    fun `catch - catch on exception`() = runTest {
        val flow = flow {
            emit(1)
            emit(2)
            throw RuntimeException("Boom!")
        }

        val values = mutableListOf<Int>()
        val errors = mutableListOf<String?>()

        flow.onEach { values += it }
            .catch { errors += it.message }   // cache는 예외만 받는다. catch 는 flow 가 종료되기 전에 호출된다
            .collect()

        values shouldBeEqualTo listOf(1, 2)
        errors shouldBeEqualTo listOf("Boom!")
    }


    @Nested
    inner class FlowOnExample {

        private fun usersFlow(): Flow<String> = flow {
            val coroutineName = currentCoroutineContext()[CoroutineName]?.name
            repeat(2) {
                emit("User$it in $coroutineName")
            }
        }

        @Test
        fun `flowOn - flow 작업 시 사용할 context 를 지정한다`() = runTest {
            val users = usersFlow()

            // collect 작업을 Name1 에서 수행
            val name1Users = withContext(CoroutineName("Coroutine Name1")) {
                users.toList()
            }
            name1Users shouldBeEqualTo listOf("User0 in Coroutine Name1", "User1 in Coroutine Name1")

            // collect 작업을 Name20 에서 수행
            val name2Users = withContext(CoroutineName("Coroutine Name2")) {
                users.toList()
            }
            name2Users shouldBeEqualTo listOf("User0 in Coroutine Name2", "User1 in Coroutine Name2")

            // collect 작업을 Name3 에서 수행
            val name3Users = users
                .flowOn(CoroutineName("Coroutine Name3"))
                .toList()

            name3Users shouldBeEqualTo listOf("User0 in Coroutine Name3", "User1 in Coroutine Name3")
        }

        private suspend fun present(place: String, message: String): String {
            val name = coroutineContext[CoroutineName]?.name
            return "[$name] $message on $place"
        }

        private fun messageFlow(trace: MutableList<String>): Flow<String> = flow {
            trace += present("flow builder", "Message")
            emit("Message")
        }

        @Test
        fun `flowOn with different context`() = runTest {
            val trace = CopyOnWriteArrayList<String>()
            val messages = messageFlow(trace)

            // NOTE: flowOn will work only for the functions upstream the flow.
            //
            withContext(CoroutineName("N1")) {
                messages
                    .flowOn(CoroutineName("N3"))            // N3 Message on flow builder
                    .onEach { trace += present("onEach", it) }       // N2 Message on onEach
                    .flowOn(CoroutineName("N2"))            //
                    .collect { trace += present("collect", it) }     // N1 Message on collect
            }

            trace.toList() shouldBeEqualTo listOf(
                "[N3] Message on flow builder",
                "[N2] Message on onEach",
                "[N1] Message on collect",
            )
        }
    }

    /**
     * `launchIn` 은 지정된 coroutine context에서 `collect` 를 수행하게 합니다.
     */
    @Test
    fun `launchIn - lauch to start flow processing on another coroutine`() =
        runTest(CoroutineName("test")) {
            flowOf("User1", "User2")
                .onStart { log.debug { "Users:" } }
                .flowOn(CoroutineName("users"))
                .onEach { log.debug { it } }
                .launchIn(this)
        }
}
