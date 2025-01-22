package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.manytomany.UserStatus.ACTIVE
import io.bluetape4k.workshop.exposed.domain.mapping.manytomany.UserStatus.INACTIVE
import io.bluetape4k.workshop.exposed.withSuspendedTables
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ManyToManyMappingTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     *
     * Postgres:
     * ```sql
     * SELECT "User".id,
     *        "User".first_name,
     *        "User".last_name,
     *        "User".username,
     *        "User".status, "User".created_at
     *   FROM "User"
    WHERE "User".first_name = 'Alice'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `generate schema`(testDb: TestDB) {
        withTables(testDb, UserTable, GroupTable, MemberTable) {
            log.info { "Schema generated" }
            val users = UserTable.selectAll().where { UserTable.firstName eq "Alice" }.toList()
            // 현재는 데이터가 없으므로 빈 리스트가 반환된다.
            log.debug { "Users: $users" }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `coroutine support`(testDb: TestDB) = runSuspendIO {
        withSuspendedTables(testDb, UserTable, GroupTable, MemberTable) {
            val prevCount = User.all().count()

            // rollback()을 호출하면 transaction은 롤백된다.
            User.new {
                username = faker.internet().username()
                firstName = faker.name().firstName()
                lastName = faker.name().lastName()
                status = ACTIVE
            }

            newSuspendedTransaction {
                /**
                 * Postgres:
                 * ```sql
                 * INSERT INTO "User" (id, username, first_name, last_name, status)
                 * VALUES ('1efcc7ef-d259-69e9-9ff4-2d40c46f118c', 'julius.bartoletti', 'Angel', 'Wisoky', 2)
                 * ```
                 */
                /**
                 * Postgres:
                 * ```sql
                 * INSERT INTO "User" (id, username, first_name, last_name, status)
                 * VALUES ('1efcc7ef-d259-69e9-9ff4-2d40c46f118c', 'julius.bartoletti', 'Angel', 'Wisoky', 2)
                 * ```
                 */
                User.new {
                    username = faker.internet().username()
                    firstName = faker.name().firstName()
                    lastName = faker.name().lastName()
                    status = INACTIVE
                }
            }
            rollback()  // 내부의 transaction은 실행되고, 외부의 transaction은 롤백된다.

            User.all().forEach {
                log.debug { "User: $it" } // User: User(id=xxxx, username=xxxx, status=INACTIVE)
            }
            val currentCount = User.all().count()
            currentCount shouldBeEqualTo prevCount + 1
        }
    }

    /**
     * ```sql
     * SELECT DISTINCT "Group".ID,
     *        "Group"."name",
     *        "Group".DESCRIPTION,
     *        "Group".CREATED_AT,
     *        "Group".OWNER_ID
     *   FROM "Group" INNER JOIN "User" ON "User".ID = "Group".OWNER_ID
     *                INNER JOIN "Member" ON "Group".ID = "Member".GROUP_ID AND "User".ID = "Member".USER_ID
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `SQL DSL 로부터 DAO Entity 만들기`(testDb: TestDB) {
        withTables(testDb, UserTable, GroupTable, MemberTable) {
            val query: Query = GroupTable
                .innerJoin(UserTable)
                .innerJoin(MemberTable)
                .select(GroupTable.columns)
                .withDistinct()

            // Exposed SQL DSL로부터 DAO Entity 만들기
            val groups: List<Group> = Group.wrapRows(query).toList()

            groups.forEach {
                log.debug { "Group: $it" }
            }
        }
    }
}
