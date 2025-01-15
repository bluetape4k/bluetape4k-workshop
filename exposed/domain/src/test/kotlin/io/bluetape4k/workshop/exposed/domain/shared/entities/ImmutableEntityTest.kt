package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ImmutableEntityTest: AbstractExposedTest() {

    companion object: KLogging()

    object Schema {
        object Organization: LongIdTable() {
            val name = varchar("name", 256)
            val etag = long("etag").default(0)
        }

        class EOrganization(id: EntityID<Long>): LongEntity(id) {
            companion object: ImmutableEntityClass<Long, EOrganization>(Schema.Organization, EOrganization::class.java)

            val name: String by Schema.Organization.name
            val etag: Long by Schema.Organization.etag
        }

        class ECachedOrganization(id: EntityID<Long>): LongEntity(id) {
            /**
             * ImmutableCachedEntityClass 를 사용하면 엔티티의 값을 변경할 수 없다.
             * 엔티티의 값을 변경하려면 forceUpdateEntity 메서드를 사용해야 한다.
             */
            companion object: ImmutableCachedEntityClass<Long, ECachedOrganization>(
                Schema.Organization,
                ECachedOrganization::class.java
            )

            val name: String by Schema.Organization.name
            val etag: Long by Schema.Organization.etag
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `immutable entity read after update`(testDb: TestDB) {
        withTables(testDb, Schema.Organization) {
            transaction {
                Organization.insert {
                    it[name] = "JetBrains"
                    it[etag] = 0
                }
            }

            transaction {
                val org = EOrganization.all().single()

                /**
                 * Immutable 엔티티를 강제로 업데이트
                 * ```sql
                 * UPDATE "SCHEMA$ORGANIZATION" SET ETAG=1 WHERE "SCHEMA$ORGANIZATION".ID = 1
                 * ```
                 */
                EOrganization.forceUpdateEntity(org, Organization.etag, 42)

                /**
                 * 강제 업데이트된 정보를 DB로부터 읽어온다.
                 * ```sql
                 * SELECT "SCHEMA$ORGANIZATION".ID, "SCHEMA$ORGANIZATION"."name", "SCHEMA$ORGANIZATION".ETAG FROM "SCHEMA$ORGANIZATION"
                 * ```
                 */
                EOrganization.all().single().etag shouldBeEqualTo 42L
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `immutable entity read after update with cached entity`(testDb: TestDB) {
        withTables(testDb, Schema.Organization) {
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
                // 모두 캐시되어 있으므로 실제 쿼리를 실행하지 않음
                val org = ECachedOrganization.all().single()

                org.name shouldBeEqualTo "JetBrains Gmbh"
                org.etag shouldBeEqualTo 1L
            }
        }
    }
}
