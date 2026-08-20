package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigestMatch
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import org.junit.jupiter.api.Test

class FieldServiceCanonicalizerTest {
    private val canonicalizer = FieldServiceCanonicalizer()

    @Test
    fun `canonicalizer rejects duplicate object keys before digest`() {
        val body = "{\"visitId\":\"visit-1\",\"visitId\":\"visit-2\"}".toByteArray()
        assertFailsWith<InvalidFieldServiceInput> { canonicalizer.digest(body) }
    }

    @Test
    fun `canonicalizer sorts keys and normalizes finite numbers`() {
        val first = canonicalizer.digest("{\"b\":1.0,\"a\":1.00}".toByteArray())
        val second = canonicalizer.digest("{\"a\":1,\"b\":1.000}".toByteArray())

        first shouldBeEqualTo second
        canonicalizer.compareStoredDigest(first, second) shouldBeEqualTo EventDigestMatch.DUPLICATE
    }

    @Test
    fun `canonicalizer rejects excessive nesting and non finite numbers`() {
        val tooDeep = "[".repeat(13) + "0" + "]".repeat(13)
        assertFailsWith<InvalidFieldServiceInput> { canonicalizer.digest(tooDeep.toByteArray()) }
        assertFailsWith<InvalidFieldServiceInput> { canonicalizer.digest("{\"value\":NaN}".toByteArray()) }
    }

    @Test
    fun `canonical bytes are utf8 and stable`() {
        val bytes = canonicalizer.canonicalBytes("{\"name\":\"현장\"}".toByteArray())
        bytes.toString(Charsets.UTF_8) shouldBeEqualTo "{\"name\":\"현장\"}"
    }
}
