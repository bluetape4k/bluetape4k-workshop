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
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.money.compositeMoney
import org.jetbrains.exposed.v1.money.currency
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
     * SELECT accounts.composite_money,
     *        accounts."composite_money_C"
     *   FROM accounts WHERE accounts.id = 1
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
     * Postgres:
     * ```sql
     * INSERT INTO accounts (composite_money, "composite_money_C")
     * VALUES (0.12345, 'USD');
     *
     * SELECT accounts.composite_money,
     *        accounts."composite_money_C"
     *   FROM accounts WHERE accounts.id = 1;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and select floating money`(testDB: TestDB) {
        withTables(testDB, Account) {
            assertInsertOfCompositeValueReturnsEquivalentOnSelect(Money.of(0.12345.toBigDecimal(), "USD"))
            Account.deleteAll()
            assertInsertOfComponentValuesReturnsEquivalentOnSelect(Money.of(0.54321.toBigDecimal(), "USD"))
        }
    }

    /**
     * Null 값을 가지는 Money 를 사용하는 테스트를 실행합니다.
     *
     * ```sql
     * INSERT INTO accounts (composite_money, "composite_money_C")
     * VALUES (NULL, NULL);
     *
     * SELECT accounts.composite_money,
     *        accounts."composite_money_C"
     *   FROM accounts
     *  WHERE accounts.id = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert select null`(testDB: TestDB) {
        withTables(testDB, Account) {
            assertInsertOfCompositeValueReturnsEquivalentOnSelect(null)
            Account.deleteAll()
            assertInsertOfComponentValuesReturnsEquivalentOnSelect(null)
        }
    }

    /**
     * INSERT INTO ... SELECT 문을 사용할 때, 컬럼 길이보다 더 긴 문자열 값을 사용 할 때에는 예외가 발생합니다.
     */
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
     * Money 컬럼으로 조회하기
     *
     * Postgres:
     * ```sql
     * SELECT accounts.id,
     *        accounts.composite_money,
     *        accounts."composite_money_C"
     *   FROM accounts
     *  WHERE (accounts.composite_money = 10)
     *    AND (accounts."composite_money_C" = 'USD')
     * ```
     * ```sql
     * SELECT accounts.id,
     *        accounts.composite_money,
     *        accounts."composite_money_C"
     *   FROM accounts
     *  WHERE accounts."composite_money_C" = 'USD'
     * ```
     * ```sql
     * SELECT accounts.id,
     *        accounts.composite_money,
     *        accounts."composite_money_C"
     *   FROM accounts
     *  WHERE accounts.composite_money = 1E+1
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

    /**
     * [compositeMoney] 함수를 이용하여 직접 `Money` 컬럼을 정의하고 사용합니다.
     *
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `using manual composite money columns`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS tester (
         *      amount DECIMAL(8, 5) NOT NULL,
         *      currency VARCHAR(3) NOT NULL,
         *      nullable_amount DECIMAL(8, 5) NULL,
         *      nullable_currency VARCHAR(3) NULL
         * )
         * ```
         */
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
            /**
             * ```sql
             * INSERT INTO tester (amount, currency, nullable_amount, nullable_currency)
             * VALUES (99.00000, 'EUR', NULL, NULL)
             * ```
             */
            val amount = 99.toBigDecimal().setScale(AMOUNT_SCALE)
            val currencyUnit = currencyUnitOf("EUR")
            tester.insert {
                it[money.amount] = amount
                it[money.currency] = currencyUnit
                it[nullableMoney.amount] = null
                it[nullableMoney.currency] = null
            }

            /**
             * ```sql
             * SELECT tester.amount,
             *        tester.currency,
             *        tester.nullable_amount,
             *        tester.nullable_currency
             *   FROM tester
             *  WHERE (tester.nullable_amount IS NULL)
             *    AND (tester.nullable_currency IS NULL)
             * ```
             */
            val result1 = tester.selectAll()
                .where { tester.nullableMoney.amount.isNull() }
                .andWhere { tester.nullableMoney.currency.isNull() }
                .single()
            result1[tester.money.amount] shouldBeEqualTo amount

            /**
             * ```sql
             * UPDATE tester
             *    SET nullable_amount=99.00000,
             *        nullable_currency='EUR'
             * ```
             */
            tester.update {
                it[tester.nullableMoney.amount] = amount
                it[tester.nullableMoney.currency] = currencyUnit
            }

            /**
             * ```sql
             * SELECT tester.currency,
             *        tester.nullable_currency
             *   FROM tester
             *  WHERE (tester.amount IS NOT NULL)
             *    AND (tester.nullable_amount IS NOT NULL)
             * ```
             */
            val result2 = tester
                .select(tester.money.currency, tester.nullableMoney.currency)
                .where { tester.money.amount.isNotNull() }
                .andWhere { tester.nullableMoney.amount.isNotNull() }
                .single()

            result2[tester.money.currency] shouldBeEqualTo currencyUnit
            result2[tester.nullableMoney.currency] shouldBeEqualTo currencyUnit

            /**
             * manual composite columns should still accept composite values
             *
             * ```sql
             * INSERT INTO tester (amount, currency, nullable_amount, nullable_currency)
             * VALUES (10, 'CAD', NULL, NULL)
             * ```
             * ```
             * INSERT INTO tester (amount, currency) VALUES (10, 'CAD')
             * ```
             */
            val compositeMoney = moneyOf(10, "CAD")
            tester.insert {
                it[money] = compositeMoney
                it[nullableMoney] = null
            }
            tester.insert {
                it[money] = compositeMoney
            }

            /**
             * Search by composite column
             *
             * ```sql
             * SELECT COUNT(*) FROM tester
             *  WHERE (tester.nullable_amount IS NULL)
             *    AND (tester.nullable_currency IS NULL)
             * ```
             */
            tester.selectAll()
                .where { tester.nullableMoney eq null }
                .count() shouldBeEqualTo 2L
        }
    }

    @Suppress("UnusedReceiverParameter")
    private fun JdbcTransaction.assertInsertOfCompositeValueReturnsEquivalentOnSelect(toInsert: Money?) {
        val accountId = Account.insertAndGetId {
            it[composite_money] = toInsert
        }

        val single = Account.select(Account.composite_money).where { Account.id eq accountId }.single()
        val inserted: MonetaryAmount? = single[Account.composite_money]

        inserted shouldBeEqualTo toInsert
    }

    @Suppress("UnusedReceiverParameter")
    private fun JdbcTransaction.assertInsertOfComponentValuesReturnsEquivalentOnSelect(toInsert: Money?) {
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
