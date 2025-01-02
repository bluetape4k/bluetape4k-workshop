package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.manytomany.UserStatus.ACTIVE
import io.bluetape4k.workshop.exposed.domain.mapping.manytomany.UserStatus.INACTIVE
import io.bluetape4k.workshop.exposed.domain.withSuspendedTables
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ManyToManyMappingTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `generate schema`(dialect: TestDB) {
        withTables(dialect, UserTable, GroupTable, MemberTable) {
            log.info { "Schema generated" }
            val users = UserTable.selectAll().where { UserTable.firstName eq "Alice" }.toList()
            // 현재는 데이터가 없으므로 빈 리스트가 반환된다.
            log.debug { "Users: $users" }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `coroutine support`(dialect: TestDB) = runSuspendIO {
        withSuspendedTables(dialect, UserTable, GroupTable, MemberTable) {
            val prevCount = User.all().count()
            User.new {
                username = faker.internet().username()
                firstName = faker.name().firstName()
                lastName = faker.name().lastName()
                status = ACTIVE
            }

            newSuspendedTransaction {
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `SQL DSL 로부터 DAO Entity 만들기`(dialect: TestDB) {
        withTables(dialect, UserTable, GroupTable, MemberTable) {
            val query = GroupTable
                .innerJoin(UserTable)
                .innerJoin(MemberTable)
                .select(GroupTable.columns)
                .withDistinct()

            val groups = Group.wrapRows(query).toList()

            log.debug { "Groups: $groups" }
        }
    }
}
