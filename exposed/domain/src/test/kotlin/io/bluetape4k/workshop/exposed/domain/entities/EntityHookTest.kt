package io.bluetape4k.workshop.exposed.domain.entities

import io.bluetape4k.workshop.exposed.domain.AbstractExposedDomainTest
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.City
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.CityTable
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.Country
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.CountryTable
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.UserTable
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.UserToCityTable
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.EntityChange
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.registeredChanges
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

class EntityHookTest: AbstractExposedDomainTest() {

    private val allTables = arrayOf(UserTable, CityTable, UserToCityTable, CountryTable)

    private fun <T> trackChanges(statement: Transaction.() -> T): Triple<T, Collection<EntityChange>, String> {
        val alreadyChanged = TransactionManager.current().registeredChanges().size
        return transaction {
            val result = statement()
            flushCache()
            Triple(result, registeredChanges().drop(alreadyChanged), id)
        }
    }

    @Test
    fun created01() {
        withTables(*allTables) {
            val (_, events, txId) = trackChanges {
                val ru = Country.new { name = "RU" }
                val moscow = City.new {
                    name = "Moscow"
                    country = ru
                }
            }
            events shouldHaveSize 2
        }
    }
}
