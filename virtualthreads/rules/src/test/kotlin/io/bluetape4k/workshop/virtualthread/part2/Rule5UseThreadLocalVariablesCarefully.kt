package io.bluetape4k.workshop.virtualthread.part2

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.StructuredTaskScope


/**
 * Rule 5: Virtual Thread 사용 시에는 ThreadLocal 를 사용하지 말고, [ScopedValue]를 사용하세요
 */
class Rule5UseThreadLocalVariablesCarefully {

    companion object: KLogging()

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

        @Test
        fun `추천 - ScopedValue 사용하기`() {
            ScopedValue.runWhere(scopedValue, "zero") {
                scopedValue.get() shouldBeEqualTo "zero"

                ScopedValue.runWhere(scopedValue, "one") {
                    scopedValue.get() shouldBeEqualTo "one"
                }

                scopedValue.get() shouldBeEqualTo "zero"

                try {
                    StructuredTaskScope.ShutdownOnFailure().use { scope ->
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
