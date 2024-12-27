package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.City
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.CityTable
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.Country
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.CountryTable
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.User
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.UserTable
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.UserToCityTable
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.EntityChange
import org.jetbrains.exposed.dao.EntityChangeType.Created
import org.jetbrains.exposed.dao.EntityChangeType.Removed
import org.jetbrains.exposed.dao.EntityChangeType.Updated
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.registeredChanges
import org.jetbrains.exposed.dao.toEntity
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.emptySized
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

class EntityHookTest: AbstractExposedTest() {

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
                City.new {
                    name = "Moscow"
                    country = ru
                }
            }

            events shouldHaveSize 2
            events.mapNotNull { it.toEntity(City)?.name } shouldBeEqualTo listOf("Moscow")
            events.mapNotNull { it.toEntity(Country)?.name } shouldBeEqualTo listOf("RU")
            events.all { it.transactionId == txId }.shouldBeTrue()
        }
    }

    @Test
    fun delete01() {
        withTables(*allTables) {
            val moscowId = transaction {
                val ru = Country.new { name = "RU" }
                val x = City.new {
                    name = "Moscow"
                    country = ru
                }
                flushCache()
                x.id
            }
            val (_, events, txId) = trackChanges {
                val moscow = City.findById(moscowId)!!
                moscow.delete()
            }

            events shouldHaveSize 1
            events.single().changeType shouldBeEqualTo Removed
            events.single().entityId shouldBeEqualTo moscowId
            events.single().transactionId shouldBeEqualTo txId
        }
    }

    @Test
    fun `modified simple 01`() {
        withTables(*allTables) {
            val (_, events1, txId) = trackChanges {
                val ru = Country.new { name = "RU" }
                City.new {
                    name = "Moscow"
                    country = ru
                }
            }

            events1 shouldHaveSize 2

            val (_, events2, txId2) = trackChanges {
                val de = Country.new { name = "DE" }
                val x = City.all().single()
                x.name = "Munich"
                x.country = de
            }

            // One may expect change for `RU` but we do not send it due to performance reasons
            events2 shouldHaveSize 2
            events2.mapNotNull { it.toEntity(City)?.name } shouldBeEqualTo listOf("Munich")
            events2.mapNotNull { it.toEntity(Country)?.name } shouldBeEqualTo listOf("DE")
            events2.all { it.transactionId == txId2 }.shouldBeTrue()
        }
    }

    @Test
    fun `modified inner table 01`() {
        withTables(*allTables) {
            transaction {
                val ru = Country.new { name = "RU" }
                val de = Country.new { name = "DE" }
                City.new { name = "Moscow"; country = ru }
                City.new { name = "Berlin"; country = de }
                User.new { name = "John"; age = 30 }

                flushCache()
            }

            val (_, events, txId) = trackChanges {
                val moscow = City.find { CityTable.name eq "Moscow" }.single()
                val john = User.all().single()
                john.cities = SizedCollection(listOf(moscow))
            }

            events shouldHaveSize 2
            events.mapNotNull { it.toEntity(City)?.name } shouldBeEqualTo listOf("Moscow")
            events.mapNotNull { it.toEntity(User)?.name } shouldBeEqualTo listOf("John")
            events.all { it.transactionId == txId }.shouldBeTrue()
        }
    }

    @Test
    fun `modified inner table 02`() {
        withTables(*allTables) {
            transaction {
                val ru = Country.new { name = "RU" }
                val de = Country.new { name = "DE" }
                val moscow = City.new { name = "Moscow"; country = ru }
                val berlin = City.new { name = "Berlin"; country = de }
                val john = User.new { name = "John"; age = 30 }

                john.cities = SizedCollection(listOf(berlin))
                flushCache()
            }

            val (_, event, txId) = trackChanges {
                val moscow = City.find { CityTable.name eq "Moscow" }.single()
                val john = User.all().single()
                john.cities = SizedCollection(listOf(moscow))
            }

            event shouldHaveSize 3
            event.mapNotNull { it.toEntity(City)?.name } shouldBeEqualTo listOf("Berlin", "Moscow")
            event.mapNotNull { it.toEntity(User)?.name } shouldBeEqualTo listOf("John")
            event.all { it.transactionId == txId }.shouldBeTrue()
        }
    }

    @Test
    fun `modified inner table 03`() {
        withTables(*allTables) {
            transaction {
                val ru = Country.new { name = "RU" }
                val de = Country.new { name = "DE" }
                val moscow = City.new { name = "Moscow"; country = ru }
                val berlin = City.new { name = "Berlin"; country = de }
                val john = User.new { name = "John"; age = 30 }

                john.cities = SizedCollection(listOf(moscow))
                flushCache()
            }

            val (_, event, txId) = trackChanges {
                val john = User.all().single()
                john.cities = emptySized()
            }

            event shouldHaveSize 2
            event.mapNotNull { it.toEntity(City)?.name } shouldBeEqualTo listOf("Moscow")
            event.mapNotNull { it.toEntity(User)?.name } shouldBeEqualTo listOf("John")
            event.all { it.transactionId == txId }.shouldBeTrue()
        }
    }

    @Test
    fun `single entity flush should trigger events`() {
        withTables(*allTables) {
            val (user, events, _) = trackChanges {
                User
                    .new { name = "John"; age = 30 }
                    .apply { flush() }
            }

            events shouldHaveSize 1
            val createEvent = events.single()
            createEvent.changeType shouldBeEqualTo Created
            createEvent.entityId shouldBeEqualTo user.id

            val (_, event2, _) = trackChanges {
                user.name = "Carl"
                user.flush()
            }

            user.name shouldBeEqualTo "Carl"
            event2 shouldHaveSize 1
            val updateEvent = event2.single()
            updateEvent.entityId shouldBeEqualTo user.id
            updateEvent.changeType shouldBeEqualTo Updated
        }
    }

    @Test
    fun `calling flush notifies entity hook subscribers`() {
        withTables(*allTables) {
            var hookCalls = 0
            val user = User.new {
                name = "1@test.local"
                age = 30
            }
            user.flush()

            EntityHook.subscribe {
                hookCalls++
            }

            user.name = "2@test.local"
            hookCalls shouldBeEqualTo 0

            user.flush()
            hookCalls shouldBeEqualTo 1

            user.name = "3@test.local"
            hookCalls shouldBeEqualTo 1

            user.flush()
            hookCalls shouldBeEqualTo 2
        }
    }
}
