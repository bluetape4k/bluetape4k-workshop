package io.bluetape4k.workshop.operations.jobconsole.api

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature

class JobConsoleJsonTest {
    @Test
    fun `strict request mapper keeps the bluetape baseline and request boundary`() {
        val mapper = JobConsoleJson.strictRequestMapper(MAX_REQUEST_BYTES)

        mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).shouldBeEqualTo(true)
        mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).shouldBeEqualTo(true)
        mapper.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).shouldBeEqualTo(true)
        mapper.tokenStreamFactory().isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION).shouldBeEqualTo(true)
        mapper.tokenStreamFactory().streamReadConstraints().getMaxDocumentLength().shouldBeEqualTo(MAX_REQUEST_BYTES.toLong())
        mapper.tokenStreamFactory().streamReadConstraints().getMaxNestingDepth().shouldBeEqualTo(32)
        mapper.tokenStreamFactory().streamReadConstraints().getMaxStringLength().shouldBeEqualTo(MAX_REQUEST_BYTES)
        mapper.tokenStreamFactory().streamReadConstraints().getMaxNameLength().shouldBeEqualTo(256)
        mapper.tokenStreamFactory().streamReadConstraints().getMaxTokenCount().shouldBeEqualTo(256L)
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 64 * 1024
    }
}
