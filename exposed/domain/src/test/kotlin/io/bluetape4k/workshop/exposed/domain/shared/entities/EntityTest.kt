package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.junit.jupiter.api.Test

object EntityTestData {

    object YTable: IdTable<String>("YTable") {
        override val id: Column<EntityID<String>> = varchar("uuid", 24).entityId()
            .clientDefault { EntityID(TimebasedUuid.nextBase62String(), YTable) }

        val x = bool("x").default(true)

        override val primaryKey = PrimaryKey(id)
    }

    object XTable: IntIdTable("XTable") {
        val b1 = bool("b1").default(true)
        val b2 = bool("b2").default(false)
        val y1 = optReference("y1", YTable)
    }

    class XEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<XEntity>(XTable)

        var b1 by XTable.b1
        var b2 by XTable.b2

        override fun toString(): String = "XEntity(id=$id, b1=$b1, b2=$b2)"
    }

    enum class XType {
        A, B
    }

    open class AEntity(id: EntityID<Int>): IntEntity(id) {
        var b1 by XTable.b1

        companion object: IntEntityClass<AEntity>(XTable) {
            fun create(b1: Boolean, type: XType): AEntity {
                val init: AEntity.() -> Unit = {
                    this.b1 = b1
                }
                val answer = when (type) {
                    XType.B -> BEntity.create { init() }
                    else    -> new { init() }
                }
                return answer
            }
        }

        override fun toString(): String = "AEntity(id=$id, b1=$b1)"
    }

    open class BEntity(id: EntityID<Int>): AEntity(id) {
        var b2 by XTable.b2
        var y by YEntity optionalReferencedOn XTable.y1

        companion object: IntEntityClass<BEntity>(XTable) {
            fun create(init: AEntity.() -> Unit): BEntity {
                val answer = new { init() }
                return answer
            }
        }

        override fun toString(): String = "BEntity(id=$id, b1=$b1, b2=$b2)"
    }

    class YEntity(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, YEntity>(YTable)

        var x by YTable.x
        val b: BEntity? by BEntity.backReferencedOn(XTable.y1)

        override fun toString(): String = "YEntity(id=$id, x=$x)"
    }
}

class EntityTest: AbstractExposedTest() {

    companion object: KLogging()

    @Test
    fun `defaults 01`() {
        withTables(EntityTestData.YTable, EntityTestData.XTable) {
            val x = EntityTestData.XEntity.new { }
            x.b1.shouldBeTrue()
            x.b2.shouldBeFalse()
        }
    }

    @Test
    fun `defaults 02`() {
        withTables(EntityTestData.YTable, EntityTestData.XTable) {
            val a = EntityTestData.AEntity.create(false, EntityTestData.XType.A)
            val b = EntityTestData.AEntity.create(false, EntityTestData.XType.B) as EntityTestData.BEntity
            val y = EntityTestData.YEntity.new { x = false }

            a.b1.shouldBeFalse()
            b.b1.shouldBeFalse()
            b.b2.shouldBeFalse()

            b.y = y

            b.y!!.x.shouldBeFalse()
            y.b.shouldNotBeNull()
        }
    }

    @Test
    fun `text field outside the transaction`() {
        val objectsToVerify = arrayListOf<Pair<Human, TestDB>>()

        withTables(Humans) { testDb ->
            val y1 = Human.new { h = "foo" }

            flushCache()
            y1.refresh(flush = false)

            objectsToVerify.add(y1 to testDb)
        }

        objectsToVerify.forEach { (human, testDb) ->
            log.debug { "Verifying $human in $testDb" }
            human.h shouldBeEqualTo "foo"
        }
    }

    @Test
    fun `new with id and refresh`() {
        val objectsToVerify = arrayListOf<Pair<Human, TestDB>>()

        withTables(Humans) { testDb ->
            val x = Human.new(2) { h = "foo" }
            x.refresh(flush = true)
            objectsToVerify.add(x to testDb)
        }

        objectsToVerify.forEach { (human, testDb) ->
            log.debug { "Verifying $human in $testDb" }
            human.h shouldBeEqualTo "foo"
            human.id.value shouldBeEqualTo 2
        }
    }


    object Humans: IntIdTable("human") {
        val h = text("h", eagerLoading = true)
    }

    object Users: IdTable<Int>("user") {
        override val id = reference("id", Humans)
        val name = text("name")
    }

    open class Human(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Human>(Humans)

        var h by Humans.h

        override fun toString(): String = "Human(id=$id, h=$h)"
    }

    open class User(id: EntityID<Int>): Entity<Int>(id) {
        companion object: EntityClass<Int, User>(Users) {
            operator fun invoke(name: String): User {
                val h = Human.new { h = name.take(2) }
                return User.new(h.id.value) {
                    this.name = name
                }
            }
        }

        val human: Human by Human referencedOn Users.id
        var name: String by Users.name

        override fun toString(): String = "User(id=$id, name=$name, human=$human)"
    }
}
