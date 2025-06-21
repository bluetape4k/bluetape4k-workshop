package io.bluetape4k.okio

import io.bluetape4k.logging.KLogging
import net.datafaker.Faker
import java.util.*

abstract class AbstractOkioTest {

    companion object: KLogging() {

        const val SEGMENT_SIZE = DEFAULT_BUFFER_SIZE

        @JvmStatic
        val faker = Faker(Locale.getDefault())

        const val TEST_FILE_NAME = "test.txt"
        const val TEST_FILE_CONTENT = "Hello, Okio!"
    }
}
