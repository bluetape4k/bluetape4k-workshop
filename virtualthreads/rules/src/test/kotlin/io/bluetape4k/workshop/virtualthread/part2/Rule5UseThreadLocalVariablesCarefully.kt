package io.bluetape4k.workshop.virtualthread.part2

import io.bluetape4k.concurrent.virtualthread.structuredTaskScopeAll
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.virtualThreads.AbstractVirtualThreadTest
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnJre
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.api.fail


/**
 * Rule 5: Virtual Thread 사용 시에는 ThreadLocal 를 사용하지 말고, [ScopedValue]를 사용하세요
 */
class Rule5UseThreadLocalVariablesCarefully: AbstractVirtualThreadTest() {

    companion object: KLoggingChannel()

    @Nested
    inner class DoNot {

        private val threadLocal = InheritableThreadLocal<String>()

        @Test
        fun `비추천 - ThreadLocal 변수를 사용하기`() {
            threadLocal.set("zero")
            threadLocal.get() shouldBeEqualTo "zero"

            threadLocal.set("one")
            threadLocal.get() shouldBeEqualTo "one"

            val childThread = Thread {
                threadLocal.get() shouldBeEqualTo "one"
            }

            childThread.start()
            childThread.join()

            threadLocal.remove()
            threadLocal.get().shouldBeNull()
        }
    }

    @Nested
    inner class Do {

        private val scopedValue = ScopedValue.newInstance<String>()

        @EnabledOnJre(JRE.JAVA_21)
        @Test
        fun `추천 - ScopedValue 사용하기`() {
            ScopedValue.where(scopedValue, "zero").run {
                scopedValue.get() shouldBeEqualTo "zero"

                ScopedValue.where(scopedValue, "one").run {
                    scopedValue.get() shouldBeEqualTo "one"
                }

                scopedValue.get() shouldBeEqualTo "zero"

                try {
                    structuredTaskScopeAll { scope ->
                        // Scope 안에서 sub task를 생성합니다.
                        scope.fork {
                            scopedValue.get() shouldBeEqualTo "zero"
                            -1
                        }
                        scope.join().throwIfFailed()
                    }
                } catch (e: InterruptedException) {
                    fail(e)
                }
            }

            assertFailsWith<NoSuchElementException> {
                scopedValue.get()
            }
        }
    }
}
