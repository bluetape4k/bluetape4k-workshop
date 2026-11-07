package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigestMatch
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.ProviderRequestId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Skill
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import org.junit.jupiter.api.Test

class FieldServiceCanonicalizerTest {

    companion object: KLogging()

    private val canonicalizer = FieldServiceCanonicalizer()

    @Test
    fun `canonicalizer rejects duplicate object keys before digest`() {
        val body = "{\"visitId\":\"visit-1\",\"visitId\":\"visit-2\"}".toUtf8Bytes()
        assertFailsWith<InvalidFieldServiceInput> {
            canonicalizer.digest(body)
        }
    }

    @Test
    fun `canonicalizer sorts keys and normalizes finite numbers`() {
        val first = canonicalizer.digest("{\"b\":1.0,\"a\":1.00}".toUtf8Bytes())
        val second = canonicalizer.digest("{\"a\":1,\"b\":1.000}".toUtf8Bytes())

        first shouldBeEqualTo second
        canonicalizer.compareStoredDigest(first, second) shouldBeEqualTo EventDigestMatch.DUPLICATE
    }

    @Test
    fun `canonicalizer rejects excessive nesting and non finite numbers`() {
        val tooDeep = "[".repeat(13) + "0" + "]".repeat(13)
        assertFailsWith<InvalidFieldServiceInput> {
            canonicalizer.digest(tooDeep.toUtf8Bytes())
        }
        assertFailsWith<InvalidFieldServiceInput> {
            canonicalizer.digest("{\"value\":NaN}".toUtf8Bytes())
        }
    }

    @Test
    fun `canonical bytes are utf8 and stable`() {
        val bytes = canonicalizer.canonicalBytes("{\"name\":\"현장\"}".toUtf8Bytes())
        bytes.toUtf8String() shouldBeEqualTo "{\"name\":\"현장\"}"
    }

    @Test
    fun `canonical bytes preserve the provider golden representation and digest`() {
        val body = "{\"b\":1.00,\"a\":[true,null,\"e\\u0301\"],\"z\":-0.0}".toUtf8Bytes()

        canonicalizer.canonicalBytes(body).toUtf8String() shouldBeEqualTo
                "{\"a\":[true, null, \"é\"], \"b\":1, \"z\":0}"

        canonicalizer.digest(body).value shouldBeEqualTo
                "a79fd84206152d61159dbea8876798156529edf969397498e874b3d57756f5b2"
    }

    @Test
    fun `canonicalizer rejects trailing JSON tokens`() {
        assertFailsWith<InvalidFieldServiceInput> {
            canonicalizer.canonicalBytes("{} {}".toUtf8Bytes())
        }
    }

    @Test
    fun `value class identifiers preserve validated normalization`() {
        WorkerId(" worker-1 ").value shouldBeEqualTo "worker-1"
        VisitId(" visit-1 ").value shouldBeEqualTo "visit-1"
        PlanId(" plan-1 ").value shouldBeEqualTo "plan-1"
        AggregateId(" aggregate-1 ").value shouldBeEqualTo "aggregate-1"
        CoordinateId(" coordinate-1 ").value shouldBeEqualTo "coordinate-1"
        Skill(" electrical ").value shouldBeEqualTo "electrical"
        DatasetId(" dataset-1 ").value shouldBeEqualTo "dataset-1"
        ProviderRequestId(" provider-request-1 ").value shouldBeEqualTo "provider-request-1"
    }
}
