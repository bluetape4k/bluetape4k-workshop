package io.bluetape4k.workshop.exposed.domain.mapping.associations.jointable

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.associations.jointable.JoinSchema.User
import io.bluetape4k.workshop.exposed.domain.mapping.associations.jointable.JoinSchema.newUser
import io.bluetape4k.workshop.exposed.domain.mapping.associations.jointable.JoinSchema.withJoinSchema
import io.bluetape4k.workshop.exposed.withDb
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
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

    /**
     *
     * Count of `loaded.addresses` is 2.
     * ```sql
     * SELECT COUNT(*)
     *   FROM join_address INNER JOIN user_address ON join_address.id = user_address.address_id
     *  WHERE user_address.user_id = 1
     * ```
     *
     * loaded.addresses
     * ```sql
     * SELECT join_address.id,
     *        join_address.street,
     *        join_address.city,
     *        join_address.zipcode,
     *        user_address.id,
     *        user_address.address_id,
     *        user_address.addr_type,
     *        user_address.user_id
     *   FROM join_address INNER JOIN user_address ON join_address.id = user_address.address_id
     *  WHERE user_address.user_id = 1
     * ```
     *
     * loaded.userAddresses
     * ```sql
     * SELECT user_address.id,
     *        user_address.user_id,
     *        user_address.address_id,
     *        user_address.addr_type
     *   FROM user_address
     *  WHERE user_address.user_id = 1
     *  ORDER BY user_address.addr_type ASC
     * ```
     *
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create user with address by join table`(testDB: TestDB) {
        withJoinSchema(testDB) {
            val user = newUser()
            entityCache.clear()

            val loaded = User.findById(user.id)!!

            loaded.addresses.count() shouldBeEqualTo 2

            loaded.addresses.forEach { addr ->
                log.debug { "Address: $addr" }
            }

            loaded.userAddresses.forEach { userAddr ->
                log.debug { "UserAddress: $userAddr, ${userAddr.user}, ${userAddr.address}" }
            }
        }
    }
}
