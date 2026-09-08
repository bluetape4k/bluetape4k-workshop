package io.bluetape4k.workshop.ktor.json

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.jackson3.Jackson
import org.junit.jupiter.api.Test

class Jackson3SupportTest {

    @Test
    fun `NDJSON support reuses the bluetape Jackson3 mapper`() {
        Jackson3Support.objectMapper shouldBeSameInstanceAs Jackson.defaultJsonMapper
    }

    @Test
    fun `shared mapper preserves unicode null and collection serialization`() {
        val value = linkedMapOf(
            "unicode" to "한글🙂",
            "nullable" to null,
            "items" to listOf("a", "b"),
        )

        Jackson3Support.objectMapper.writeValueAsString(value) shouldBeEqualTo
            """{"unicode":"한글🙂","nullable":null,"items":["a","b"]}"""
    }
}
