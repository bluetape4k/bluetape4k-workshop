package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SizedIterable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * one-to-many 관계에서 referrersOn, optionalReferrersOn 함수를 사용하여 참조되는 엔티티들을 정렬하는 방법을 설명합니다.
 */
class OrderedReferenceTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS users (id SERIAL PRIMARY KEY)
     * ```
     */
    object Users: IntIdTable()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS userratings (
     *      id SERIAL PRIMARY KEY,
     *      "value" INT NOT NULL,
     *      "user" INT NOT NULL,
     *
     *      CONSTRAINT fk_userratings_user__id FOREIGN KEY ("user") REFERENCES users(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object UserRatings: IntIdTable() {
        val value: Column<Int> = integer("value")
        val user: Column<EntityID<Int>> = reference("user", Users)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS usernullableratings (
     *      id SERIAL PRIMARY KEY,
     *      "value" INT NOT NULL,
     *      "user" INT NULL,
     *
     *      CONSTRAINT fk_usernullableratings_user__id FOREIGN KEY ("user")
     *      REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object UserNullableRatings: IntIdTable() {
        val value: Column<Int> = integer("value")
        val user: Column<EntityID<Int>?> = reference("user", Users).nullable()
    }

    class UserRatingDefaultOrder(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserRatingDefaultOrder>(UserRatings)

        var value by UserRatings.value
        var user by UserDefaultOrder referencedOn UserRatings.user

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("value", value)
                .toString()
    }

    class UserNullableRatingDefaultOrder(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserNullableRatingDefaultOrder>(UserNullableRatings)

        var value by UserNullableRatings.value
        var user by UserDefaultOrder optionalReferencedOn UserNullableRatings.user

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("value", value)
                .toString()
    }

    class UserDefaultOrder(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserDefaultOrder>(Users)

        val ratings: SizedIterable<UserRatingDefaultOrder>
                by UserRatingDefaultOrder referrersOn
                        UserRatings.user orderBy UserRatings.value

        val nullableRatings: SizedIterable<UserNullableRatingDefaultOrder>
                by UserNullableRatingDefaultOrder optionalReferrersOn
                        UserNullableRatings.user orderBy UserNullableRatings.value

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = toStringBuilder().toString()
    }

    private val unsortedRatingValues = listOf(0, 3, 1, 2, 4, 4, 5, 4, 5, 6, 9, 8)

    private fun withOrderedReferenceTestTables(testDB: TestDB, statement: JdbcTransaction.(TestDB) -> Unit) {
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

            entityCache.clear()

            statement(testDB)
        }
    }

    /**
     * Ratings are ordered by value in ascending order
     *
     * ```sql
     * SELECT userratings.id,
     *        userratings."value",
     *        userratings."user"
     *   FROM userratings
     *  WHERE userratings."user" = 1
     *  ORDER BY userratings."value" ASC;
     * ```
     *
     * Nullable ratings are ordered by value in ascending order
     * and then by id in descending order
     *
     * ```sql
     * SELECT usernullableratings.id,
     *        usernullableratings."value",
     *        usernullableratings."user"
     *   FROM usernullableratings
     *  WHERE usernullableratings."user" = 1
     *  ORDER BY usernullableratings."value" ASC;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `default order`(testDB: TestDB) {
        withOrderedReferenceTestTables(testDB) {
            val user = UserDefaultOrder.all().first()

            unsortedRatingValues.sorted().zip(user.ratings).forEach { (value, rating) ->
                log.debug { "rating: ${rating.value}, value=$value" }
                rating.value shouldBeEqualTo value
            }

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

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("value", value)
                .toString()
    }

    class UserNullableRatingMultiColumn(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserNullableRatingMultiColumn>(UserNullableRatings)

        var value by UserNullableRatings.value
        var user by UserMultiColumn optionalReferencedOn UserNullableRatings.user

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("value", value)
                .toString()
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

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = toStringBuilder().toString()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `multi column order`(testDB: TestDB) {
        withOrderedReferenceTestTables(testDB) {
            /**
             * ```sql
             * SELECT users.id FROM users;
             *
             * SELECT userratings.id,
             *        userratings."value",
             *        userratings."user"
             *   FROM userratings
             *  WHERE userratings."user" = 1
             *  ORDER BY userratings."value" DESC,
             *           userratings.id DESC;
             * ```
             */
            val ratings = UserMultiColumn.all().first().ratings.toList()

            /**
             * ```sql
             * SELECT users.id FROM users;
             *
             * SELECT usernullableratings.id,
             *        usernullableratings."value",
             *        usernullableratings."user"
             *   FROM usernullableratings
             *  WHERE usernullableratings."user" = 1
             *  ORDER BY usernullableratings."value" DESC,
             *           usernullableratings.id DESC;
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

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("value", value)
                .toString()
    }

    class UserChainedColumn(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserChainedColumn>(Users)

        val ratings: SizedIterable<UserRatingChainedColumn> by UserRatingChainedColumn
            .referrersOn(UserRatings.user)
            .orderBy(UserRatings.value to SortOrder.DESC)       // value DESC
            .orderBy(UserRatings.id to SortOrder.DESC)          // id DESC

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = toStringBuilder().toString()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `chained orderBy`(testDB: TestDB) {
        withOrderedReferenceTestTables(testDB) {
            /**
             * ```sql
             * SELECT USERS.ID FROM USERS;
             *
             * SELECT userratings.id,
             *        userratings."value",
             *        userratings."user"
             *   FROM userratings
             *  WHERE userratings."user" = 1
             *  ORDER BY userratings."value" DESC,
             *           userratings.id DESC;
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
