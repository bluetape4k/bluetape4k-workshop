package io.bluetape4k.workshop.exposed.domain.mapping.inheritance

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.javatime.date
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SingleTableInheritanceTest: AbstractExposedTest() {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS BILLING (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      OWNER VARCHAR(255) NOT NULL,
     *      SWIFT VARCHAR(255) NOT NULL,
     *
     *      DTYPE VARCHAR(31) DEFAULT 'UNKNOWN' NOT NULL,
     *
     *      CARD_NUMBER VARCHAR(32) NULL,
     *      COMPANY_NAME VARCHAR(255) NULL,
     *      EXP_MONTH INT NULL,
     *      EXP_YEAR INT NULL,
     *      START_DATE DATE NULL,
     *      END_DATE DATE NULL,
     *
     *      ACCOUNT_NUMBER VARCHAR(255) NULL,
     *      BANK_NAME VARCHAR(255) NULL
     * );
     *
     * CREATE INDEX BILLING_OWNER ON BILLING (OWNER);
     * ```
     */
    object BillingTable: IntIdTable("billing") {

        val owner = varchar("owner", 255).index()
        val swift = varchar("swift", 255)

        val dtype = enumerationByName<BillingType>("dtype", 31).default(BillingType.UNKNOWN)

        // CreditCard
        val cardNumber = varchar("card_number", 32).nullable()
        val companyName = varchar("company_name", 255).nullable()
        val expMonth = integer("exp_month").nullable()
        val expYear = integer("exp_year").nullable()
        val startDate = date("start_date").nullable()
        val endDate = date("end_date").nullable()

        // BankAccount
        val accountNumber = varchar("account_number", 255).nullable()
        val bankName = varchar("bank_name", 255).nullable()
    }

    enum class BillingType {
        UNKNOWN,
        CREDIT_CARD,
        BANK_ACCOUNT
    }

    abstract class Billing(id: EntityID<Int>): IntEntity(id) {
        var owner by BillingTable.owner
        var swift by BillingTable.swift

        var dtype: BillingType by BillingTable.dtype

        override fun equals(other: Any?): Boolean =
            other is Billing && id._value == other.id._value

        override fun hashCode(): Int = id._value.hashCode()
    }

    class CreditCard(id: EntityID<Int>): Billing(id) {
        companion object: IntEntityClass<CreditCard>(BillingTable) {
            override fun new(id: Int?, init: CreditCard.() -> Unit): CreditCard {
                return super.new(id, init).apply {
                    dtype = BillingType.CREDIT_CARD
                }
            }

            fun countCreditCard(): Long = super.count(BillingTable.dtype eq BillingType.CREDIT_CARD)
        }

        var cardNumber by BillingTable.cardNumber
        var companyName by BillingTable.companyName
        var expMonth by BillingTable.expMonth
        var expYear by BillingTable.expYear
        var startDate by BillingTable.startDate
        var endDate by BillingTable.endDate

        override fun equals(other: Any?): Boolean =
            other is CreditCard && super.equals(other) && cardNumber == other.cardNumber

        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String {
            return ToStringBuilder(this)
                .add("id", id)
                .add("owner", owner)
                .add("cardNumber", cardNumber)
                .toString()
        }
    }

    class BankAccount(id: EntityID<Int>): Billing(id) {
        companion object: IntEntityClass<BankAccount>(BillingTable) {
            override fun new(id: Int?, init: BankAccount.() -> Unit): BankAccount {
                return super.new(id, init).apply {
                    dtype = BillingType.BANK_ACCOUNT
                }
            }

            fun countBankAccount(): Long = super.count(BillingTable.dtype eq BillingType.BANK_ACCOUNT)
        }

        var accountNumber by BillingTable.accountNumber
        var bankName by BillingTable.bankName

        override fun equals(other: Any?): Boolean =
            other is BankAccount && super.equals(other) && accountNumber == other.accountNumber

        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String {
            return ToStringBuilder(this)
                .add("id", id)
                .add("owner", owner)
                .add("accountNumber", accountNumber)
                .toString()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `single table with tow type entities`(testDB: TestDB) {
        withTables(testDB, BillingTable) {
            val account = BankAccount.new {
                owner = "debop"
                swift = "NACFKRSE"
                accountNumber = "123-456-7890"
                bankName = "Kookmin Bank"
            }

            val card = CreditCard.new {
                owner = "debop"
                swift = "NACFKRSE"
                cardNumber = "1234-5678-9012-3456"
                companyName = "VISA"
                expMonth = 12
                expYear = 2023
            }

            entityCache.clear()

            val account2 = BankAccount.findById(account.id)!!
            account2 shouldBeEqualTo account

            val card2 = CreditCard.findById(card.id)!!
            card2 shouldBeEqualTo card

            // BankAccount 만 삭제 
            BillingTable.deleteWhere { BillingTable.dtype eq BillingType.BANK_ACCOUNT }
            BankAccount.count(BillingTable.dtype eq BillingType.BANK_ACCOUNT) shouldBeEqualTo 0L
            BankAccount.countBankAccount() shouldBeEqualTo 0L

            // 같은 테이블이지만, bank account의 모든 정보가 삭제되어도, credit card 정보는 남아있다
            CreditCard.count() shouldBeEqualTo 1L

            BillingTable.deleteWhere { BillingTable.dtype eq BillingType.CREDIT_CARD }
            CreditCard.countCreditCard() shouldBeEqualTo 0L
        }
    }
}
