package io.bluetape4k.workshop.shared.image

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test

class PublicBaseUriPolicyTest {

    @Test
    fun `loopback http is allowed and remote https is allowed`() {
        PublicBaseUriPolicy().validate("http://localhost:8080/public")
            .toString() shouldBeEqualTo "http://localhost:8080/public"
        PublicBaseUriPolicy().validate("https://cdn.example.com/images")
            .toString() shouldBeEqualTo "https://cdn.example.com/images"
    }

    @Test
    fun `remote http and local storage remote url are rejected by default`() {
        assertIllegalArgument { PublicBaseUriPolicy().validate("http://cdn.example.com/images") }
        assertIllegalArgument {
            PublicBaseUriPolicy().validate("https://cdn.example.com/images", storageBackendIsLocal = true)
        }
    }

    @Test
    fun `path traversal encoded dot and uri metadata are rejected`() {
        listOf(
            "https://cdn.example.com/a/../b",
            "https://cdn.example.com/a/%2e%2e/b",
            "https://user:pass@cdn.example.com/images",
            "https://cdn.example.com/images?token=secret",
            "https://cdn.example.com/images#fragment",
        ).forEach { url ->
            try {
                PublicBaseUriPolicy().validate(url)
                throw AssertionError("Expected rejection for $url")
            } catch (failure: IllegalArgumentException) {
                failure.message.orEmpty() shouldContain "publicBaseUrl"
            }
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
