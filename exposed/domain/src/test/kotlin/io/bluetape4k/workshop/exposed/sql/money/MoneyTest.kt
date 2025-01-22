package io.bluetape4k.workshop.exposed.sql.money

import io.bluetape4k.logging.KLogging
import io.bluetape4k.money.currencyUnitOf
import io.bluetape4k.money.moneyOf
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.javamoney.moneta.Money
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.money.compositeMoney
import org.jetbrains.exposed.sql.money.currency
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import javax.money.CurrencyUnit
import javax.money.MonetaryAmount

class MoneyTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * Money 를 사용하는 테스트를 실행합니다.
     *
     * ```sql
     * INSERT INTO accounts (composite_money, "composite_money_C") VALUES (10.00000, 'USD')
     * SELECT accounts.composite_money, accounts."composite_money_C" FROM accounts WHERE accounts.id = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and select money`(testDB: TestDB) {
        withTables(testDB, Account) {
            assertInsertOfCompositeValueReturnsEquivalentOnSelect(Money.of(BigDecimal.TEN, "USD"))
            Account.deleteAll()
            assertInsertOfComponentValuesReturnsEquivalentOnSelect(Money.of(BigDecimal.TEN, "USD"))
        }
    }

    /**
     * Floating 값을 가지는 Money 를 사용하는 테스트를 실행합니다.
     *
     * H2:
     * ```sql
     * INSERT INTO ACCOUNTS (COMPOSITE_MONEY, "composite_money_C") VALUES (0.12345, 'USD')
     * SELECT ACCOUNTS.COMPOSITE_MONEY, ACCOUNTS."composite_money_C" FROM ACCOUNTS WHERE ACCOUNTS.ID = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and select floating money`(testDB: TestDB) {
        withTables(testDB, Account) {
            assertInsertOfCompositeValueReturnsEquivalentOnSelect(Money.of(0.12345.toBigDecimal(), "USD"))
            Account.deleteAll()
            assertInsertOfComponentValuesReturnsEquivalentOnSelect(Money.of(0.12345.toBigDecimal(), "USD"))
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert select null`(testDB: TestDB) {
        withTables(testDB, Account) {
            assertInsertOfCompositeValueReturnsEquivalentOnSelect(null)
            Account.deleteAll()
            assertInsertOfComponentValuesReturnsEquivalentOnSelect(null)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert select out of length`(testDB: TestDB) {
        val amount = 12345678901.toBigDecimal()
        val toInsert = moneyOf(amount, "CZK")
        withTables(testDB, Account) {
            expectException<ExposedSQLException> {
                Account.insertAndGetId {
                    it[composite_money] = toInsert
                }
            }
            expectException<ExposedSQLException> {
                Account.insertAndGetId {
                    it[composite_money.amount] = amount
                    it[composite_money.currency] = toInsert.currency
                }
            }
        }
    }

    /**
     * ```sql
     * SELECT ACCOUNTS.ID, ACCOUNTS.COMPOSITE_MONEY, ACCOUNTS."composite_money_C"
     *   FROM ACCOUNTS
     *  WHERE (ACCOUNTS.COMPOSITE_MONEY = 10) AND (ACCOUNTS."composite_money_C" = 'USD')
     * ```
     * ```sql
     * SELECT ACCOUNTS.ID, ACCOUNTS.COMPOSITE_MONEY, ACCOUNTS."composite_money_C"
     *   FROM ACCOUNTS
     *  WHERE ACCOUNTS."composite_money_C" = 'USD'
     * ```
     * ```sql
     * SELECT ACCOUNTS.ID, ACCOUNTS.COMPOSITE_MONEY, ACCOUNTS."composite_money_C"
     *   FROM ACCOUNTS WHERE ACCOUNTS.COMPOSITE_MONEY = 1E+1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `search by composite column`(testDB: TestDB) {
        val money = moneyOf(BigDecimal.TEN, "USD")

        withTables(testDB, Account) {
            Account.insertAndGetId {
                it[composite_money] = money
            }

            val predicates = listOf(
                Account.composite_money eq money,
                Account.composite_money.currency eq money.currency,
                Account.composite_money.amount eq money.numberStripped
            )

            predicates.forEach { predicate ->
                val found = AccountDao.find { predicate }
                found.count() shouldBeEqualTo 1L
                val next = found.iterator().next()
                next.money shouldBeEqualTo money
                next.currency shouldBeEqualTo money.currency
                next.amount shouldBeEqualTo money.numberStripped.setScale(AMOUNT_SCALE)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `using manual composite money columns`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val money = compositeMoney(
                decimal("amount", 8, AMOUNT_SCALE),
                currency("currency")
            )

            val nullableMoney = compositeMoney(
                decimal("nullable_amount", 8, AMOUNT_SCALE).nullable(),
                currency("nullable_currency").nullable()
            )
        }

        withTables(testDB, tester) {
            val amount = 99.toBigDecimal().setScale(AMOUNT_SCALE)
            val currencyUnit = currencyUnitOf("EUR")
            tester.insert {
                it[money.amount] = amount
                it[money.currency] = currencyUnit
                it[nullableMoney.amount] = null
                it[nullableMoney.currency] = null
            }

            val result1 = tester.selectAll()
                .where {
                    tester.nullableMoney.amount.isNull() and tester.nullableMoney.currency.isNull()
                }
                .single()
            result1[tester.money.amount] shouldBeEqualTo amount

            tester.update {
                it[tester.nullableMoney.amount] = amount
                it[tester.nullableMoney.currency] = currencyUnit
            }

            val result2 = tester
                .select(tester.money.currency, tester.nullableMoney.currency)
                .where { tester.money.amount.isNotNull() and tester.nullableMoney.amount.isNotNull() }
                .single()
            result2[tester.money.currency] shouldBeEqualTo currencyUnit
            result2[tester.nullableMoney.currency] shouldBeEqualTo currencyUnit

            // manual composite columns should still accept composite values
            val compositeMoney = moneyOf(10, "CAD")
            tester.insert {
                it[money] = compositeMoney
                it[nullableMoney] = null
            }
            tester.insert {
                it[money] = compositeMoney
            }
            tester.selectAll().where { tester.nullableMoney eq null }.count() shouldBeEqualTo 2L
        }
    }

    @Suppress("UnusedReceiverParameter")
    private fun Transaction.assertInsertOfCompositeValueReturnsEquivalentOnSelect(toInsert: Money?) {
        val accountId = Account.insertAndGetId {
            it[composite_money] = toInsert
        }

        val single = Account.select(Account.composite_money).where { Account.id eq accountId }.single()
        val inserted: MonetaryAmount? = single[Account.composite_money]

        inserted shouldBeEqualTo toInsert
    }

    @Suppress("UnusedReceiverParameter")
    private fun Transaction.assertInsertOfComponentValuesReturnsEquivalentOnSelect(toInsert: Money?) {
        val amount: BigDecimal? = toInsert?.numberStripped?.setScale(AMOUNT_SCALE)
        val currencyUnit: CurrencyUnit? = toInsert?.currency
        val accountId = Account.insertAndGetId {
            it[composite_money.amount] = amount
            it[composite_money.currency] = currencyUnit
        }

        val single = Account.select(Account.composite_money).where { Account.id eq accountId }.single()
        single[Account.composite_money.amount] shouldBeEqualTo amount
        single[Account.composite_money.currency] shouldBeEqualTo currencyUnit
    }
}
