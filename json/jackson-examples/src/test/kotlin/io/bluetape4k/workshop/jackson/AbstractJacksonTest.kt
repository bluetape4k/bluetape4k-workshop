package io.bluetape4k.workshop.jackson

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

abstract class AbstractJacksonTest {

    companion object: KLogging() {
        @JvmStatic
        val faker = Fakers.faker

        const val REPEAT_SIZE = 5
    }

    protected val defaultMapper: JsonMapper by lazy {
        Jackson.defaultJsonMapper
            .rebuild()
            .apply {
                configure(SerializationFeature.INDENT_OUTPUT, true)
            }
            .build()
    }

    private val jsonPathConfiguratrion: Configuration = Configuration.defaultConfiguration()
        .addOptions(
            Option.SUPPRESS_EXCEPTIONS
        )

    protected fun String.toDocument(): DocumentContext =
        JsonPath.parse(this, jsonPathConfiguratrion)
}
