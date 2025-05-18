package io.bluetape4k.workshop.jackson

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import io.bluetape4k.jackson.Jackson
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging

abstract class AbstractJacksonTest {

    companion object: KLogging() {
        @JvmStatic
        val faker = Fakers.faker

        const val REPEAT_SIZE = 5
    }

    protected val defaultMapper: ObjectMapper by lazy {
        Jackson.defaultJsonMapper
            .enable(SerializationFeature.INDENT_OUTPUT)
    }

    private val jsonPathConfiguratrion: Configuration = Configuration.defaultConfiguration()
        .addOptions(
            Option.SUPPRESS_EXCEPTIONS
        )

    protected fun String.toDocument(): DocumentContext =
        JsonPath.parse(this, jsonPathConfiguratrion)
}
