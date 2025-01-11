package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ForeignIdEntityTest: AbstractExposedTest() {

    companion object: KLogging()

    object Schema {

        object Projects: LongIdTable() {
            val name: Column<String> = varchar("name", 50)
        }

        // one-to-one relationship
        object ProjectConfigs: IdTable<Long>() {
            override val id: Column<EntityID<Long>> = reference("id", Projects)     // one-to-one relationship
            val setting: Column<Boolean> = bool("setting")
        }

        object Actors: IdTable<String>("actors") {
            override val id: Column<EntityID<String>> = varchar("guild_id", 13).entityId()
            override val primaryKey = PrimaryKey(id)
        }

        object Roles: IntIdTable("roles") {
            val actor: Column<EntityID<String>> = reference("guild_id", Actors)
        }
    }

    class Project(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Project>(Schema.Projects)

        var name by Schema.Projects.name
    }

    class ProjectConfig(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<ProjectConfig>(Schema.ProjectConfigs)

        var setting by Schema.ProjectConfigs.setting
    }

    class Actor(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, Actor>(Schema.Actors)

        val roles by Role referrersOn Schema.Roles.actor  // one-to-many relationship
    }

    class Role(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Role>(Schema.Roles)

        var actor by Actor referencedOn Schema.Roles.actor  // many-to-one relationship
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `foreign id entity update`(testDB: TestDB) {
        withTables(
            testDB,
            Schema.Projects, Schema.ProjectConfigs,
            configure = { useNestedTransactions = true }
        ) {
            transaction {
                /**
                 * ```sql
                 * INSERT INTO "SCHEMA$PROJECTS" ("name") VALUES ('Space')
                 * INSERT INTO "SCHEMA$PROJECTCONFIGS" (ID, SETTING) VALUES (1, TRUE)
                 * ```
                 */
                val projectId = Project.new { name = "Space" }.id.value
                // ProjectConfig is a one-to-one relationship with Project
                ProjectConfig.new(projectId) { setting = true }

                /**
                 * ```sql
                 * INSERT INTO "SCHEMA$PROJECTS" ("name") VALUES ('Earth')
                 * INSERT INTO "SCHEMA$PROJECTCONFIGS" (ID, SETTING) VALUES (2, TRUE)
                 * ```
                 */
                val project2 = Project.new { name = "Earth" }
                ProjectConfig.new(project2.id.value) { setting = true }
            }

            transaction {
                ProjectConfig.all().first().setting = false
            }

            transaction {
                ProjectConfig.all().first().setting.shouldBeFalse()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `referenced entities with identitical column names`(testDB: TestDB) {
        withTables(testDB, Schema.Actors, Schema.Roles) {
            val actorA = Actor.new("3746529") { }
            val roleA = Role.new { actor = actorA }
            val roleB = Role.new { actor = actorA }

            flushCache()

            /**
             * ```sql
             * SELECT ROLES.ID, ROLES.GUILD_ID FROM ROLES WHERE ROLES.GUILD_ID = '3746529'
             * ```
             */
            actorA.roles.toList() shouldBeEqualTo listOf(roleA, roleB)
        }
    }
}
