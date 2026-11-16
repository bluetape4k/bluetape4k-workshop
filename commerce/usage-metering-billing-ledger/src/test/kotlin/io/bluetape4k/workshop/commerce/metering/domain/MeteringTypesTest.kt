package io.bluetape4k.workshop.commerce.metering.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.money.bigDecimalValue
import io.bluetape4k.money.currencyUnitOf
import io.bluetape4k.money.moneyOf
import org.junit.jupiter.api.Test
import org.javamoney.moneta.Money as MonetaMoney
import java.math.BigDecimal
import java.util.Currency

class MeteringTypesTest {

    companion object: KLogging()

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
        moneyOf(BigDecimal("12.345"), currencyUnitOf("USD")).normalized().bigDecimalValue shouldBeEqualTo
                BigDecimal("12.35")
        moneyOf(BigDecimal("12.5"), currencyUnitOf("KRW")).normalized().bigDecimalValue shouldBeEqualTo
                BigDecimal("13")
    }

    @Test
    fun `money arithmetic rejects currency mismatch`() {
        val dollars = moneyOf(BigDecimal("10.00"), currencyUnitOf("USD"))
        val won = moneyOf(BigDecimal("10"), currencyUnitOf("KRW"))

        assertFailsWith<IllegalArgumentException> {
            dollars + won
        }
    }

    @Test
    fun `money arithmetic normalizes the Moneta result`() {
        val total =
            moneyOf(BigDecimal("10.005"), currencyUnitOf("USD")) +
                moneyOf(BigDecimal("1.005"), currencyUnitOf("USD"))

        total.bigDecimalValue shouldBeEqualTo BigDecimal("11.01")
    }

    @Test
    fun `money rejects negative amounts before normalization`() {
        assertFailsWith<IllegalArgumentException> {
            moneyOf(BigDecimal("-1.00"), currencyUnitOf("USD")).normalized()
        }
    }

    @Test
    fun `charge multiplies quantity and unit price before currency rounding`() {
        val result: MonetaMoney = charge(
            quantity = UsageQuantity(BigDecimal("3.333333")),
            unitPrice = UnitPrice(BigDecimal("0.015")),
            currency = Currency.getInstance("USD"),
        )

        result shouldBeEqualTo moneyOf(BigDecimal("0.05"), currencyUnitOf("USD"))
    }

    @Test
    fun `billing charge keeps the ledger six decimal scale through Moneta`() {
        chargeAmount(
            quantity = BigDecimal("3.333333"),
            unitPrice = BigDecimal("0.015"),
            currencyCode = "USD",
        ) shouldBeEqualTo BigDecimal("0.050000")
    }
}
