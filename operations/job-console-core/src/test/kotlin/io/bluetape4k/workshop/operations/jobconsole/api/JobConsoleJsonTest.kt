package io.bluetape4k.workshop.operations.jobconsole.api

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature

class JobConsoleJsonTest {

    private companion object: KLogging() {
        const val MAX_REQUEST_BYTES = 64 * 1024
    }

    @Test
    fun `strict request mapper keeps the bluetape baseline and request boundary`() {
        val mapper = JobConsoleJson.strictRequestMapper(MAX_REQUEST_BYTES)

        mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).shouldBeTrue()
        mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).shouldBeTrue()
        mapper.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).shouldBeTrue()
        mapper.tokenStreamFactory().isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION).shouldBeTrue()
        mapper.tokenStreamFactory().streamReadConstraints().maxDocumentLength shouldBeEqualTo MAX_REQUEST_BYTES.toLong()
        mapper.tokenStreamFactory().streamReadConstraints().maxNestingDepth shouldBeEqualTo 32
        mapper.tokenStreamFactory().streamReadConstraints().maxStringLength shouldBeEqualTo MAX_REQUEST_BYTES
        mapper.tokenStreamFactory().streamReadConstraints().maxNameLength shouldBeEqualTo 256
        mapper.tokenStreamFactory().streamReadConstraints().maxTokenCount shouldBeEqualTo 256L
    }
}
