package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
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
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

class ForeignIdEntityTest: AbstractExposedTest() {

    companion object: KLogging()

    object Schema {

        object Projects: LongIdTable() {
            val name = varchar("name", 50)
        }

        // one-to-one relationship
        object ProjectConfigs: IdTable<Long>() {
            override val id = reference("id", Projects)
            val setting = bool("setting")
        }

        object Actors: IdTable<String>("actors") {
            override val id = varchar("guild_id", 13).entityId()
            override val primaryKey = PrimaryKey(id)
        }

        object Roles: IntIdTable("roles") {
            val actor = reference("guild_id", Actors)
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

        val roles by Role referrersOn Schema.Roles.actor
    }

    class Role(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Role>(Schema.Roles)

        var actor by Actor referencedOn Schema.Roles.actor
    }

    @Test
    fun `foreign id entity update`() {
        withTables(
            Schema.Projects, Schema.ProjectConfigs,
            configure = { useNestedTransactions = true }
        ) {
            transaction {
                val projectId = Project.new { name = "Space" }.id.value
                ProjectConfig.new(projectId) { setting = true }
            }

            transaction {
                ProjectConfig.all().first().setting = false
            }

            transaction {
                ProjectConfig.all().first().setting.shouldBeFalse()
            }
        }
    }

    @Test
    fun `referenced entities with identitical column names`() {
        withTables(Schema.Actors, Schema.Roles) {
            val actorA = Actor.new("3746529") { }
            val roleA = Role.new { actor = actorA }
            val roleB = Role.new { actor = actorA }

            flushCache()

            actorA.roles.toList() shouldBeEqualTo listOf(roleA, roleB)
        }
    }
}
