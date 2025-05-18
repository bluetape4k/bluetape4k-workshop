package io.bluetape4k.workshop.exposed.sql.money

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.money.CompositeMoneyColumn
import org.jetbrains.exposed.sql.money.compositeMoney
import org.jetbrains.exposed.sql.money.nullable
import java.math.BigDecimal
import javax.money.CurrencyUnit
import javax.money.MonetaryAmount

internal const val AMOUNT_SCALE = 5

/**
 * Money 를 저장하는 테이블을 정의합니다.
 *
 * ```sql
 * CREATE TABLE IF NOT EXISTS accounts (
 *      id SERIAL PRIMARY KEY,
 *      composite_money DECIMAL(8, 5) NULL,     -- currency amount
 *      "composite_money_C" VARCHAR(3) NULL     -- currency unit
 * );
 * ```
 */
internal object Account: IntIdTable("Accounts") {
    val composite_money: CompositeMoneyColumn<BigDecimal?, CurrencyUnit?, MonetaryAmount?> =
        compositeMoney(8, AMOUNT_SCALE, "composite_money").nullable()
}

internal class AccountDao(id: EntityID<Int>): IntEntity(id) {
    companion object: EntityClass<Int, AccountDao>(Account)

    val money: MonetaryAmount? by Account.composite_money

    val amount: BigDecimal? by Account.composite_money.amount
    val currency: CurrencyUnit? by Account.composite_money.currency

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String =
        toStringBuilder()
            .add("money", money)
            .toString()
}
