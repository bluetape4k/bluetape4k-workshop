package io.bluetape4k.workshop.virtualthread.part2

import io.bluetape4k.junit5.coroutines.runSuspendTest
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualThreads.AbstractVirtualThreadTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * 동기 코드를 비동기 방식으로 실행하는 방법
 *
 * - 동기 방식의 Legacy 코드를 비동기로 실행할 때
 *      - CPU intensive 한 작업은 기존의 Platform Thread 를 사용하자
 *      - IO intensive 한 작업은 Virtual Thread 를 사용하자
 *      - Request-Response 방식에는 Virtual Thread 가 적합하다
 *
 *  - 신규 제작 시에는 Kotlin Coroutines 를 활용하는 방법도 있다.
 *      - 신규 제작 함수를 suspend 함수로 작성하고, Coroutine 을 이용하여 실행한다.
 */
class Rule2WriteBlockingSynchronousCode: AbstractVirtualThreadTest() {

    companion object: KLoggingChannel() {
        private const val REPEAT_SIZE = 3
    }

    /**
     * 동기 함수를 CompletableFuture (Platform Thread) 를 이용하여 비동기로 실행하기
     * 중간에 예외 처리는 어떻게 해야 하나? (Structural Concurrency)
     */
    @RepeatedTest(REPEAT_SIZE)
    fun `동기 코드를 CompletableFuture 로 실행하기`() {
        val startMs = System.currentTimeMillis()

        CompletableFuture
            .supplyAsync { readPriceInEur() }
            .thenCombine(
                CompletableFuture.supplyAsync { readExchangeRateEurToUsd() }) { price, rate ->
                price * rate
            }
            .thenCompose { amount ->
                CompletableFuture.supplyAsync { amount * (1.0F + readTax(amount)) }
            }
            .whenComplete { grossAmountInUsd, error ->
                if (error == null) {
                    grossAmountInUsd.toInt() shouldBeEqualTo 108
                } else {
                    fail(error)
                }
            }
            .get()  // 어쨌든 여기서 Blocking 된다 (Non-Blocking 이 아니다)

        val durationMs = System.currentTimeMillis() - startMs
        log.debug { "CompletableFuture 실행 시간(msec): $durationMs" }
        durationMs shouldBeInRange 800L..900L
    }

    /**
     * 동기 함수를 Virtual Thread를 이용하여 실행하기
     * 코드 자체가 Sequential 하게 작성되므로, 이해하기 쉽고, 예외 처리도 쉽다.
     */
    @RepeatedTest(REPEAT_SIZE)
    fun `동기 코드를 Virtual Thread 로 실행하기`() {

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val startMs = System.currentTimeMillis()

            val priceInEur = executor.submit<Int> { readPriceInEur() }
            val exchangeRateEuroToUsd = executor.submit<Float> { readExchangeRateEurToUsd() }
            val netAmountInUsd = priceInEur.get() * exchangeRateEuroToUsd.get()

            val tax = executor.submit<Float> { readTax(netAmountInUsd) }
            val grossAmountInUsd = netAmountInUsd * (1.0F + tax.get())

            grossAmountInUsd.toInt() shouldBeEqualTo 108

            val durationMs = System.currentTimeMillis() - startMs
            log.debug { "Virtual Thread로 동기 코드 실행 시간(msec): $durationMs" }
            durationMs shouldBeInRange 800L..900L
        }
    }

    /**
     * Suspend 함수를 [Dispatchers.Default] Context에서 실행하기
     */
    @RepeatedTest(REPEAT_SIZE)
    fun `Suspend 함수를 Coroutines 환경에서 실행하기`() = runSuspendTest(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()

        val priceInEur = async { readPriceInEurAwait() }
        val exchangeRateEuroToUsd = async { readExchangeRateEurToUsdAwait() }
        val netAmountInUsd = priceInEur.await() * exchangeRateEuroToUsd.await()

        val tax = async { readTax(netAmountInUsd) }
        val grossAmountInUsd = netAmountInUsd * (1.0F + tax.await())

        grossAmountInUsd.toInt() shouldBeEqualTo 108

        val durationMs = System.currentTimeMillis() - startMs
        log.debug { "Coroutines 로 Suspend 함수 실행 시간(msec): $durationMs" }
        durationMs shouldBeInRange 800L..900L
    }


    private fun readPriceInEur(): Int {
        return sleepAndGet(200, 82)
    }

    private fun readExchangeRateEurToUsd(): Float {
        return sleepAndGet(300, 1.1f)
    }

    private fun readTax(amount: Float): Float {
        return sleepAndGet(500, 0.2f)
    }

    private suspend fun readPriceInEurAwait(): Int {
        return sleepAndGetAwait(200, 82)
    }

    private suspend fun readExchangeRateEurToUsdAwait(): Float {
        return sleepAndGetAwait(300, 1.1f)
    }

    private suspend fun readTaxAwait(amount: Float): Float {
        return sleepAndGetAwait(500, 0.2f)
    }
}
