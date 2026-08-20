package io.bluetape4k.workshop.optimization.fieldservice.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class FieldServiceModelsTest {

    @Test
    fun `ids and event keys reject blank or oversized values`() {
        assertFailsWith<InvalidFieldServiceInput> { VisitId("") }
        assertFailsWith<InvalidFieldServiceInput> {
            EventKey("k".repeat(FieldServiceLimits.MAX_KEY_LENGTH + 1))
        }
        IdempotencyKey("a".repeat(FieldServiceLimits.MAX_KEY_LENGTH)).value.length shouldBeEqualTo
            FieldServiceLimits.MAX_KEY_LENGTH
    }

    @Test
    fun `limits accept the documented envelope`() {
        FieldServiceLimits.MAX_WORKERS shouldBeEqualTo 100
        FieldServiceLimits.MAX_VISITS shouldBeEqualTo 500
        FieldServiceLimits.MAX_SKILLS_PER_WORKER shouldBeEqualTo 20
        FieldServiceLimits.MAX_AVAILABILITY_WINDOWS_PER_WORKER shouldBeEqualTo 20
        FieldServiceLimits.MAX_JSON_DEPTH shouldBeEqualTo 12
        FieldServiceLimits.isFiniteNonNegativeTravelTime(0L).shouldBeTrue()
        FieldServiceLimits.isFiniteNonNegativeTravelTime(Long.MAX_VALUE).shouldBeTrue()
    }

    @Test
    fun `event key comparison distinguishes duplicate and reused payload`() {
        FieldServiceEvents.compare(
            EventDigest("a".repeat(64)),
            EventDigest("a".repeat(64)),
        ) shouldBeEqualTo EventDigestMatch.DUPLICATE
        FieldServiceEvents.compare(
            EventDigest("a".repeat(64)),
            EventDigest("b".repeat(64)),
        ) shouldBeEqualTo EventDigestMatch.EVENT_KEY_REUSED
    }
}
