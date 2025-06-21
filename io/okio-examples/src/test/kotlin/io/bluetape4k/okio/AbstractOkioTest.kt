package io.bluetape4k.okio

import io.bluetape4k.logging.KLogging
import net.datafaker.Faker
import org.amshove.kluent.shouldBeNear
import java.util.*

abstract class AbstractOkioTest {

    companion object: KLogging() {

        const val SEGMENT_SIZE = DEFAULT_BUFFER_SIZE

        @JvmStatic
        val faker = Faker(Locale.getDefault())

        const val TEST_FILE_NAME = "test.txt"
        const val TEST_FILE_CONTENT = "Hello, Okio!"
    }

    protected fun now(): Double = System.nanoTime() / 1_000_000.0

    protected fun assertElapsed(duration: Double, start: Double) {
        (now() - start - 200.0).shouldBeNear(duration, 250.0)
    }
}
