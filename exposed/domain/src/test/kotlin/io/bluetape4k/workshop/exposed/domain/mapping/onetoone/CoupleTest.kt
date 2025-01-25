package io.bluetape4k.workshop.exposed.domain.mapping.onetoone

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CoupleTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS HUSBAND (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      WIFE_ID INT NULL
     * )
     * ```
     */
    object HusbandTable: IntIdTable("husband") {
        val name = varchar("name", 255)
        val wifeId = optReference("wife_id", WifeTable)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS WIFE (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * )
     * ```
     */
    object WifeTable: IntIdTable("wife") {
        val name = varchar("name", 255)
    }

    class Husband(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Husband>(HusbandTable)

        var name by HusbandTable.name
        var wife by Wife optionalReferencedOn HusbandTable.wifeId

        override fun equals(other: Any?): Boolean = other is Husband && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Husband(id=$id, name=$name)"
    }

    class Wife(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Wife>(WifeTable)

        var name by WifeTable.name

        /**
         * one-to-one bidirectional (wife -> husband)
         */
        val husband by Husband optionalBackReferencedOn HusbandTable.wifeId

        override fun equals(other: Any?): Boolean = other is Wife && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Wife(id=$id, name=$name)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-one bidirectional`(testDB: TestDB) {
        withTables(testDB, HusbandTable, WifeTable) {
            val wife = Wife.new {
                name = "Jane"
            }
            val husband = Husband.new {
                this.name = "John"
                this.wife = wife
            }
            entityCache.clear()

            husband.wife shouldBeEqualTo wife
            wife.husband shouldBeEqualTo husband

            entityCache.clear()

            val husband2 = Husband.findById(husband.id)!!
            husband2 shouldBeEqualTo husband
            husband2.wife shouldBeEqualTo wife

            // husband 를 삭제해도 wife 는 삭제되지 않는다. (cascade restrict 이다)
            val wife2 = husband2.wife!!
            husband2.delete()
            Wife.findById(wife2.id).shouldNotBeNull()

            wife2.delete()

            entityCache.clear()

            // SELECT COUNT(HUSBAND.ID) FROM HUSBAND
            Husband.count() shouldBeEqualTo 0

            // SELECT COUNT(WIFE.ID) FROM WIFE
            Wife.count() shouldBeEqualTo 0
        }
    }
}
