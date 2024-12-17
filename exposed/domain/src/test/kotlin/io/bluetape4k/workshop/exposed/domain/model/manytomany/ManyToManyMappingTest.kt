package io.bluetape4k.workshop.exposed.domain.model.manytomany

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.domain.AbstractExposedDomainTest
import io.bluetape4k.workshop.exposed.domain.runSuspendWithTables
import io.bluetape4k.workshop.exposed.domain.runWithTables
import io.bluetape4k.workshop.exposed.domain.schema.manytomany.GroupTable
import io.bluetape4k.workshop.exposed.domain.schema.manytomany.MemberTable
import io.bluetape4k.workshop.exposed.domain.schema.manytomany.UserTable
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.Test

class ManyToManyMappingTest: AbstractExposedDomainTest() {

    companion object: KLogging()

    @Test
    fun `generate schema`() {
        runWithTables(UserTable, GroupTable, MemberTable) {
            log.info { "Schema generated" }
            val users = UserTable.selectAll().where { UserTable.firstName eq "Alice" }.toList()
            // 현재는 데이터가 없으므로 빈 리스트가 반환된다.
            log.debug { "Users: $users" }
        }
    }

    @Test
    fun `coroutine support`() = runSuspendIO {
        runSuspendWithTables(UserTable, GroupTable, MemberTable) {
            val prevCount = User.all().count()
            User.new {
                username = faker.internet().username()
                firstName = faker.name().firstName()
                lastName = faker.name().lastName()
                status = UserStatus.ACTIVE
            }

            newSuspendedTransaction {
                User.new {
                    username = faker.internet().username()
                    firstName = faker.name().firstName()
                    lastName = faker.name().lastName()
                    status = UserStatus.INACTIVE
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

    @Test
    fun `SQL DSL 로부터 DAO Entity 만들기`() {
        runWithTables(UserTable, GroupTable, MemberTable) {
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
