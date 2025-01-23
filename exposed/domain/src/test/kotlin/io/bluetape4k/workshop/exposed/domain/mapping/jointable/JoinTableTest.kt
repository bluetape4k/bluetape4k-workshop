package io.bluetape4k.workshop.exposed.domain.mapping.jointable

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.jointable.JoinSchema.User
import io.bluetape4k.workshop.exposed.domain.mapping.jointable.JoinSchema.newUser
import io.bluetape4k.workshop.exposed.domain.mapping.jointable.JoinSchema.withJoinSchema
import io.bluetape4k.workshop.exposed.withDb
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.sql.SchemaUtils
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class JoinTableTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create join schema`(testDB: TestDB) {
        withDb(testDB) {
            try {
                SchemaUtils.create(*JoinSchema.allTables)
            } finally {
                SchemaUtils.drop(*JoinSchema.allTables)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create user with address by join table`(testDB: TestDB) {
        withJoinSchema(testDB) {
            val user = newUser()
            entityCache.clear()

            val loaded = User.findById(user.id)!!

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM JOIN_ADDRESS INNER JOIN USER_ADDRESS ON JOIN_ADDRESS.ID = USER_ADDRESS.ADDRESS_ID
             *  WHERE USER_ADDRESS.USER_ID = 1
             * ```
             */
            loaded.addresses.count() shouldBeEqualTo 2

            loaded.addresses.forEach { addr ->
                log.debug { "Address: $addr" }
            }

            /**
             * ```sql
             * SELECT USER_ADDRESS.ID, USER_ADDRESS.USER_ID, USER_ADDRESS.ADDRESS_ID, USER_ADDRESS.ADDR_TYPE
             *   FROM USER_ADDRESS WHERE USER_ADDRESS.USER_ID = 1
             *  ORDER BY USER_ADDRESS.ADDR_TYPE ASC
             *  ```
             */
            loaded.userAddresses.forEach { userAddr ->
                log.debug { "UserAddress: $userAddr, ${userAddr.user}, ${userAddr.address}" }
            }
        }
    }
}
