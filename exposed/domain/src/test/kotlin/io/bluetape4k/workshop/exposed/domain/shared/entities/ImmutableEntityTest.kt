package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.shared.entities.ImmutableEntityTest.Schema.ECachedOrganization
import io.bluetape4k.workshop.exposed.domain.shared.entities.ImmutableEntityTest.Schema.EOrganization
import io.bluetape4k.workshop.exposed.domain.shared.entities.ImmutableEntityTest.Schema.Organization
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.ImmutableCachedEntityClass
import org.jetbrains.exposed.dao.ImmutableEntityClass
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test

class ImmutableEntityTest: AbstractExposedTest() {

    object Schema {
        object Organization: LongIdTable() {
            val name = varchar("name", 256)
            val etag = long("etag").default(0)
        }

        class EOrganization(id: EntityID<Long>): LongEntity(id) {
            companion object: ImmutableEntityClass<Long, EOrganization>(Schema.Organization, EOrganization::class.java)

            val name by Schema.Organization.name
            val etag by Schema.Organization.etag
        }

        class ECachedOrganization(id: EntityID<Long>): LongEntity(id) {
            companion object: ImmutableCachedEntityClass<Long, ECachedOrganization>(
                Schema.Organization,
                ECachedOrganization::class.java
            )

            val name by Schema.Organization.name
            val etag by Schema.Organization.etag
        }
    }

    @Test
    fun `immutable entity read after update`() {
        withTables(Schema.Organization) {
            transaction {
                Organization.insert {
                    it[name] = "JetBrains"
                    it[etag] = 0
                }
            }

            transaction {
                val org = EOrganization.all().single()

                // Immutable 엔티티를 강제로 업데이트
                EOrganization.forceUpdateEntity(org, Organization.etag, 1)

                EOrganization.all().single().etag shouldBeEqualTo 1L
            }
        }
    }

    @Test
    fun `immutable entity read after update with cached entity`() {
        withTables(Schema.Organization) {
            transaction {
                Organization.insert {
                    it[name] = "JetBrains"
                    it[etag] = 0
                }
            }
            transaction {
                Organization.update {
                    it[name] = "JetBrains Inc."
                }
            }

            transaction {
                val org = ECachedOrganization.all().single()

                // Immutable Cached 엔티티를 강제로 업데이트
                ECachedOrganization.forceUpdateEntity(org, Organization.name, "JetBrains Gmbh")

                Organization.update({ Organization.id eq org.id }) {
                    it[etag] = 1
                }

                // 테이블에 직접 Update 한 것은 Cached 엔티티에 반영되지 않음
                org.name shouldBeEqualTo "JetBrains Gmbh"
                org.etag shouldBeEqualTo 0L

                // Populate _cachedValues in ImmutableCachedEntityClass with inconsistent entity value
                val org2 = ECachedOrganization.all().single()

                org2.name shouldBeEqualTo "JetBrains Gmbh"
                org2.etag shouldBeEqualTo 1L
            }

            transaction {
                val org = ECachedOrganization.all().single()

                org.name shouldBeEqualTo "JetBrains Gmbh"
                org.etag shouldBeEqualTo 1L
            }
        }
    }
}
