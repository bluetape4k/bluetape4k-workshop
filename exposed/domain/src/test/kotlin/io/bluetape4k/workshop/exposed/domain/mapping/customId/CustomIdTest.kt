package io.bluetape4k.workshop.exposed.domain.mapping.customId

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource


class CustomIdTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * Custom Type 의 Id 를 가지는 Table
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS emails (
     *      email_id VARCHAR(64) NOT NULL,
     *      "name" VARCHAR(255) NOT NULL,
     *      ssn CHAR(14) NOT NULL
     * );
     *
     * ALTER TABLE emails ADD CONSTRAINT emails_ssn_unique UNIQUE (ssn);
     * ```
     */
    object CustomIdTable: IdTable<Email>("emails") {
        override val id: Column<EntityID<Email>> = email("email_id").entityId()
        val name: Column<String> = varchar("name", 255)
        val ssn: Column<Ssn> = ssn("ssn").uniqueIndex()
    }


    class CustomIdEntity(id: EntityID<Email>): Entity<Email>(id) {
        companion object: EntityClass<Email, CustomIdEntity>(CustomIdTable)

        var name by CustomIdTable.name
        var ssn by CustomIdTable.ssn

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("name", name)
                .add("ssn", ssn)
                .toString()
    }

    private fun newEntity(): CustomIdEntity {
        val email = Email(faker.internet().emailAddress())
        val name = faker.name().name()
        val ssn = faker.idNumber().ssnValid()

        return CustomIdEntity.new(email) {
            this.name = name
            this.ssn = Ssn(ssn)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create schema`(testDB: TestDB) {
        withDb(testDB) {
            SchemaUtils.create(CustomIdTable)
            CustomIdTable.exists().shouldBeTrue()
            SchemaUtils.drop(CustomIdTable)
        }
    }

    /**
     * Custom Type 의 Id 를 가지는 Table 에 레코드를 저장합니다.
     *
     * ```sql
     * INSERT INTO emails (email_id, "name", ssn)
     * VALUES (bud.lindgren@gmail.com, 'Prince Ziemann', 706-24-2397)
     * ```
     * ```sql
     * SELECT emails.email_id,
     *        emails."name",
     *        emails.ssn
     *   FROM emails
     *  WHERE emails.email_id = bud.lindgren@gmail.com
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `save custom id record`(testDB: TestDB) {
        withTables(testDB, CustomIdTable) {
            val entityId = CustomIdTable.insertAndGetId { row ->
                row[CustomIdTable.id] = Email(faker.internet().emailAddress())
                row[CustomIdTable.name] = faker.name().name()
                row[CustomIdTable.ssn] = Ssn(faker.idNumber().ssnValid())
            }
            entityId.value.value.shouldNotBeNull()

            val row = CustomIdTable.selectAll()
                .where { CustomIdTable.id eq entityId }
                .single()

            row[CustomIdTable.id].value shouldBeEqualTo entityId.value
        }
    }

    /**
     * Custom Type 의 Id 를 가지는 Entity 를 저장합니다.
     *
     * ```sql
     * INSERT INTO emails (email_id, "name", ssn)
     * VALUES (marvin.reichert@gmail.com, 'Damon Dicki', 010-56-3259)
     * ```
     *
     * ```sql
     * SELECT emails.email_id,
     *        emails."name",
     *        emails.ssn
     *   FROM emails
     *  WHERE emails.email_id = marvin.reichert@gmail.com
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `save custom id entity`(testDB: TestDB) {
        withTables(testDB, CustomIdTable) {
            val entity = newEntity()
            entity.id.shouldNotBeNull()

            entityCache.clear()

            val loaded = CustomIdEntity.findById(entity.id)

            loaded shouldBeEqualTo entity
        }
    }

    /**
     * Custom Type 의 Id 를 가지는 Entity 를 저장하고, Ssn 속성으로 조회합니다.
     *
     * ```sql
     * SELECT emails.email_id,
     *        emails."name",
     *        emails.ssn
     *   FROM emails
     *  WHERE emails.ssn = 059-56-9626
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find by custom column type`(testDB: TestDB) {
        withTables(testDB, CustomIdTable) {
            val entity = newEntity()
            entity.id.shouldNotBeNull()

            entityCache.clear()

            val loaded = CustomIdEntity.find { CustomIdTable.ssn eq entity.ssn }.singleOrNull()
            log.debug { "loaded=$loaded" }
            loaded shouldBeEqualTo entity
        }
    }

    /**
     * Custom Type 의 Id 를 가지는 Entity 를 저장하고, Id 로 조회합니다.
     *
     * `inList` 나 `inSubQuery` 를 사용하여 여러 Id 로 조회할 수 있습니다.
     *
     * ```sql
     * SELECT emails.email_id,
     *        emails."name",
     *        emails.ssn
     *   FROM emails
     *  WHERE emails.email_id IN (joline.thiel@gmail.com,
     *                            bruna.rath@gmail.com,
     *                            collette.fahey@hotmail.com,
     *                            edgardo.parisian@yahoo.com,
     *                            emmy.gislason@yahoo.com)
     *  ORDER BY emails."name" ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find records by custom ids`(testDB: TestDB) {
        withTables(testDB, CustomIdTable) {
            val entities = List(5) { newEntity() }

            entityCache.clear()

            val rows = CustomIdTable.selectAll()
                .where { CustomIdTable.id inList entities.map { it.id } }
                .orderBy(CustomIdTable.name to SortOrder.ASC)

            val loaded = CustomIdEntity.wrapRows(rows).toList()

            loaded shouldBeEqualTo entities.sortedBy { it.name }
        }
    }

    /**
     * Custom Type 의 Id 를 가지는 Entity 를 저장하고, Id 로 조회합니다.
     *
     * ```sql
     * SELECT emails.email_id,
     *        emails."name",
     *        emails.ssn
     *   FROM emails
     *  WHERE emails.email_id IN (fiona.thompson@yahoo.com,
     *                            michael.cruickshank@gmail.com,
     *                            enrique.kertzmann@yahoo.com,
     *                            garland.rippin@gmail.com,
     *                            magdalena.gleason@gmail.com)
     *  ORDER BY emails."name" ASC
     * ```
     *
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find entities by custom ids`(testDB: TestDB) {
        withTables(testDB, CustomIdTable) {
            val entities = List(5) { newEntity() }

            entityCache.clear()

            val loaded = CustomIdEntity
                .find { CustomIdTable.id inList entities.map { it.id } }
                .orderBy(CustomIdTable.name to SortOrder.ASC)
                .toList()

            loaded shouldBeEqualTo entities.sortedBy { it.name }
        }
    }
}
