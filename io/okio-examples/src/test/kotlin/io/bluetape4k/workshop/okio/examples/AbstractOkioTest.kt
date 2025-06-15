package io.bluetape4k.workshop.okio.examples

import io.bluetape4k.logging.KLogging
import net.datafaker.Faker
import java.util.*

abstract class AbstractOkioTest {

    companion object: KLogging() {
        @JvmStatic
        val faker = Faker(Locale.getDefault())

        const val TEST_FILE_NAME = "test.txt"
        const val TEST_FILE_CONTENT = "Hello, Okio!"
    }
}
