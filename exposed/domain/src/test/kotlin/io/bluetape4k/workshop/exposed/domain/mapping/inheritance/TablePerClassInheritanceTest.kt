package io.bluetape4k.workshop.exposed.domain.mapping.inheritance

import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityID
import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.javatime.date
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate

class TablePerClassInheritanceTest: AbstractExposedTest() {

    abstract class AbstractBillingTable(name: String): TimebasedUUIDTable(name) {
        val owner = varchar("owner", 64).index()
        val swift = varchar("swift", 16)
    }

    /**
     * CreditCard Table
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS CREDIT_CARD (
     *      ID UUID PRIMARY KEY,
     *      OWNER VARCHAR(64) NOT NULL,
     *      SWIFT VARCHAR(16) NOT NULL,
     *      CARD_NUMBER VARCHAR(24) NOT NULL,
     *      COMPANY_NAME VARCHAR(128) NOT NULL,
     *      EXP_YEAR INT NOT NULL,
     *      EXP_MONTH INT NOT NULL,
     *      START_DATE DATE NOT NULL,
     *      END_DATE DATE NOT NULL
     * );
     *
     * CREATE INDEX CREDIT_CARD_OWNER ON CREDIT_CARD (OWNER);
     * ```
     */
    object CreditCardTable: AbstractBillingTable("credit_card") {
        val cardNumber = varchar("card_number", 24)
        val companyName = varchar("company_name", 128)
        val expYear = integer("exp_year")
        val expMonth = integer("exp_month")
        val startDate = date("start_date")
        val endDate = date("end_date")
    }

    /**
     * BankAccount Table
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS BANK_ACCOUNT (
     *      ID UUID PRIMARY KEY,
     *      OWNER VARCHAR(64) NOT NULL,
     *      SWIFT VARCHAR(16) NOT NULL,
     *      ACCOUNT_NUMBER VARCHAR(24) NOT NULL,
     *      BANK_NAME VARCHAR(128) NOT NULL
     * );
     *
     * CREATE INDEX BANK_ACCOUNT_OWNER ON BANK_ACCOUNT (OWNER);
     *```
     */
    object BankAccountTable: AbstractBillingTable("bank_account") {
        val accountNumber = varchar("account_number", 24)
        val bankName = varchar("bank_name", 128)
    }


    class CreditCard(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
        companion object: TimebasedUUIDEntityClass<CreditCard>(CreditCardTable)

        var owner by CreditCardTable.owner
        var swift by CreditCardTable.swift

        var cardNumber by CreditCardTable.cardNumber
        var companyName by CreditCardTable.companyName
        var expYear by CreditCardTable.expYear
        var expMonth by CreditCardTable.expMonth
        var startDate by CreditCardTable.startDate
        var endDate by CreditCardTable.endDate

        override fun equals(other: Any?): Boolean = other is CreditCard && id._value == other.id._value
        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String =
            "CreditCard(id=${id._value}, owner=$owner, cardNumber=$cardNumber)"
    }

    class BankAccount(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
        companion object: TimebasedUUIDEntityClass<BankAccount>(BankAccountTable)

        var owner by BankAccountTable.owner
        var swift by BankAccountTable.swift

        var accountNumber by BankAccountTable.accountNumber
        var bankName by BankAccountTable.bankName

        override fun equals(other: Any?): Boolean = other is BankAccount && id._value == other.id._value
        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String =
            "BankAccount(id=${id._value}, owner=$owner, accountNumber=$accountNumber)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `table per class inheritance`(testDB: TestDB) {
        withTables(testDB, BankAccountTable, CreditCardTable) {
            val account = BankAccount.new {
                owner = "debop"
                swift = "KODBKRSE"
                accountNumber = "123-456-7890"
                bankName = "Kookmin Bank"
            }

            val card = CreditCard.new {
                owner = "debop"
                swift = "KODBKRSE"
                cardNumber = "1234-5678-9012-3456"
                companyName = "VISA"
                expMonth = 12
                expYear = 2029
                startDate = LocalDate.now()
                endDate = LocalDate.now().plusYears(5)
            }

            entityCache.clear()

            val account2 = BankAccount.findById(account.id)!!
            account2 shouldBeEqualTo account

            val card2 = CreditCard.findById(card.id)!!
            card2 shouldBeEqualTo card

            // BankAccount Table을 삭제합니다.
            BankAccountTable.deleteAll()

            BankAccount.all().count() shouldBeEqualTo 0L
            CreditCard.all().count() shouldBeEqualTo 1L

            CreditCardTable.deleteAll()
            CreditCard.all().count() shouldBeEqualTo 0L
        }
    }
}
