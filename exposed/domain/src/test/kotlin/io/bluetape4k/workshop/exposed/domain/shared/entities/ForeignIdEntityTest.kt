package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.dao.idValue
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldContainSame
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.entityCache
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

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS projects (
     *      id BIGSERIAL PRIMARY KEY,
     *      "name" VARCHAR(50) NOT NULL
     * )
     * ```
     */
    object Projects: LongIdTable("projects") {
        val name: Column<String> = varchar("name", 50)
    }

    /**
     * one-to-one relationship
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS project_configs (
     *      id BIGINT NOT NULL,
     *      setting BOOLEAN NOT NULL,
     *
     *      CONSTRAINT fk_project_configs_id__id FOREIGN KEY (id) REFERENCES projects(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object ProjectConfigs: IdTable<Long>("project_configs") {
        override val id: Column<EntityID<Long>> = reference("id", Projects)     // one-to-one relationship
        val setting: Column<Boolean> = bool("setting")
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS actors (
     *      guild_id VARCHAR(13) PRIMARY KEY
     * )
     * ```
     */
    object Actors: IdTable<String>("actors") {
        override val id: Column<EntityID<String>> = varchar("guild_id", 13).entityId()
        override val primaryKey = PrimaryKey(id)
    }

    /**
     * many-to-one relationship
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS roles (
     *      id SERIAL PRIMARY KEY,
     *      guild_id VARCHAR(13) NOT NULL,
     *
     *      CONSTRAINT fk_roles_guild_id__guild_id FOREIGN KEY (guild_id) REFERENCES actors(guild_id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Roles: IntIdTable("roles") {
        val actor: Column<EntityID<String>> = reference("guild_id", Actors)  // many-to-one relationship
    }

    class Project(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Project>(Projects)

        var name by Projects.name

        override fun equals(other: Any?): Boolean = other is Project && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Project(id=$idValue, name=$name)"
    }

    class ProjectConfig(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<ProjectConfig>(ProjectConfigs)

        var setting by ProjectConfigs.setting

        override fun equals(other: Any?): Boolean = other is ProjectConfig && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "ProjectConfig(id=$idValue, setting=$setting)"
    }

    class Actor(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, Actor>(Actors)

        val roles by Role referrersOn Roles.actor  // one-to-many relationship

        override fun equals(other: Any?): Boolean = other is Actor && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Actor(id=$id)"
    }

    class Role(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Role>(Roles)

        var actor by Actor referencedOn Roles.actor  // many-to-one relationship

        override fun equals(other: Any?): Boolean = other is Role && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Role(id=$id)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `foreign id entity update`(testDB: TestDB) {
        withTables(
            testDB,
            Projects, ProjectConfigs,
            configure = { useNestedTransactions = true }
        ) {
            val project1 = transaction {
                /**
                 * ```sql
                 * INSERT INTO projects ("name") VALUES ('Space');
                 * INSERT INTO project_configs (id, setting) VALUES (1, TRUE);
                 * ```
                 */
                val project1 = Project.new { name = "Space" }
                // ProjectConfig is a one-to-one relationship with Project
                ProjectConfig.new(project1.id.value) { setting = true }

                project1
            }

            val project2 = transaction {
                /**
                 * ```sql
                 * INSERT INTO projects ("name") VALUES ('Earth');
                 * INSERT INTO project_configs (id, setting) VALUES (2, TRUE);
                 * ```
                 */
                val project2 = Project.new { name = "Earth" }
                ProjectConfig.new(project2.id.value) { setting = true }
                project2
            }

            transaction {
                ProjectConfig.findById(project1.id)!!.setting = false
            }

            transaction {
                ProjectConfig.findById(project1.id)!!.setting.shouldBeFalse()
            }
        }
    }

    /**
     * ```sql
     * SELECT roles.id, roles.guild_id
     *   FROM roles
     *  WHERE roles.guild_id = '3746529'
     * ```
     *
     * ```sql
     * SELECT roles.id, roles.guild_id FROM roles
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `referenced entities with identitical column names`(testDB: TestDB) {
        withTables(testDB, Actors, Roles) {
            val actorA = Actor.new("3746529") { }
            val roleA = Role.new { actor = actorA }
            val roleB = Role.new { actor = actorA }

            entityCache.clear()

            actorA.roles.toList() shouldContainSame listOf(roleA, roleB)

            Role.all().toList() shouldContainSame listOf(roleA, roleB)
        }
    }
}
