package io.bluetape4k.workshop.commerce.metering.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

class MeteringTypesTest {

    @Test
    fun `identifiers reject blank and oversized values`() {
        assertFailsWith<IllegalArgumentException> { TenantId(" ") }
        assertFailsWith<IllegalArgumentException> { TenantId("t".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { MeterCode("") }
        assertFailsWith<IllegalArgumentException> { MeterCode("m".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { SourceSystem("\t") }
        assertFailsWith<IllegalArgumentException> { SourceSystem("s".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { SourceEventId("\n") }
        assertFailsWith<IllegalArgumentException> { SourceEventId("e".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { IdempotencyKey(" ") }
        assertFailsWith<IllegalArgumentException> { IdempotencyKey("k".repeat(257)) }
    }

    @Test
    fun `quantity and unit price require positive values with bounded scale`() {
        assertFailsWith<IllegalArgumentException> { UsageQuantity(BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { UsageQuantity(BigDecimal("-0.000001")) }
        assertFailsWith<IllegalArgumentException> { UsageQuantity(BigDecimal("1.0000001")) }
        assertFailsWith<IllegalArgumentException> { UnitPrice(BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { UnitPrice(BigDecimal("-0.01")) }
        assertFailsWith<IllegalArgumentException> { UnitPrice(BigDecimal("0.0000001")) }

        UsageQuantity(BigDecimal("1.000001")).value shouldBeEqualTo BigDecimal("1.000001")
        UnitPrice(BigDecimal("0.000001")).value shouldBeEqualTo BigDecimal("0.000001")
    }

    @Test
    fun `money normalizes using currency fraction digits and half up rounding`() {
        Money(BigDecimal("12.345"), Currency.getInstance("USD")).normalized() shouldBeEqualTo
            Money(BigDecimal("12.35"), Currency.getInstance("USD"))
        Money(BigDecimal("12.5"), Currency.getInstance("KRW")).normalized() shouldBeEqualTo
            Money(BigDecimal("13"), Currency.getInstance("KRW"))
    }

    @Test
    fun `money arithmetic rejects currency mismatch`() {
        val dollars = Money(BigDecimal("10.00"), Currency.getInstance("USD"))
        val won = Money(BigDecimal("10"), Currency.getInstance("KRW"))

        assertFailsWith<IllegalArgumentException> { dollars + won }
    }

    @Test
    fun `charge multiplies quantity and unit price before currency rounding`() {
        charge(
            quantity = UsageQuantity(BigDecimal("3.333333")),
            unitPrice = UnitPrice(BigDecimal("0.015")),
            currency = Currency.getInstance("USD"),
        ) shouldBeEqualTo Money(BigDecimal("0.05"), Currency.getInstance("USD"))
    }
}
