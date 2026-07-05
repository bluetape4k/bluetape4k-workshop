package io.bluetape4k.workshop.virtualthread.part3

import io.bluetape4k.concurrent.virtualthread.StructuredSubtask
import io.bluetape4k.concurrent.virtualthread.structuredTaskScopeAll
import io.bluetape4k.concurrent.virtualthread.structuredTaskScopeAny
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualThreads.AbstractVirtualThreadTest
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.concurrent.StructuredTaskScope

class StructuredConcurrencyExamples : AbstractVirtualThreadTest() {

    companion object : KLoggingChannel()

    data class Pasta(val name: String = "Spaghetti") : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data class Sauce(val name: String = "Tomato") : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data class Dish(
        val pasta: Pasta,
        val sauce: Sauce,
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    fun preparePasta(): Pasta {
        log.debug { "prepare Pasta" }
        return Pasta()
    }

    fun makeSaurce(): Sauce {
        log.debug { "make Sauce" }
        return Sauce()
    }

    fun serveDish(dish: Dish) {
        log.debug { "hear you are, $dish" }
    }

    /**
     * 비동기 코드를 병렬로 실행하고, 모든 작업이 성공적으로 완료되면 결과를 반환합니다.
     */
    fun prepareDish(): Dish = structuredTaskScopeAll { scope ->
        log.debug { "prepare Dish ..." }
        val pasta: StructuredSubtask<Pasta> = scope.fork {
            sleep(100)
            preparePasta()
        }
        val sauce = scope.fork {
            sleep(200)
            makeSaurce()
        }
        sleep(5)

        scope.join().throwIfFailed()

        pasta.state() shouldBeEqualTo StructuredTaskScope.Subtask.State.SUCCESS
        sauce.state() shouldBeEqualTo StructuredTaskScope.Subtask.State.SUCCESS

        log.debug { "complete Dish" }
        Dish(pasta.get(), sauce.get())
    }

    fun cookPasta(): Dish {
        val dish = prepareDish()
        serveDish(dish)
        return dish
    }

    @Test
    fun `Structured Task Scope 안에서 병렬 작업`() {
        cookPasta() shouldBeEqualTo Dish(Pasta(), Sauce())
    }


    @Test
    fun `structured task scope on success`() {
        // Subtask 들 중 하나라도 성공하면, 나머지 Subtask 들은 취소하고, 결과를 반환합니다.
        // 만약 성공한 것이 없다면 ExecutionException 을 반환합니다.
        val pasta = structuredTaskScopeAny { scope ->

            val subtask1 = scope.fork {
                sleep(100)
                preparePasta()
            }

            val subtask2 = scope.fork {
                sleep(200)
                preparePasta()
            }

            sleep(5)
            subtask1.state() shouldBeEqualTo StructuredTaskScope.Subtask.State.UNAVAILABLE
            subtask2.state() shouldBeEqualTo StructuredTaskScope.Subtask.State.UNAVAILABLE


            scope.join()

            subtask1.state() shouldBeEqualTo StructuredTaskScope.Subtask.State.SUCCESS
            subtask2.state() shouldBeEqualTo StructuredTaskScope.Subtask.State.UNAVAILABLE

            scope.result { RuntimeException(it) }
        }
        pasta shouldBeEqualTo Pasta()
    }
}
