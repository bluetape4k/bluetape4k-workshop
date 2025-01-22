package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class OrderedReferenceTest: AbstractExposedTest() {

    companion object: KLogging()

    object Users: IntIdTable()

    object UserRatings: IntIdTable() {
        val value: Column<Int> = integer("value")
        val user: Column<EntityID<Int>> = reference("user", Users)
    }

    object UserNullableRatings: IntIdTable() {
        val value: Column<Int> = integer("value")
        val user: Column<EntityID<Int>?> = reference("user", Users).nullable()
    }

    class UserRatingDefaultOrder(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserRatingDefaultOrder>(UserRatings)

        var value by UserRatings.value
        var user by UserDefaultOrder referencedOn UserRatings.user
    }

    class UserNullableRatingDefaultOrder(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserNullableRatingDefaultOrder>(UserNullableRatings)

        var value by UserNullableRatings.value
        var user by UserDefaultOrder optionalReferencedOn UserNullableRatings.user
    }

    class UserDefaultOrder(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserDefaultOrder>(Users)

        val ratings: SizedIterable<UserRatingDefaultOrder>
                by UserRatingDefaultOrder referrersOn UserRatings.user orderBy UserRatings.value

        val nullableRatings: SizedIterable<UserNullableRatingDefaultOrder>
                by UserNullableRatingDefaultOrder optionalReferrersOn UserNullableRatings.user orderBy UserNullableRatings.value
    }

    private val unsortedRatingValues = listOf(0, 3, 1, 2, 4, 4, 5, 4, 5, 6, 9, 8)

    private fun withOrderedReferenceTestTables(testDB: TestDB, statement: Transaction.(TestDB) -> Unit) {
        withTables(testDB, Users, UserRatings, UserNullableRatings) {
            val userId = Users.insertAndGetId {}
            unsortedRatingValues.forEach { value ->
                UserRatings.insert {
                    it[user] = userId
                    it[UserRatings.value] = value
                }
                UserNullableRatings.insert {
                    it[user] = userId
                    it[UserNullableRatings.value] = value
                }
                UserNullableRatings.insert {
                    it[user] = null
                    it[UserNullableRatings.value] = value
                }
            }

            flushCache()

            statement(testDB)
        }
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `default order`(testDB: TestDB) {
        withOrderedReferenceTestTables(testDB) {
            val user = UserDefaultOrder.all().first()

            /**
             * Ratings are ordered by value in ascending order
             *
             * ```sql
             * SELECT USERRATINGS.ID, USERRATINGS."value", USERRATINGS."user"
             *   FROM USERRATINGS
             *  WHERE USERRATINGS."user" = 1 ORDER BY USERRATINGS."value" ASC
             * ```
             */
            unsortedRatingValues.sorted().zip(user.ratings).forEach { (value, rating) ->
                log.debug { "rating: ${rating.value}, value=$value" }
                rating.value shouldBeEqualTo value
            }

            /**
             * Nullable ratings are ordered by value in ascending order
             * and then by id in descending order
             *
             * ```sql
             * SELECT USERNULLABLERATINGS.ID, USERNULLABLERATINGS."value", USERNULLABLERATINGS."user"
             *  FROM USERNULLABLERATINGS
             * WHERE USERNULLABLERATINGS."user" = 1
             * ORDER BY USERNULLABLERATINGS."value" ASC
             * ```
             */
            unsortedRatingValues.sorted().zip(user.nullableRatings).forEach { (value, rating) ->
                log.debug { "rating: ${rating.value}, value=$value" }
                rating.value shouldBeEqualTo value
            }
        }
    }

    class UserRatingMultiColumn(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserRatingMultiColumn>(UserRatings)

        var value by UserRatings.value
        var user by UserMultiColumn referencedOn UserRatings.user
    }

    class UserNullableRatingMultiColumn(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserNullableRatingMultiColumn>(UserNullableRatings)

        var value by UserNullableRatings.value
        var user by UserMultiColumn optionalReferencedOn UserNullableRatings.user
    }

    class UserMultiColumn(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserMultiColumn>(Users)

        val ratings: SizedIterable<UserRatingMultiColumn> by UserRatingMultiColumn
            .referrersOn(UserRatings.user)
            .orderBy(UserRatings.value to SortOrder.DESC)
            .orderBy(UserRatings.id to SortOrder.DESC)

        val nullableRatings: SizedIterable<UserNullableRatingMultiColumn> by UserNullableRatingMultiColumn
            .optionalReferrersOn(UserNullableRatings.user)
            .orderBy(
                UserNullableRatings.value to SortOrder.DESC,
                UserNullableRatings.id to SortOrder.DESC
            )
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `multi column order`(testDB: TestDB) {
        withOrderedReferenceTestTables(testDB) {
            /**
             * ```sql
             * SELECT USERS.ID FROM USERS;
             *
             * SELECT USERRATINGS.ID, USERRATINGS."value", USERRATINGS."user"
             *   FROM USERRATINGS
             *  WHERE USERRATINGS."user" = 1
             *  ORDER BY USERRATINGS."value" DESC,
             *           USERRATINGS.ID DESC
             * ```
             */
            val ratings = UserMultiColumn.all().first().ratings.toList()

            /**
             * ```sql
             * SELECT USERS.ID FROM USERS;
             *
             * SELECT USERNULLABLERATINGS.ID, USERNULLABLERATINGS."value", USERNULLABLERATINGS."user"
             *   FROM USERNULLABLERATINGS
             *  WHERE USERNULLABLERATINGS."user" = 1
             * ORDER BY USERNULLABLERATINGS."value" DESC,
             *          USERNULLABLERATINGS.ID DESC
             * ```
             */
            val nullableRatings = UserMultiColumn.all().first().nullableRatings.toList()

            // value 가 내림차순의 순서로 정렬되어야 합니다.
            // value 가 같다면 ID 가 내림차순으로 정렬되어야 합니다.
            fun assertRatingsOrdered(current: UserRatingMultiColumn, prev: UserRatingMultiColumn) {
                current.value shouldBeLessOrEqualTo prev.value
                if (current.value == prev.value) {
                    current.id.value shouldBeLessOrEqualTo prev.id.value
                }
            }

            fun assertNullableRatingsOrdered(
                current: UserNullableRatingMultiColumn,
                prev: UserNullableRatingMultiColumn,
            ) {
                current.value shouldBeLessOrEqualTo prev.value
                if (current.value == prev.value) {
                    current.id.value shouldBeLessOrEqualTo prev.id.value
                }
            }

            for (i in 1 until ratings.size) {
                assertRatingsOrdered(ratings[i], ratings[i - 1])
            }
            for (i in 1 until nullableRatings.size) {
                assertNullableRatingsOrdered(nullableRatings[i], nullableRatings[i - 1])
            }
        }
    }

    class UserRatingChainedColumn(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserRatingChainedColumn>(UserRatings)

        var value by UserRatings.value
        var user by UserChainedColumn referencedOn UserRatings.user
    }

    class UserChainedColumn(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserChainedColumn>(Users)

        val ratings: SizedIterable<UserRatingChainedColumn> by UserRatingChainedColumn
            .referrersOn(UserRatings.user)
            .orderBy(UserRatings.value to SortOrder.DESC)
            .orderBy(UserRatings.id to SortOrder.DESC)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `chained orderBy`(testDB: TestDB) {
        withOrderedReferenceTestTables(testDB) {
            /**
             * ```sql
             * SELECT USERS.ID FROM USERS;
             *
             * SELECT USERRATINGS.ID, USERRATINGS."value", USERRATINGS."user"
             *   FROM USERRATINGS
             *  WHERE USERRATINGS."user" = 1
             *  ORDER BY USERRATINGS."value" DESC,
             *           USERRATINGS.ID DESC
             * ```
             */
            val ratings = UserChainedColumn.all().first().ratings.toList()

            // value 가 내림차순의 순서로 정렬되어야 합니다.
            // value 가 같다면 ID 가 내림차순으로 정렬되어야 합니다.
            fun assertRatingsOrdered(current: UserRatingChainedColumn, prev: UserRatingChainedColumn) {
                current.value shouldBeLessOrEqualTo prev.value
                if (current.value == prev.value) {
                    current.id.value shouldBeLessOrEqualTo prev.id.value
                }
            }

            for (i in 1 until ratings.size) {
                assertRatingsOrdered(ratings[i], ratings[i - 1])
            }
        }
    }
}
