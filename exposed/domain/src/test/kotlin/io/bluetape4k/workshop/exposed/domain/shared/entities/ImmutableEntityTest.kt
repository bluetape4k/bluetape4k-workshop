package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.dao.idValue
import io.bluetape4k.workshop.exposed.domain.shared.entities.ImmutableEntityTest.Schema.ECachedOrganization
import io.bluetape4k.workshop.exposed.domain.shared.entities.ImmutableEntityTest.Schema.EOrganization
import io.bluetape4k.workshop.exposed.domain.shared.entities.ImmutableEntityTest.Schema.Organization
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
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

/**
 * 읽기 전용 엔티티 사용 예
 */
class ImmutableEntityTest: AbstractExposedTest() {

    companion object: KLogging()

    object Schema {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS organization (
         *      id BIGSERIAL PRIMARY KEY,
         *      "name" VARCHAR(256) NOT NULL,
         *      etag BIGINT DEFAULT 0 NOT NULL
         * )
         * ```
         */
        object Organization: LongIdTable("organization") {
            val name = varchar("name", 256)
            val etag = long("etag").default(0)
        }

        /**
         * 읽기 전용 엔티티 사용 예
         *
         * 엔티티의 값을 변경하려면 forceUpdateEntity 메서드를 사용해야 한다.
         */
        class EOrganization(id: EntityID<Long>): LongEntity(id) {
            companion object: ImmutableEntityClass<Long, EOrganization>(Schema.Organization, EOrganization::class.java)

            val name: String by Schema.Organization.name
            val etag: Long by Schema.Organization.etag

            override fun equals(other: Any?): Boolean = other is EOrganization && idValue == other.idValue
            override fun hashCode(): Int = idValue.hashCode()
            override fun toString(): String = "EOrganization(id=$idValue, name=$name, etag=$etag)"
        }

        /**
         * 캐시된 엔티티 사용 예
         *
         * 엔티티의 값을 변경하려면 forceUpdateEntity 메서드를 사용해야 한다.
         */
        class ECachedOrganization(id: EntityID<Long>): LongEntity(id) {
            companion object: ImmutableCachedEntityClass<Long, ECachedOrganization>(
                Schema.Organization,
                ECachedOrganization::class.java
            )

            val name: String by Schema.Organization.name
            val etag: Long by Schema.Organization.etag

            override fun equals(other: Any?): Boolean = other is ECachedOrganization && idValue == other.idValue
            override fun hashCode(): Int = idValue.hashCode()
            override fun toString(): String = "ECachedOrganization(id=$idValue, name=$name, etag=$etag)"
        }
    }

    /**
     * `forceUpdateEntity` 메서드를 사용하여 Immutable Entity의 값을 변경할 수 있다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `immutable entity read after update`(testDB: TestDB) {
        withTables(testDB, Organization) {
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
                 * UPDATE organization SET etag=42 WHERE organization.id = 1
                 * ```
                 */
                EOrganization.forceUpdateEntity(org, Organization.etag, 42)

                /**
                 * 강제 업데이트된 정보를 DB로부터 읽어온다.
                 * ```sql
                 * SELECT organization.id, organization."name", organization.etag FROM organization
                 * ```
                 */
                EOrganization.all().single().etag shouldBeEqualTo 42L
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `immutable entity read after update with cached entity`(testDB: TestDB) {
        withTables(testDB, Organization) {
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

                /**
                 * Immutable Cached 엔티티를 강제로 업데이트
                 *
                 * ```sql
                 * UPDATE organization SET "name"='JetBrains Gmbh' WHERE organization.id = 1
                 * ```
                 */
                ECachedOrganization.forceUpdateEntity(org, Organization.name, "JetBrains Gmbh")

                /**
                 * DSL 을 이용하여 직접 업데이트한 경우 Cached 엔티티에 반영되지 않는다.
                 *
                 * ```sql
                 * UPDATE organization SET etag=1 WHERE organization.id = 1
                 * ```
                 */
                Organization.update({ Organization.id eq org.id }) {
                    it[etag] = 1
                }
                org.name shouldBeEqualTo "JetBrains Gmbh"
                org.etag shouldBeEqualTo 0L

                // Populate _cachedValues in ImmutableCachedEntityClass with inconsistent entity value
                // DB에서 다시 로드 시에는 값이 Update 된다.
                val org2 = ECachedOrganization.all().single()

                org2.name shouldBeEqualTo "JetBrains Gmbh"
                org2.etag shouldBeEqualTo 1L

                // 캐시된 엔티티는 update 되지 않는다.
                org.etag shouldNotBeEqualTo org2.etag
            }

            // 다른 Transaction 에서는 다른 캐시를 사용하므로 업데이트된 값을 읽어온다.
            transaction {
                // 모두 캐시되어 있으므로 실제 쿼리를 실행하지 않음
                val org = ECachedOrganization.all().single()

                org.name shouldBeEqualTo "JetBrains Gmbh"
                org.etag shouldBeEqualTo 1L
            }
        }
    }
}
