package io.bluetape4k.workshop.exposed.domain.h2


import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.Cities
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.City
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.User
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.Users
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Board
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Boards
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Categories
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Post
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Posts

import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.BEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XTable
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.YEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.YTable
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.load
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import kotlin.properties.Delegates
import kotlin.test.assertFails

class EntityReferenceCacheTest: AbstractExposedTest() {

    companion object: KLogging()

    private val db by lazy { TestDB.H2.connect() }

    private val dbWithCache by lazy {
        TestDB.H2.connect {
            /**
             * Turns on "mode" for Exposed DAO to store relations (after they were loaded) within the entity that will
             * allow access to them outside the transaction.
             * Useful when [eager loading](https://github.com/JetBrains/Exposed/wiki/DAO#eager-loading) is used.
             */
            keepLoadedReferencesOutOfTransaction = true
        }
    }

    private fun executeOnH2(vararg tables: Table, body: () -> Unit) {
        Assumptions.assumeTrue { TestDB.H2 in TestDB.enabledDialects() }
        var testWasStarted = false
        transaction(db) {
            SchemaUtils.create(*tables)
            testWasStarted = true
        }
        Assumptions.assumeTrue(testWasStarted)
        if (testWasStarted) {
            try {
                body()
            } finally {
                transaction(db) {
                    SchemaUtils.drop(*tables)
                }
            }
        }
    }

    @Test
    fun `referenceOn works out of transaction`() {
        var y1: YEntity by Delegates.notNull()
        var b1: BEntity by Delegates.notNull()

        executeOnH2(XTable, YTable) {
            transaction(db) {
                y1 = YEntity.new { this.x = true }
                b1 = BEntity.new {
                    this.b1 = true
                    this.y = y1
                }
            }
            assertFails { y1.b }
            assertFails { b1.y }

            transaction(dbWithCache) {
                y1.refresh()
                b1.refresh()
                y1.b?.id shouldBeEqualTo b1.id
                b1.y?.id shouldBeEqualTo y1.id
            }

            y1.b?.id shouldBeEqualTo b1.id
            b1.y?.id shouldBeEqualTo y1.id
        }
    }

    @Test
    fun `referenceOn works out of transaction via with`() {
        var b1: Board by Delegates.notNull()
        var p1: Post by Delegates.notNull()
        var p2: Post by Delegates.notNull()

        executeOnH2(Boards, Posts, Categories) {
            transaction(db) {
                b1 = Board.new { this.name = "test-board" }
                p1 = Post.new { board = b1 }
                p2 = Post.new { board = b1 }
            }
            assertFails { b1.posts.toList() }
            assertFails { p1.board?.id }
            assertFails { p2.board?.id }

            transaction(dbWithCache) {
                b1.refresh()
                p1.refresh()
                p2.refresh()
                listOf(p1, p2).with(Post::board)
            }

            p1.board?.id shouldBeEqualTo b1.id
            p2.board?.id shouldBeEqualTo b1.id
        }
    }

    @Test
    fun `referrersOn works out of transaction`() {
        var b1: Board by Delegates.notNull()
        var p1: Post by Delegates.notNull()
        var p2: Post by Delegates.notNull()

        executeOnH2(Boards, Posts, Categories) {
            transaction(db) {
                b1 = Board.new { this.name = "test-board" }
                p1 = Post.new { board = b1 }
                p2 = Post.new { board = b1 }
            }
            assertFails { b1.posts.toList() }
            assertFails { p1.board?.id }
            assertFails { p2.board?.id }

            transaction(dbWithCache) {
                b1.refresh()
                p1.refresh()
                p2.refresh()

                p1.board?.id shouldBeEqualTo b1.id
                p2.board?.id shouldBeEqualTo b1.id
                b1.posts.map { it.id } shouldBeEqualTo listOf(p1.id, p2.id)
            }

            p1.board?.id shouldBeEqualTo b1.id
            p2.board?.id shouldBeEqualTo b1.id
            b1.posts.map { it.id } shouldBeEqualTo listOf(p1.id, p2.id)
        }
    }

    @Test
    fun `optionalReferrersOn works out of transaction via warmup`() {
        var b1: Board by Delegates.notNull()
        var p1: Post by Delegates.notNull()
        var p2: Post by Delegates.notNull()

        executeOnH2(Boards, Posts, Categories) {
            transaction(db) {
                b1 = Board.new { this.name = "test-board" }
                p1 = Post.new { board = b1 }
                p2 = Post.new { board = b1 }
            }
            assertFails { b1.posts.toList() }
            assertFails { p1.board?.id }
            assertFails { p2.board?.id }

            transaction(dbWithCache) {
                b1.refresh()
                p1.refresh()
                p2.refresh()

                b1.load(Board::posts)
                b1.posts.map { it.id } shouldBeEqualTo listOf(p1.id, p2.id)
            }

            b1.posts.map { it.id } shouldBeEqualTo listOf(p1.id, p2.id)
        }
    }

    @Test
    fun `referrersOn work out of transaction via warmup`() {
        var c1: City by Delegates.notNull()
        var u1: User by Delegates.notNull()
        var u2: User by Delegates.notNull()

        executeOnH2(Cities, Users) {
            transaction(dbWithCache) {
                c1 = City.new { name = "test-city" }
                u1 = User.new {
                    name = "a"
                    city = c1
                    age = 5
                }
                u2 = User.new {
                    name = "b"
                    city = c1
                    age = 27
                }
                City.all().with(City::users).toList()
            }
            c1.users.map { it.id } shouldBeEqualTo listOf(u1.id, u2.id)
        }
    }

    @Test
    fun `via refreence out of transaction`() {

    }
}
