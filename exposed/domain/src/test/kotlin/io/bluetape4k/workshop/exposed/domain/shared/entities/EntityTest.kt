package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.id.SnowflakeIdTable
import io.bluetape4k.exposed.sql.timebasedGenerated
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XTable
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.YTable
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.exceptions.EntityNotFoundException
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.dao.load
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.Case
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.idParam
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.inTopLevelTransaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection
import kotlin.test.assertFailsWith

object EntityTestData {

    object YTable: IdTable<String>("YTable") {
        override val id: Column<EntityID<String>> = varchar("uuid", 24).entityId()
            .clientDefault { EntityID(TimebasedUuid.nextBase62String(), YTable) }

        val x = bool("x").default(true)

        override val primaryKey = PrimaryKey(id)
    }

    object XTable: IntIdTable("XTable") {
        val b1 = bool("b1").default(true)
        val b2 = bool("b2").default(false)
        val y1 = optReference("y1", YTable)
    }

    class XEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<XEntity>(XTable)

        var b1 by XTable.b1
        var b2 by XTable.b2

        override fun toString(): String = "XEntity(id=$id, b1=$b1, b2=$b2)"
    }

    enum class XType {
        A, B
    }

    open class AEntity(id: EntityID<Int>): IntEntity(id) {
        var b1 by XTable.b1

        companion object: IntEntityClass<AEntity>(XTable) {
            fun create(b1: Boolean, type: XType): AEntity {
                val init: AEntity.() -> Unit = {
                    this.b1 = b1
                }
                val answer = when (type) {
                    XType.B -> BEntity.create { init() }
                    else    -> new { init() }
                }
                return answer
            }
        }

        override fun toString(): String = "AEntity(id=$id, b1=$b1)"
    }

    open class BEntity(id: EntityID<Int>): AEntity(id) {
        var b2 by XTable.b2
        var y by YEntity optionalReferencedOn XTable.y1

        companion object: IntEntityClass<BEntity>(XTable) {
            fun create(init: AEntity.() -> Unit): BEntity {
                val answer = new { init() }
                return answer
            }
        }

        override fun toString(): String = "BEntity(id=$id, b1=$b1, b2=$b2)"
    }

    class YEntity(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, YEntity>(YTable)

        var x by YTable.x
        val b: BEntity? by BEntity.backReferencedOn(XTable.y1)

        override fun toString(): String = "YEntity(id=$id, x=$x)"
    }
}

@Suppress("UNUSED_VARIABLE")
class EntityTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults 01`(testDB: TestDB) {
        withTables(testDB, YTable, XTable) {
            // INSERT INTO XTABLE (B1, B2) VALUES (TRUE, FALSE)
            val x = EntityTestData.XEntity.new { }
            x.b1.shouldBeTrue()
            x.b2.shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults 02`(testDB: TestDB) {
        withTables(testDB, YTable, XTable) {
            // INSERT INTO XTABLE (B1, B2, Y1) VALUES (FALSE, FALSE, NULL)
            val a = EntityTestData.AEntity.create(false, EntityTestData.XType.A)
            // INSERT INTO XTABLE (B1, B2, Y1) VALUES (FALSE, FALSE, '2ylqB2ttjrAtFxMhKKNxd')
            val b = EntityTestData.AEntity.create(false, EntityTestData.XType.B) as EntityTestData.BEntity
            // INSERT INTO YTABLE (UUID, X) VALUES ('2ylqB2ttjrAtFxMhKKNxd', FALSE)
            val y = EntityTestData.YEntity.new { x = false }

            a.b1.shouldBeFalse()
            b.b1.shouldBeFalse()
            b.b2.shouldBeFalse()

            b.y = y

            b.y!!.x.shouldBeFalse()
            y.b.shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `text field outside the transaction`(testDB: TestDB) {
        val objectsToVerify = arrayListOf<Pair<Human, TestDB>>()

        withTables(testDB, Humans) { testDb ->
            // INSERT INTO HUMAN (H) VALUES ('foo')
            val y1 = Human.new { h = "foo" }

            flushCache()
            /**
             * ```sql
             * SELECT HUMAN.ID, HUMAN.H FROM HUMAN WHERE HUMAN.ID = 1
             * ```
             */
            y1.refresh(flush = false)

            objectsToVerify.add(y1 to testDb)
        }

        objectsToVerify.forEach { (human, testDb) ->
            log.debug { "Verifying $human in $testDb" }
            human.h shouldBeEqualTo "foo"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `new with id and refresh`(testDB: TestDB) {
        val objectsToVerify = arrayListOf<Pair<Human, TestDB>>()

        withTables(testDB, Humans) {
            val x = Human.new(2) { h = "foo" }
            x.refresh(flush = true)
            objectsToVerify.add(x to testDB)
        }

        objectsToVerify.forEach { (human, testDb) ->
            log.debug { "Verifying $human in $testDb" }
            human.h shouldBeEqualTo "foo"
            human.id.value shouldBeEqualTo 2
        }
    }

    internal object OneAutoFieldTable: IntIdTable("single")
    internal class SingleFieldEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<SingleFieldEntity>(OneAutoFieldTable)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one field entity`(testDB: TestDB) {
        withTables(testDB, OneAutoFieldTable) {
            val entity = SingleFieldEntity.new { }
            commit()
            entity.id.value shouldBeGreaterThan 0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `back reference 01`(testDB: TestDB) {
        withTables(testDB, YTable, XTable) {
            /**
             * ```sql
             * INSERT INTO YTABLE (UUID) VALUES ('2ylqB3I4kotn2yLdTR2uo')
             * ```
             */
            val y = EntityTestData.YEntity.new { }
            flushCache()

            /**
             * ```sql
             * INSERT INTO XTABLE (B1, B2, Y1) VALUES (TRUE, FALSE, '2ylqB3I4kotn2yLdTR2uo')
             * ```
             */
            val b = EntityTestData.BEntity.new { }
            b.y = y

            /**
             * ```sql
             * SELECT XTABLE.ID, XTABLE.B1, XTABLE.B2, XTABLE.Y1
             *   FROM XTABLE
             *  WHERE XTABLE.Y1 = '2ylqB3I4kotn2yLdTR2uo'
             * ```
             */
            y.b shouldBeEqualTo b
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `back reference 02`(testDB: TestDB) {
        withTables(testDB, YTable, XTable) {
            /**
             * ```sql
             * INSERT INTO XTABLE (B1, B2) VALUES (TRUE, FALSE)
             * ```
             */
            val b = EntityTestData.BEntity.new { }
            flushCache()

            /**
             * ```sql
             * INSERT INTO YTABLE (UUID) VALUES ('2ylqB3JvcJDrxvKSTOpll')
             * ```
             */
            val y = EntityTestData.YEntity.new { }

            /**
             * ```sql
             * UPDATE XTABLE SET Y1='2ylqB3JvcJDrxvKSTOpll' WHERE ID = 1
             * ```
             */
            b.y = y

            /**
             * ```sql
             * SELECT XTABLE.ID, XTABLE.B1, XTABLE.B2, XTABLE.Y1 FROM XTABLE WHERE XTABLE.Y1 = '2ylqB3JvcJDrxvKSTOpll'
             * ```
             */
            y.b shouldBeEqualTo b
        }
    }

    object Boards: IntIdTable("board") {
        val name = varchar("name", 255).uniqueIndex()
    }

    object Posts: LongIdTable("posts") {
        val board = optReference("board", Boards.id)
        val parent = optReference("parent", this)
        val category = optReference("category", Categories.uniqueId).uniqueIndex()
        val optCategory = optReference("optCategory", Categories.uniqueId)
    }

    object Categories: IntIdTable("categories") {
        val uniqueId = varchar("uniqueId", 22).timebasedGenerated().uniqueIndex()
        val title = varchar("title", 50)
    }

    class Board(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Board>(Boards)

        var name by Boards.name
        val posts by Post optionalReferrersOn Posts.board  // one-to-many
    }

    class Post(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Post>(Posts)

        var board by Board optionalReferencedOn Posts.board     // many-to-one
        var parent by Post optionalReferencedOn Posts.parent     // many-to-one
        val children by Post optionalReferrersOn Posts.parent   // one-to-many
        var category by Category optionalReferencedOn Posts.category
        var optCategory by Category optionalReferencedOn Posts.optCategory
    }

    class Category(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Category>(Categories)

        val uniqueId by Categories.uniqueId
        var title by Categories.title
        val posts by Post optionalReferrersOn Posts.optCategory  // one-to-many
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `self reference table`(testDB: TestDB) {
        SchemaUtils.sortTablesByReferences(listOf(Posts, Boards, Categories)) shouldBeEqualTo listOf(
            Boards,
            Categories,
            Posts
        )
        SchemaUtils.sortTablesByReferences(listOf(Categories, Posts, Boards)) shouldBeEqualTo listOf(
            Categories,
            Boards,
            Posts
        )
        SchemaUtils.sortTablesByReferences(listOf(Posts)) shouldBeEqualTo listOf(Boards, Categories, Posts)
    }

    /**
     * count of Posts
     * ```sql
     * SELECT COUNT(*) FROM POSTS
     * ```
     *
     * count of Posts where parent is 1
     * ```sql
     * SELECT COUNT(*) FROM POSTS WHERE POSTS.PARENT = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert child without flush`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            val parent = Post.new { this.category = Category.new { title = "title" } }
            Post.new { this.parent = parent }

            /**
             * ```sql
             * SELECT COUNT(*) FROM POSTS
             * ```
             */
            Post.all().count() shouldBeEqualTo 2L

            /**
             * ```sql
             * SELECT COUNT(*) FROM POSTS WHERE POSTS.PARENT = 1
             * ```
             */
            // one-to-many의 count 만 따로 수행하는 쿼리가 된다. (JPA 에서는 안됨)
            parent.children.count() shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert non child without flush`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            val board = Board.new { name = "ireelevant" }
            Post.new { this.board = board }  // first flush before referencing

            flushCache().size shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `queries within other query iterator works fine`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            val board1 = Board.new { name = "irrelevant" }
            val board2 = Board.new { name = "relavant" }
            val post1 = Post.new { board = board1 }

            Board.all().forEach { board ->
                /**
                 * ```sql
                 * SELECT COUNT(*) FROM POSTS WHERE POSTS.BOARD = 1
                 * SELECT POSTS.ID, POSTS.BOARD, POSTS.PARENT, POSTS.CATEGORY, POSTS."optCategory" FROM POSTS WHERE POSTS.BOARD = 1
                 * SELECT POSTS.ID, POSTS.BOARD, POSTS.PARENT, POSTS.CATEGORY, POSTS."optCategory" FROM POSTS WHERE POSTS.BOARD = 1
                 * ```
                 * ```sql
                 * SELECT COUNT(*) FROM POSTS WHERE POSTS.BOARD = 2
                 * SELECT POSTS.ID, POSTS.BOARD, POSTS.PARENT, POSTS.CATEGORY, POSTS."optCategory" FROM POSTS WHERE POSTS.BOARD = 2
                 * SELECT POSTS.ID, POSTS.BOARD, POSTS.PARENT, POSTS.CATEGORY, POSTS."optCategory" FROM POSTS WHERE POSTS.BOARD = 2
                 * ```
                 */
                board.posts.count() to board.posts.toList()
                val text = Post.find { Posts.board eq board.id }.joinToString { post ->
                    post.board?.name.orEmpty()
                }
                log.debug { "text: $text" }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert child with flush`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            val parent = Post.new { this.category = Category.new { title = "title" } }
            flushCache()
            parent.id._value.shouldNotBeNull()

            Post.new { this.parent = parent }

            flushCache().size shouldBeEqualTo 1

            // SELECT COUNT(*) FROM POSTS
            Post.all().count() shouldBeEqualTo 2L

            // SELECT COUNT(*) FROM POSTS WHERE POSTS.PARENT = 1
            parent.children.count() shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert child with child`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            val parent = Post.new { this.category = Category.new { title = "title1" } }
            val child1 = Post.new {
                this.parent = parent
                this.category = Category.new { title = "title2" }
            }
            Post.new { this.parent = child1 }
            flushCache()

            // SELECT COUNT(*) FROM POSTS
            Post.all().count() shouldBeEqualTo 3L

            // SELECT COUNT(*) FROM POSTS WHERE POSTS.PARENT = 1
            parent.children.count() shouldBeEqualTo 1L

            // SELECT COUNT(*) FROM POSTS WHERE POSTS.PARENT = 2
            child1.children.count() shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `optional referrers with different keys`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            val board = Board.new { name = "irrelevant" }
            val post1 = Post.new {
                this.board = board
                this.category = Category.new { title = "title" }
            }
            /**
             * ```sql
             * SELECT COUNT(*) FROM POSTS WHERE POSTS.BOARD = 1
             * ```
             */
            board.posts.count() shouldBeEqualTo 1L
            /**
             * ```sql
             * SELECT POSTS.ID, POSTS.BOARD, POSTS.PARENT, POSTS.CATEGORY, POSTS."optCategory"
             *   FROM POSTS
             *  WHERE POSTS.BOARD = 1
             * ```
             */
            board.posts.single() shouldBeEqualTo post1

            Post.new { this.board = board }
            /**
             * ```sql
             * SELECT COUNT(*) FROM POSTS WHERE POSTS.BOARD = 1
             * ```
             */
            board.posts.count() shouldBeEqualTo 2L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `error on set to deleted entity`(testDB: TestDB) {
        withTables(testDB, Boards) {
            assertFailsWith<EntityNotFoundException> {
                val board = Board.new { name = "irrelevant" }
                board.delete()

                // 이미 삭제되었음
                board.name = "new name"
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `cache invalidated on DSL delete`(testDB: TestDB) {
        withTables(testDB, Boards) {
            val board1 = Board.new { name = "irrelevant" }
            Board.testCache(board1.id).shouldNotBeNull()
            board1.delete()
            Board.testCache(board1.id).shouldBeNull()

            val board2 = Board.new { name = "irrelevant" }
            Board.testCache(board2.id).shouldNotBeNull()

            // DSL Delete 를 수행해도 Cache예서 제거된다.
            Boards.deleteWhere { Boards.id eq board2.id }
            Board.testCache(board2.id).shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `cache invalidated on DSL update`(testDB: TestDB) {
        withTables(testDB, Boards) {
            val board1 = Board.new { name = "irrelevant" }
            Board.testCache(board1.id).shouldNotBeNull()
            board1.name = "relevant"
            board1.name shouldBeEqualTo "relevant"

            val board2 = Board.new { name = "irrelevant2" }
            Board.testCache(board2.id).shouldNotBeNull()

            // DSL Update 를 수행하면 Cache가 지워진다.
            Boards.update({ Boards.id eq board2.id }) {
                it[name] = "relevant2"
            }
            Board.testCache(board2.id).shouldBeNull()

            // 다시 읽어들인다.
            board2.refresh(flush = false)
            Board.testCache(board2.id).shouldNotBeNull()
            board2.name shouldBeEqualTo "relevant2"
        }
    }

    object Items: IntIdTable("items") {
        val name = varchar("name", 255).uniqueIndex()
        val price = decimal("price", 10, 2)
    }

    class Item(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Item>(Items)

        var name by Items.name
        var price by Items.price

        override fun toString(): String = "Item(id=$id, name=$name, price=$price)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `cache invalidated on DSL upsert`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB != TestDB.H2_V1 }

        withTables(testDB, Items) {
            val oldPrice = 20.0.toBigDecimal()
            val itemA = Item.new {
                name = "Item A"
                price = oldPrice
            }
            itemA.price shouldBeEqualTo oldPrice
            Item.testCache(itemA.id).shouldNotBeNull()

            val newPrice = 50.0.toBigDecimal()
            val conflictKeys = if (testDB in TestDB.ALL_MYSQL_LIKE) emptyArray<Column<*>>() else arrayOf(Items.name)

            /**
             * ```sql
             * MERGE INTO ITEMS T USING (VALUES ('Item A', 50.0)) S("name", PRICE) ON (T."name"=S."name")
             * WHEN MATCHED THEN
             *      UPDATE SET T.PRICE=S.PRICE
             * WHEN NOT MATCHED THEN
             *      INSERT ("name", PRICE) VALUES(S."name", S.PRICE)
             * ```
             */
            Items.upsert(*conflictKeys) {
                it[name] = itemA.name
                it[price] = newPrice
            }
            itemA.price shouldBeEqualTo oldPrice
            Item.testCache(itemA.id).shouldBeNull()

            /**
             * ```sql
             * SELECT ITEMS.ID, ITEMS."name", ITEMS.PRICE FROM ITEMS WHERE ITEMS.ID = 1
             * ```
             */
            itemA.refresh(flush = false)
            itemA.price shouldBeEqualTo newPrice
            Item.testCache(itemA.id).shouldNotBeNull()

            /**
             * ```sql
             * MERGE INTO ITEMS T USING (VALUES ('Item A', 100.0)) S("name", PRICE) ON (T."name"=S."name") WHEN MATCHED THEN UPDATE SET T.PRICE=S.PRICE WHEN NOT MATCHED THEN INSERT ("name", PRICE) VALUES(S."name", S.PRICE)
             * MERGE INTO ITEMS T USING (VALUES ('Item B', 100.0)) S("name", PRICE) ON (T."name"=S."name") WHEN MATCHED THEN UPDATE SET T.PRICE=S.PRICE WHEN NOT MATCHED THEN INSERT ("name", PRICE) VALUES(S."name", S.PRICE)
             * MERGE INTO ITEMS T USING (VALUES ('Item C', 100.0)) S("name", PRICE) ON (T."name"=S."name") WHEN MATCHED THEN UPDATE SET T.PRICE=S.PRICE WHEN NOT MATCHED THEN INSERT ("name", PRICE) VALUES(S."name", S.PRICE)
             * MERGE INTO ITEMS T USING (VALUES ('Item D', 100.0)) S("name", PRICE) ON (T."name"=S."name") WHEN MATCHED THEN UPDATE SET T.PRICE=S.PRICE WHEN NOT MATCHED THEN INSERT ("name", PRICE) VALUES(S."name", S.PRICE)
             * MERGE INTO ITEMS T USING (VALUES ('Item E', 100.0)) S("name", PRICE) ON (T."name"=S."name") WHEN MATCHED THEN UPDATE SET T.PRICE=S.PRICE WHEN NOT MATCHED THEN INSERT ("name", PRICE) VALUES(S."name", S.PRICE)
             * ```
             */
            val newPricePlusExtra = 100.0.toBigDecimal()
            val newItems = List(5) { i -> "Item ${'A' + i}" to newPricePlusExtra }
            Items.batchUpsert(newItems, *conflictKeys, shouldReturnGeneratedValues = false) { (name, price) ->
                this[Items.name] = name
                this[Items.price] = price   // newPricePlusExtra
            }
            itemA.price shouldBeEqualTo newPrice
            Item.testCache(itemA.id).shouldBeNull()

            /**
             * ```sql
             * SELECT ITEMS.ID, ITEMS."name", ITEMS.PRICE FROM ITEMS WHERE ITEMS.ID = 1
             * ```
             */
            itemA.refresh(flush = false)
            itemA.price shouldBeEqualTo newPricePlusExtra
            Item.testCache(itemA.id).shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Dao findByIdAndUpdate`(testDB: TestDB) {
        withTables(testDB, Items) {
            val oldPrice = 20.0.toBigDecimal()
            val item = Item.new {
                name = "Item A"
                price = oldPrice
            }
            item.price shouldBeEqualTo oldPrice
            Item.testCache(item.id).shouldNotBeNull()

            val newPrice = 50.0.toBigDecimal()

            /**
             * ```sql
             * SELECT ITEMS.ID, ITEMS."name", ITEMS.PRICE FROM ITEMS WHERE ITEMS.ID = 1 FOR UPDATE
             * ```
             */
            val updatedItem = Item.findByIdAndUpdate(item.id.value) {
                it.price = newPrice
            }

            updatedItem shouldBeEqualTo item

            updatedItem.shouldNotBeNull()
            updatedItem.price shouldBeEqualTo newPrice
            Item.testCache(item.id).shouldNotBeNull()

            item.price shouldBeEqualTo newPrice

            // NOTE: refresh(flush=false)이면 Cache 값이 다시 채워진다.
            /**
             * ```sql
             * SELECT ITEMS.ID, ITEMS."name", ITEMS.PRICE FROM ITEMS WHERE ITEMS.ID = 1
             * ```
             */
            item.refresh(flush = false)
            item.price shouldBeEqualTo oldPrice
            Item.testCache(item.id).shouldNotBeNull()

            updatedItem shouldBeEqualTo item
            Item.testCache(updatedItem.id).shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Dao findSingleByAndUpdate`(testDB: TestDB) {
        withTables(testDB, Items) {
            val oldPrice = 20.0.toBigDecimal()
            val item = Item.new {
                name = "Item A"
                price = oldPrice
            }
            item.price shouldBeEqualTo oldPrice
            Item.testCache(item.id).shouldNotBeNull()

            /**
             * ```sql
             * SELECT ITEMS.ID,
             *        ITEMS."name",
             *        ITEMS.PRICE
             *   FROM ITEMS
             *  WHERE ITEMS."name" = 'Item A'
             *    FOR UPDATE
             * ```
             */
            val newPrice = 50.0.toBigDecimal()
            val updatedItem = Item.findSingleByAndUpdate(Items.name eq "Item A") {
                it.price = newPrice
            }

            updatedItem shouldBeEqualTo item

            updatedItem.shouldNotBeNull()
            updatedItem.price shouldBeEqualTo newPrice
            Item.testCache(item.id).shouldNotBeNull()

            item.price shouldBeEqualTo newPrice

            // NOTE: refresh(flush=false)이면 Cache 값이 다시 채워진다.
            /**
             * ```sql
             * SELECT ITEMS.ID, ITEMS."name", ITEMS.PRICE FROM ITEMS WHERE ITEMS.ID = 1
             * ```
             */
            item.refresh(flush = false)
            item.price shouldBeEqualTo oldPrice
            Item.testCache(item.id).shouldNotBeNull()

            updatedItem shouldBeEqualTo item
            Item.testCache(updatedItem.id).shouldNotBeNull()
        }
    }


    object Humans: IntIdTable("human") {
        val h = text("h", eagerLoading = true)
    }

    object Users: IdTable<Int>("user") {
        override val id = reference("id", Humans)
        val name = text("name")
    }

    open class Human(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Human>(Humans)

        var h by Humans.h

        override fun toString(): String = "Human(id=$id, h=$h)"
    }

    open class User(id: EntityID<Int>): Entity<Int>(id) {
        companion object: EntityClass<Int, User>(Users) {
            operator fun invoke(name: String): User {
                val h = Human.new { h = name.take(2) }
                return User.new(h.id.value) {
                    this.name = name
                }
            }
        }

        val human: Human by Human referencedOn Users.id
        var name: String by Users.name

        override fun toString(): String = "User(id=$id, name=$name, human=$human)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-one reference`(testDB: TestDB) {
        withTables(testDB, Humans, Users) {
            repeat(3) {
                /**
                 * ```sql
                 * INSERT INTO HUMAN (H) VALUES ('te')
                 * INSERT INTO "user" (ID, "name") VALUES (1, 'testUser')
                 * ```
                 */
                val user = User("testUser")
                user.human.h shouldBeEqualTo "te"
                user.name shouldBeEqualTo "testUser"
                user.id.value shouldBeEqualTo user.human.id.value
            }
        }
    }

    private object SelfReferenceTable: IntIdTable() {
        val parentId = optReference("parent_id", SelfReferenceTable)
    }

    open class SelfReferenceEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<SelfReferenceEntity>(SelfReferenceTable)

        var parent by SelfReferenceTable.parentId
        val children by SelfReferenceEntity optionalReferrersOn SelfReferenceTable.parentId
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `hierarchy entity`(testDB: TestDB) {
        withTables(testDB, SelfReferenceTable) {
            val parent = SelfReferenceEntity.new { }
            val child1 = SelfReferenceEntity.new { this.parent = parent.id }
            val child2 = SelfReferenceEntity.new { this.parent = parent.id }
            val grandChild = SelfReferenceEntity.new { this.parent = child1.id }

            /**
             * ```sql
             * SELECT COUNT(*) FROM SELFREFERENCE WHERE SELFREFERENCE.PARENT_ID = 1
             * SELECT COUNT(*) FROM SELFREFERENCE WHERE SELFREFERENCE.PARENT_ID = 2
             * SELECT COUNT(*) FROM SELFREFERENCE WHERE SELFREFERENCE.PARENT_ID = 3
             * SELECT COUNT(*) FROM SELFREFERENCE WHERE SELFREFERENCE.PARENT_ID = 4
             * ```
             */
            parent.children.count() shouldBeEqualTo 2
            child1.children.count() shouldBeEqualTo 1
            child2.children.count() shouldBeEqualTo 0
            grandChild.children.count() shouldBeEqualTo 0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `self references`(testDB: TestDB) {
        withTables(testDB, SelfReferenceTable) {
            repeat(5) { SelfReferenceEntity.new { } }

            val ref1 = SelfReferenceEntity.new { }
            /**
             * ```sql
             * UPDATE SELFREFERENCE
             *    SET PARENT_ID=6
             *  WHERE ID = 6
             * ```
             */
            ref1.parent = ref1.id

            /**
             * ```sql
             * SELECT SELFREFERENCE.ID, SELFREFERENCE.PARENT_ID
             *   FROM SELFREFERENCE
             *  WHERE SELFREFERENCE.ID = 6
             * ```
             */
            val refRow = SelfReferenceTable.selectAll()
                .where { SelfReferenceTable.id eq ref1.id }
                .single()

            refRow[SelfReferenceTable.parentId]!!.value shouldBeEqualTo ref1.id._value
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `non entityId reference`(testDB: TestDB) {
        withTables(testDB, Posts, Boards, Categories) {
            val category1 = Category.new { title = "category1" }

            val post1 = Post.new {
                optCategory = category1
                category = Category.new { title = "title" }
            }

            val post2 = Post.new {
                optCategory = category1
                parent = post1
            }

            Post.all().count() shouldBeEqualTo 2L

            // SELECT COUNT(*) FROM POSTS WHERE POSTS."optCategory" = '2ylpPHjEQlD7I6FiOM0Gt'
            category1.posts.count() shouldBeEqualTo 2L

            // SELECT COUNT(*) FROM POSTS WHERE POSTS."optCategory" = '2ylpPHjEQlD7I6FiOM0Gt'
            Posts.selectAll()
                .where { Posts.optCategory eq category1.uniqueId }
                .count() shouldBeEqualTo 2L

            // SELECT COUNT(*) FROM POSTS WHERE POSTS."optCategory" = '2ylpPHjEQlD7I6FiOM0Gt'
            Post.find { Posts.optCategory eq category1.uniqueId }
                .count() shouldBeEqualTo 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `call limit on relation doesnt mutate the cached value`(testDB: TestDB) {
        withTables(testDB, Posts, Boards, Categories) {
            val category1 = Category.new { title = "category1" }

            Post.new {
                optCategory = category1
                category = Category.new { title = "title" }
            }

            Post.new {
                optCategory = category1
            }

            commit()

            /**
             * ```sql
             * SELECT COUNT(*) FROM POSTS WHERE POSTS."optCategory" = '2ylpPHssTfbOjIO2XKuqq'
             * ```
             */
            category1.posts.count() shouldBeEqualTo 2L

            /**
             * ```sql
             * SELECT POSTS.ID,
             *        POSTS.BOARD,
             *        POSTS.PARENT,
             *        POSTS.CATEGORY,
             *        POSTS."optCategory"
             *   FROM POSTS
             *  WHERE POSTS."optCategory" = '2ylpPHssTfbOjIO2XKuqq'
             * ```
             */
            category1.posts.toList() shouldHaveSize 2

            /**
             * ```sql
             * SELECT POSTS.ID,
             *        POSTS.BOARD,
             *        POSTS.PARENT,
             *        POSTS.CATEGORY,
             *        POSTS."optCategory"
             *   FROM POSTS
             *  WHERE POSTS."optCategory" = '2ylpPHssTfbOjIO2XKuqq'
             *  LIMIT 1
             * ```
             */
            category1.posts.limit(1).toList() shouldHaveSize 1

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM (
             *      SELECT POSTS.ID posts_id,
             *             POSTS.BOARD posts_board,
             *             POSTS.PARENT posts_parent,
             *             POSTS.CATEGORY posts_category,
             *             POSTS."optCategory" posts_optCategory
             *        FROM POSTS
             *       WHERE POSTS."optCategory" = '2ylpPHssTfbOjIO2XKuqq'
             *       LIMIT 1
             *  ) subquery
             * ```
             */
            category1.posts.limit(1).count() shouldBeEqualTo 1L
            category1.posts.count() shouldBeEqualTo 2L
            category1.posts.toList() shouldHaveSize 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `order by on entities`(testDB: TestDB) {
        withTables(testDB, Categories) {
            Categories.deleteAll()

            val category1 = Category.new { title = "Test1" }
            val category3 = Category.new { title = "Test3" }
            val category2 = Category.new { title = "Test2" }

            Category.all().toList() shouldBeEqualTo listOf(category1, category3, category2)

            /**
             * ```sql
             * SELECT CATEGORIES.ID, CATEGORIES."uniqueId", CATEGORIES.TITLE
             *   FROM CATEGORIES
             *  ORDER BY CATEGORIES.TITLE ASC
             * ```
             */
            Category.all()
                .orderBy(Categories.title to SortOrder.ASC)
                .toList() shouldBeEqualTo listOf(category1, category2, category3)

            /**
             * ```sql
             * SELECT CATEGORIES.ID, CATEGORIES."uniqueId", CATEGORIES.TITLE
             *   FROM CATEGORIES
             *  ORDER BY CATEGORIES.TITLE DESC
             * ```
             */
            Category.all()
                .orderBy(Categories.title to SortOrder.DESC)
                .toList() shouldBeEqualTo listOf(category3, category2, category1)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update of inserted entities goes before an insert`(testDB: TestDB) {
        withTables(testDB, Categories, Posts, Boards) {
            val category1 = Category.new { title = "category1" }
            val category2 = Category.new { title = "category2" }

            val post1 = Post.new { category = category1 }

            flushCache()
            post1.category shouldBeEqualTo category1

            post1.category = category2

            val post2 = Post.new { category = category1 }

            flushCache()
            /**
             * ```sql
             * SELECT POSTS.ID, POSTS.BOARD, POSTS.PARENT, POSTS.CATEGORY, POSTS."optCategory" FROM POSTS WHERE POSTS.ID = 1
             * ```
             */
            Post.reload(post1)
            /**
             * ```sql
             * SELECT POSTS.ID, POSTS.BOARD, POSTS.PARENT, POSTS.CATEGORY, POSTS."optCategory" FROM POSTS WHERE POSTS.ID = 2
             * ```
             */
            Post.reload(post2)

            post1.category shouldBeEqualTo category2
            post2.category shouldBeEqualTo category1
        }
    }

    object Parents: SnowflakeIdTable("parents") {
        val name = varchar("name", 50)
    }

    open class Parent(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Parent>(Parents)

        var name by Parents.name
    }

    object Children: LongIdTable("children") {
        val parentId = reference("parent_id", Parents)
        val name = varchar("name", 80)
    }

    open class Child(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Child>(Children)

        var parent by Parent referencedOn Children.parentId
        var name by Children.name
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `new id with get`(testDB: TestDB) {
        withTables(testDB, Parents, Children) {
            val parentId = Parent.new(10L) { name = "parent" }.id.value

            commit()

            val child = Child.new(100L) {
                parent = Parent[parentId]
                name = "child"
            }

            parentId shouldBeEqualTo 10L
            child.id.value shouldBeEqualTo 100L
            child.parent.id.value shouldBeEqualTo parentId
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `newly created entity flushed successfully`(testDB: TestDB) {
        withTables(testDB, Boards) {
            val board = Board.new { name = "Board1" }
                .apply {
                    flush().shouldBeTrue()
                }

            board.name shouldBeEqualTo "Board1"
            board.id._value.shouldNotBeNull()
        }
    }

    private fun <T> newTransaction(statement: Transaction.() -> T): T =
        inTopLevelTransaction(
            TransactionManager.manager.defaultIsolationLevel,
            readOnly = false,
            db = null,
            outerTransaction = null,
            statement = statement
        )

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `sharing entity between transaction with entity cache`(testDB: TestDB) {
        withTables(testDB, Humans) {
            val human1 = newTransaction {
                maxAttempts = 1
                Human.new { h = "foo" }
            }

            newTransaction {
                maxAttempts = 1
                Human.testCache(human1.id).shouldBeNull()
                Humans.selectAll().single()[Humans.h] shouldBeEqualTo "foo"

                human1.h = "bar"
                Human.testCache(human1.id) shouldBeEqualTo human1
                Humans.selectAll().single()[Humans.h] shouldBeEqualTo "bar"
            }

            newTransaction {
                maxAttempts = 1
                Humans.selectAll().single()[Humans.h] shouldBeEqualTo "bar"
            }
        }
    }

    object Regions: IntIdTable("regions") {
        val name = varchar("name", 255)
    }

    object Students: LongIdTable("students") {
        val name = varchar("name", 255)
        val school = reference("school_id", Schools)
    }

    object StudentBios: LongIdTable("student_bios") {
        val dateOfBirth = varchar("date_of_birth", 25)
        val student = reference("student_id", Students)
    }

    object Notes: LongIdTable("notes") {
        val text = varchar("text", 255)
        val student = reference("student_id", Students)
    }

    object Detentions: LongIdTable("datentions") {
        val reason = varchar("reason", 255)
        val student = optReference("student_id", Students)
    }

    object Holidays: LongIdTable("holidays") {
        val holidayStart = long("holiday_start")
        val holidayEnd = long("holiday_end")
    }

    object SchoolHolidays: Table("school_holidays") {
        val school = reference("school_id", Schools, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
        val holiday = reference("holiday_id", Holidays, ReferenceOption.CASCADE, ReferenceOption.CASCADE)

        override val primaryKey = PrimaryKey(school, holiday)
    }

    object Schools: IntIdTable("schools") {
        val name = varchar("name", 255).uniqueIndex()
        val region = reference("region_id", Regions)
        val secondaryRegion = optReference("secondary_region_id", Regions)
    }

    class Region(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Region>(Regions)

        var name by Regions.name

        override fun equals(other: Any?): Boolean {
            return (other as? Region)?.id?.equals(id) ?: false
        }

        override fun hashCode(): Int = id.hashCode()
        override fun toString(): String = "Region(id=$id, name=$name)"
    }

    @Suppress("UNCHECKED_CAST")
    abstract class ComparableLongEntity<T: LongEntity>(id: EntityID<Long>): LongEntity(id) {
        override fun equals(other: Any?): Boolean = (other as? T)?.id?.equals(id) ?: false
        override fun hashCode(): Int = id.hashCode()
    }

    class Student(id: EntityID<Long>): ComparableLongEntity<Student>(id) {
        companion object: LongEntityClass<Student>(Students)

        var name by Students.name
        var school by School referencedOn Students.school
        val bio by StudentBio optionalBackReferencedOn StudentBios.student
        val notes by Note.referrersOn(Notes.student, cache = true)
        val detentions by Detention optionalReferrersOn Detentions.student

        override fun toString(): String = "Student(id=$id, name=$name, school=$school)"
    }

    class StudentBio(id: EntityID<Long>): ComparableLongEntity<StudentBio>(id) {
        companion object: LongEntityClass<StudentBio>(StudentBios)

        var student by Student referencedOn StudentBios.student
        var dateOfBirth by StudentBios.dateOfBirth

        override fun toString(): String = "StudentBio(id=$id, dateOfBirth=$dateOfBirth, student=$student)"
    }

    class Note(id: EntityID<Long>): ComparableLongEntity<Note>(id) {
        companion object: LongEntityClass<Note>(Notes)

        var text by Notes.text
        var student by Student referencedOn Notes.student

        override fun toString(): String = "Note(id=$id, text=$text, student=$student)"
    }

    class Detention(id: EntityID<Long>): ComparableLongEntity<Detention>(id) {
        companion object: LongEntityClass<Detention>(Detentions)

        var reason by Detentions.reason
        var student by Student optionalReferencedOn Detentions.student

        override fun toString(): String = "Detention(id=$id, reason=$reason, student=$student)"
    }

    class Holiday(id: EntityID<Long>): ComparableLongEntity<Holiday>(id) {
        companion object: LongEntityClass<Holiday>(Holidays)

        var holidayStart by Holidays.holidayStart
        var holidayEnd by Holidays.holidayEnd

        override fun toString(): String = "Holiday(id=$id, holidayStart=$holidayStart, holidayEnd=$holidayEnd)"
    }

    class School(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<School>(Schools)

        var name by Schools.name
        var region by Region referencedOn Schools.region
        var secondaryRegion by Region optionalReferencedOn Schools.secondaryRegion
        val students by Student.referrersOn(Students.school, cache = true)
        var holidays by Holiday via SchoolHolidays

        override fun toString(): String = "School(id=$id, name=$name, region=$region, secondaryRegion=$secondaryRegion)"
    }

    /**
     * Preload references = Fetch Eager Loading
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload references on a sized iterable`(testDB: TestDB) {
        withTables(testDB, Regions, Schools) {
            val region1 = Region.new { name = "United Kingdom" }
            val region2 = Region.new { name = "England" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val school2 = School.new { name = "Harrow"; region = region1 }
            val school3 = School.new { name = "Winchester"; region = region2 }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                /**
                 * ```sql
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS
                 * ```
                 * ```sql
                 * SELECT REGIONS.ID, REGIONS."name" FROM REGIONS WHERE REGIONS.ID IN (1, 2)
                 * ```
                 */
                // with - Fetch Eager Loading (즉시 로딩, N+1 문제 해결)
                School.all().with(School::region)
                School.testCache(school1.id).shouldNotBeNull()
                School.testCache(school2.id).shouldNotBeNull()
                School.testCache(school3.id).shouldNotBeNull()

                Region.testCache(School.testCache(school1.id)!!.readValues[Schools.region]) shouldBeEqualTo region1
                Region.testCache(School.testCache(school2.id)!!.readValues[Schools.region]) shouldBeEqualTo region1
                Region.testCache(School.testCache(school3.id)!!.readValues[Schools.region]) shouldBeEqualTo region2
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `iteration over sized iterable with preload`(testDB: TestDB) {
        fun HashMap<String, Pair<Int, Long>>.assertEachQueryExecutedOnlyOnce() {
            forEach { (_, stats) ->
                val executionCount = stats.first
                executionCount shouldBeEqualTo 1
            }
        }

        withTables(testDB, Regions, Schools) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val school2 = School.new { name = "Harrow"; region = region1 }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                debug = true

                /**
                 * ```sql
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS
                 * SELECT REGIONS.ID, REGIONS."name" FROM REGIONS WHERE REGIONS.ID = 1
                 * ```
                 */
                val allSchools = School.all().with(School::region).toList()

                allSchools shouldHaveSize 2

                // 기대: 모든 School을 선택하는 데 1개의 쿼리, 참조된 Region을 선택하는 데 1개의 쿼리
                statementCount shouldBeEqualTo 2
                statementStats.size shouldBeEqualTo statementCount
                statementStats.assertEachQueryExecutedOnlyOnce()

                // reset tracker
                statementCount = 0
                statementStats.clear()

                /**
                 * ```sql
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS LIMIT 1
                 * SELECT REGIONS.ID, REGIONS."name" FROM REGIONS WHERE REGIONS.ID = 1
                 * ```
                 */
                val oneSchool = School.all().limit(1).with(School::region).toList()

                oneSchool shouldHaveSize 1
                statementCount shouldBeEqualTo 2
                statementStats.size shouldBeEqualTo statementCount
                statementStats.assertEachQueryExecutedOnlyOnce()

                debug = false
            }

            // 로딩 후 SizedIterable 쿼리가 변경되면 캐시된 결과가 전파되지 않는지 테스트
            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                debug = true

                /**
                 * ```sql
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS
                 * SELECT REGIONS.ID, REGIONS."name" FROM REGIONS WHERE REGIONS.ID = 1
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS LIMIT 1
                 * ```
                 */
                val oneSchool = School.all().with(School::region).limit(1).toList()

                oneSchool shouldHaveSize 1
                // 기대:
                // 1. 모든 School을 선택하는 데 1개의 쿼리
                // 2. 참조된 Region을 선택하는 데 1개의 쿼리
                // 3. 그후 첫번째 School을 조회하는 1개의 새로운 쿼리
                statementCount shouldBeEqualTo 3
                statementStats.size shouldBeEqualTo statementCount
                statementStats.assertEachQueryExecutedOnlyOnce()

                debug = false
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload optional refrences on an entity`(testDB: TestDB) {
        withTables(testDB, Regions, Schools) {
            val region1 = Region.new { name = "United Kingdom" }
            val region2 = Region.new { name = "England" }
            val school1 = School.new {
                name = "Eton"
                region = region1
                secondaryRegion = region2
            }
            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1

                /**
                 * ```sql
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS WHERE SCHOOLS.ID = 1
                 * SELECT REGIONS.ID, REGIONS."name" FROM REGIONS WHERE REGIONS.ID = 2
                 * ```
                 */
                val school2 = School.find {
                    Schools.id eq school1.id
                }.first().load(School::secondaryRegion)

                Region.testCache(school2.readValues[Schools.region]).shouldBeNull()
                // load 를 통해 secondaryRegion을 로드한다.
                Region.testCache(school2.readValues[Schools.secondaryRegion]!!) shouldBeEqualTo region2
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload referrers on a sized iterable`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Students) {
            val region1 = Region.new { name = "United Kingdom" }
            val region2 = Region.new { name = "England" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val school2 = School.new { name = "Harrow"; region = region1 }
            val school3 = School.new { name = "Winchester"; region = region2 }
            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school2 }
            val student3 = Student.new { name = "Henry Smith"; school = school3 }
            val student4 = Student.new { name = "Peter Smith"; school = school3 }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1

                val cache = TransactionManager.current().entityCache

                /**
                 * ```sql
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS
                 * SELECT STUDENTS.ID, STUDENTS."name", STUDENTS.SCHOOL_ID FROM STUDENTS WHERE STUDENTS.SCHOOL_ID IN (1, 2, 3)
                 * ```
                 */
                School.all().with(School::students).toList()

                cache.getReferrers<Student>(school1.id, Students.school)?.toList().orEmpty() shouldBeEqualTo listOf(
                    student1
                )
                cache.getReferrers<Student>(school2.id, Students.school)?.toList().orEmpty() shouldBeEqualTo listOf(
                    student2
                )
                cache.getReferrers<Student>(school3.id, Students.school)?.toList().orEmpty() shouldBeEqualTo listOf(
                    student3,
                    student4
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload referrers on a entity`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Students) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }

            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school1 }
            val student3 = Student.new { name = "Henry Smith"; school = school1 }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1

                val cache = TransactionManager.current().entityCache
                /**
                 * ```sql
                 * SELECT SCHOOLS.ID,
                 *        SCHOOLS."name",
                 *        SCHOOLS.REGION_ID,
                 *        SCHOOLS.SECONDARY_REGION_ID
                 *   FROM SCHOOLS
                 *  WHERE SCHOOLS.ID = 1
                 * ```
                 */
                School.find { Schools.id eq school1.id }.first().load(School::students)

                /**
                 * ```sql
                 * SELECT STUDENTS.ID,
                 *        STUDENTS."name",
                 *        STUDENTS.SCHOOL_ID
                 *   FROM STUDENTS
                 *  WHERE STUDENTS.SCHOOL_ID = 1
                 * ```
                 */
                cache.getReferrers<Student>(school1.id, Students.school)?.toList().orEmpty() shouldBeEqualTo
                        listOf(student1, student2, student3)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload optional referrers on a sized iterable`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Students, Detentions) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }

            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school1 }

            val detention1 = Detention.new {
                reason = "Fighting"
                student = student1
            }
            val detention2 = Detention.new {
                reason = "Poor Behaviour"
                student = student1
            }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1
                val cache = TransactionManager.current().entityCache

                /**
                 * ```sql
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS
                 * SELECT STUDENTS.ID, STUDENTS."name", STUDENTS.SCHOOL_ID FROM STUDENTS WHERE STUDENTS.SCHOOL_ID = 1
                 * SELECT DATENTIONS.ID, DATENTIONS.REASON, DATENTIONS.STUDENT_ID FROM DATENTIONS WHERE DATENTIONS.STUDENT_ID IN (1, 2)
                 * ```
                 */
                School.all().with(School::students, Student::detentions)

                cache.getReferrers<Student>(school1.id, Students.school)?.toList().orEmpty() shouldBeEqualTo
                        listOf(student1, student2)

                cache.getReferrers<Detention>(student1.id, Detentions.student)?.toList().orEmpty() shouldBeEqualTo
                        listOf(detention1, detention2)

                cache.getReferrers<Detention>(student2.id, Detentions.student)?.toList().orEmpty().shouldBeEmpty()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload inner table link on a sized iterable`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Holidays, SchoolHolidays) {
            val now = System.currentTimeMillis()
            val now10 = now + 10

            val region1 = Region.new { name = "United Kingdom" }
            val region2 = Region.new { name = "England" }

            val school1 = School.new { name = "Eton"; region = region1 }
            val school2 = School.new { name = "Harrow"; region = region1 }
            val school3 = School.new { name = "Winchester"; region = region2 }

            val holiday1 = Holiday.new { holidayStart = now; holidayEnd = now10 }
            val holiday2 = Holiday.new { holidayStart = now; holidayEnd = now10 }
            val holiday3 = Holiday.new { holidayStart = now; holidayEnd = now10 }

            school1.holidays = SizedCollection(holiday1, holiday2)
            school2.holidays = SizedCollection(holiday3)

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1
                val cache = TransactionManager.current().entityCache

                /**
                 * ```sql
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS
                 * SELECT HOLIDAYS.ID,
                 *        HOLIDAYS.HOLIDAY_START,
                 *        HOLIDAYS.HOLIDAY_END,
                 *        SCHOOL_HOLIDAYS.SCHOOL_ID,
                 *        SCHOOL_HOLIDAYS.HOLIDAY_ID
                 *   FROM HOLIDAYS INNER JOIN SCHOOL_HOLIDAYS ON SCHOOL_HOLIDAYS.HOLIDAY_ID = HOLIDAYS.ID
                 *  WHERE SCHOOL_HOLIDAYS.SCHOOL_ID IN (1, 2, 3)
                 * ```
                 */
                School.all().with(School::holidays)

                cache.getReferrers<Holiday>(school1.id, SchoolHolidays.school)?.toList().orEmpty() shouldBeEqualTo
                        listOf(holiday1, holiday2)

                cache.getReferrers<Holiday>(school2.id, SchoolHolidays.school)?.toList().orEmpty() shouldBeEqualTo
                        listOf(holiday3)

                cache.getReferrers<Holiday>(school3.id, SchoolHolidays.school)?.toList().orEmpty().shouldBeEmpty()
            }

        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload inner table link on a entity`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Holidays, SchoolHolidays) {
            val now = System.currentTimeMillis()
            val now10 = now + 10

            val region1 = Region.new { name = "United Kingdom" }
            val region2 = Region.new { name = "England" }

            val school1 = School.new { name = "Eton"; region = region1 }

            val holiday1 = Holiday.new { holidayStart = now; holidayEnd = now10 }
            val holiday2 = Holiday.new { holidayStart = now; holidayEnd = now10 }
            val holiday3 = Holiday.new { holidayStart = now; holidayEnd = now10 }

            SchoolHolidays.insert {
                it[school] = school1.id
                it[holiday] = holiday1.id
            }
            SchoolHolidays.insert {
                it[school] = school1.id
                it[holiday] = holiday2.id
            }
            SchoolHolidays.insert {
                it[school] = school1.id
                it[holiday] = holiday3.id
            }

            commit()

            /**
             * ```sql
             * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID
             *   FROM SCHOOLS
             *  WHERE SCHOOLS.ID = 1
             * ```
             * ```sql
             * SELECT HOLIDAYS.ID, HOLIDAYS.HOLIDAY_START, HOLIDAYS.HOLIDAY_END, SCHOOL_HOLIDAYS.SCHOOL_ID, SCHOOL_HOLIDAYS.HOLIDAY_ID
             *   FROM HOLIDAYS INNER JOIN SCHOOL_HOLIDAYS ON SCHOOL_HOLIDAYS.HOLIDAY_ID = HOLIDAYS.ID
             *  WHERE SCHOOL_HOLIDAYS.SCHOOL_ID = 1
             * ```
             */
            School.find { Schools.id eq school1.id }.first().load(School::holidays)

            val cache = TransactionManager.current().entityCache

            cache.getReferrers<Holiday>(school1.id, SchoolHolidays.school)?.toList().orEmpty() shouldBeEqualTo
                    listOf(holiday1, holiday2, holiday3)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload relation at depth`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Holidays, SchoolHolidays, Students, Notes) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school1 }
            val note1 = Note.new { text = "Note text"; student = student1 }
            val note2 = Note.new { text = "Note text"; student = student2 }

            commit()

            /**
             * ```sql
             * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID FROM SCHOOLS
             * SELECT STUDENTS.ID, STUDENTS."name", STUDENTS.SCHOOL_ID FROM STUDENTS WHERE STUDENTS.SCHOOL_ID = 1
             * SELECT NOTES.ID, NOTES.TEXT, NOTES.STUDENT_ID FROM NOTES WHERE NOTES.STUDENT_ID IN (1, 2)
             * ```
             */
            School.all().with(School::students, Student::notes)

            val cache = TransactionManager.current().entityCache

            cache.getReferrers<Student>(school1.id, Students.school)?.toList().orEmpty() shouldBeEqualTo
                    listOf(student1, student2)
            cache.getReferrers<Note>(student1.id, Notes.student)?.single() shouldBeEqualTo note1
            cache.getReferrers<Note>(student2.id, Notes.student)?.single() shouldBeEqualTo note2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload back referrence on a sized iterable`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Students, StudentBios) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school1 }
            val bio1 = StudentBio.new { dateOfBirth = "2000-01-01"; student = student1 }
            val bio2 = StudentBio.new { dateOfBirth = "2002-01-01"; student = student2 }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1
                val cache = TransactionManager.current().entityCache

                /**
                 * Fetch eager loading
                 *
                 * ```sql
                 * SELECT STUDENTS.ID, STUDENTS."name", STUDENTS.SCHOOL_ID
                 *   FROM STUDENTS;
                 *
                 * SELECT STUDENT_BIOS.ID, STUDENT_BIOS.DATE_OF_BIRTH, STUDENT_BIOS.STUDENT_ID
                 *   FROM STUDENT_BIOS
                 *  WHERE STUDENT_BIOS.STUDENT_ID IN (1, 2)
                 * ```
                 */
                Student.all().with(Student::bio)

                cache.getReferrers<StudentBio>(student1.id, StudentBios.student)?.single() shouldBeEqualTo bio1
                cache.getReferrers<StudentBio>(student2.id, StudentBios.student)?.single() shouldBeEqualTo bio2
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload back referrence on a entity`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Students, StudentBios) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school1 }
            val bio1 = StudentBio.new { dateOfBirth = "2000-01-01"; student = student1 }
            val bio2 = StudentBio.new { dateOfBirth = "2002-01-01"; student = student2 }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1
                val cache = TransactionManager.current().entityCache

                /**
                 * Fetch eager loading
                 * ```sql
                 * SELECT STUDENTS.ID, STUDENTS."name", STUDENTS.SCHOOL_ID
                 *   FROM STUDENTS;
                 *
                 * SELECT STUDENT_BIOS.ID,
                 *        STUDENT_BIOS.DATE_OF_BIRTH,
                 *        STUDENT_BIOS.STUDENT_ID
                 *   FROM STUDENT_BIOS
                 *  WHERE STUDENT_BIOS.STUDENT_ID = 1
                 * ```
                 */
                Student.all().first().load(Student::bio)

                cache.getReferrers<StudentBio>(student1.id, StudentBios.student)
                    ?.toList().orEmpty() shouldBeEqualTo listOf(bio1)

                cache.getReferrers<StudentBio>(student2.id, StudentBios.student)
                    ?.toList().orEmpty().shouldBeEmpty()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `reference cache doesn't fully invalidated on set entity reference`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Students, StudentBios) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school1 }
            val bio1 = StudentBio.new { dateOfBirth = "2000-01-01"; student = student1 }

            student1.bio shouldBeEqualTo bio1
            bio1.student shouldBeEqualTo student1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `nested entity initialization`(testDB: TestDB) {
        withTables(testDB, Posts, Categories, Boards) {
            val post = Post.new {
                parent = Post.new {
                    board = Board.new { name = "Parent Board" }
                    category = Category.new { title = "Parent Category" }
                }
                category = Category.new { title = "Child Category" }
                optCategory = parent!!.category
            }

            post.parent?.board?.name shouldBeEqualTo "Parent Board"
            post.parent?.category?.title shouldBeEqualTo "Parent Category"
            post.optCategory?.title shouldBeEqualTo "Parent Category"
            post.category?.title shouldBeEqualTo "Child Category"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `explicit entity constructor`(testDB: TestDB) {
        var createBoardCalled = false

        fun createBoard(id: EntityID<Int>): Board {
            createBoardCalled = true
            return Board(id)
        }

        val boardEntityClass = object: IntEntityClass<Board>(Boards, entityCtor = ::createBoard) {}

        withTables(testDB, Boards) {
            val board = boardEntityClass.new { name = "Test Board" }
            board.name shouldBeEqualTo "Test Board"

            createBoardCalled.shouldBeTrue()
        }
    }

    object RequestsTable: IdTable<String>() {
        val requestId: Column<String> = varchar("requestId", 255)
        override val primaryKey = PrimaryKey(requestId)
        override val id: Column<EntityID<String>> = requestId.entityId()
    }

    class Request(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, Request>(RequestsTable)

        var requestId by RequestsTable.requestId
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select from string id table with primary key by column`(testDB: TestDB) {
        withTables(testDB, RequestsTable) {
            Request.new {
                requestId = "123"
            }

            Request.all().count() shouldBeEqualTo 1L
        }
    }

    object CreditCards: IntIdTable("CreditCards") {
        val number = varchar("number", 16)
        val spendingLimit = ulong("spendingLimit").databaseGenerated()
    }

    class CreditCard(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<CreditCard>(CreditCards)

        var number by CreditCards.number
        var spendingLimit by CreditCards.spendingLimit
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `database generated value`(testDB: TestDB) {
        withTables(testDB, CreditCards) {
            when (testDB) {
                TestDB.POSTGRESQL -> {
                    // The value can also be set using a SQL trigger
                    exec(
                        """
                        CREATE OR REPLACE FUNCTION set_spending_limit()
                          RETURNS TRIGGER
                          LANGUAGE PLPGSQL
                          AS
                        $$
                        BEGIN
                            NEW."spendingLimit" := 10000;
                            RETURN NEW;
                        END;
                        $$;
                        """.trimIndent()
                    )
                    exec(
                        """
                        CREATE TRIGGER set_spending_limit
                        BEFORE INSERT
                        ON CreditCards
                        FOR EACH ROW
                        EXECUTE PROCEDURE set_spending_limit();
                        """.trimIndent()
                    )
                }

                else              -> {
                    // This table is only used to get the statement that adds the DEFAULT value, and use it with exec
                    val creditCards2 = object: IntIdTable("CreditCards") {
                        val spendingLimit = ulong("spendingLimit").default(10000uL)
                    }
                    val missingStatements = SchemaUtils.addMissingColumnsStatements(creditCards2)
                    missingStatements.forEach {
                        exec(it)
                    }
                }
            }

            /**
             * ```sql
             * INSERT INTO CREDITCARDS ("number") VALUES ('0000111122223333')
             * ```
             */

            val creditCardId = CreditCards.insertAndGetId {
                it[number] = "0000111122223333"
            }.value

            /**
             * ```sql
             * SELECT CREDITCARDS.ID,
             *        CREDITCARDS."number",
             *        CREDITCARDS."spendingLimit"
             *   FROM CREDITCARDS
             *  WHERE CREDITCARDS.ID = 1
             * ```
             */
            CreditCards.selectAll()
                .where { CreditCards.id eq creditCardId }
                .single()[CreditCards.spendingLimit] shouldBeEqualTo 10000uL

            /**
             * ```sql
             * INSERT INTO CREDITCARDS ("number") VALUES ('0000111122223333')
             * ```
             * ```sql
             * SELECT CREDITCARDS.ID,
             *        CREDITCARDS."number",
             *        CREDITCARDS."spendingLimit"
             *   FROM CREDITCARDS
             *  WHERE CREDITCARDS.ID = 2
             * ```
             */
            val creditCard = CreditCard.new {
                number = "0000111122223333"
            }.apply {
                flush()
            }
            creditCard.spendingLimit shouldBeEqualTo 10000uL
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `use entityId parameters`(testDB: TestDB) {
        withTables(testDB, CreditCards) {
            val newCard = CreditCard.new {
                number = "0000111122223333"
                spendingLimit = 10000uL
            }

            val conditionalId = Case()
                .When(CreditCards.spendingLimit less 500uL, CreditCards.id)
                .Else(idParam(newCard.id, CreditCards.id))

            /**
             * ```sql
             * SELECT
             *      CASE
             *          WHEN CREDITCARDS."spendingLimit" < 500 THEN CREDITCARDS.ID
             *          ELSE 1
             *      END
             * FROM CREDITCARDS
             * ```
             */
            CreditCards.select(conditionalId)
                .single()[conditionalId] shouldBeEqualTo newCard.id

            /**
             * ```sql
             * SELECT CREDITCARDS."spendingLimit"
             *   FROM CREDITCARDS
             *  WHERE CREDITCARDS.ID = 1
             * ```
             */
            CreditCards.select(CreditCards.spendingLimit)
                .where { CreditCards.id eq idParam(newCard.id, CreditCards.id) }
                .single()[CreditCards.spendingLimit] shouldBeEqualTo 10000uL
        }
    }

    object Countries: IdTable<String>("Countries") {
        override val id = varchar("id", 3).uniqueIndex().entityId()
        val name = text("name")
    }

    class Country(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, Country>(Countries)

        var name by Countries.name
        val dishes by Dish referrersOn Dishes.country  // one-to-many
    }

    object Dishes: IntIdTable("Dishes") {
        val name = text("name")
        val country = reference("country_id", Countries)
    }

    class Dish(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Dish>(Dishes)

        var name by Dishes.name
        var country by Country referencedOn Dishes.country  // many-to-one
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `eager loading with string parent id`(testDB: TestDB) {
        withTables(testDB, Countries, Dishes, configure = { keepLoadedReferencesOutOfTransaction = true }) {
            val koreaId = Countries.insertAndGetId {
                it[id] = "KOR"
                it[name] = "Korea"
            }.value
            val korea = Country.findById(koreaId)!!

            Dish.new {
                name = "Kimchi"
                country = Country[koreaId]
            }
            Dish.new {
                name = "Bibimbap"
                country = Country[koreaId]
            }
            Dish.new {
                name = "Bulgogi"
                country = Country[koreaId]
            }

            flushCache()
            debug = true

            /**
             * ```sql
             * SELECT COUNTRIES.ID, COUNTRIES."name" FROM COUNTRIES
             * ```
             * ```sql
             * SELECT DISHES.ID,
             *        DISHES."name",
             *        DISHES.COUNTRY_ID
             *   FROM DISHES
             *  WHERE DISHES.COUNTRY_ID = 'KOR'
             * ```
             */
            Country.all().with(Country::dishes)  // fetch eager loading

            statementStats
                .filterKeys { it.startsWith("SELECT ") }
                .forEach { (_, stats) ->
                    val (count, _) = stats
                    count shouldBeEqualTo 1
                }

            debug = false
        }
    }

    object Customers: IntIdTable("Customers") {
        val emailAddress = varchar("emailAddress", 30).uniqueIndex()
        val fullName = text("fullName")
    }

    class Customer(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Customer>(Customers)

        var emailAddress by Customers.emailAddress
        var name by Customers.fullName
        val orders by Order referrersOn Orders.customer
    }

    object Orders: IntIdTable("Orders") {
        val orderName = text("orderName")
        val customer = reference("customer_id", Customers)
    }

    class Order(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Order>(Orders)

        var name by Orders.orderName
        var customer by Customer referencedOn Orders.customer
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `eager loading with reference different from parent id`(testDB: TestDB) {
        withTables(testDB, Customers, Orders, configure = { keepLoadedReferencesOutOfTransaction = true }) {
            val customer1 = Customer.new {
                emailAddress = "customer1@testing.com"
                name = "Customer1"
            }
            val customer2 = Customer.new {
                emailAddress = "customer2@testing.com"
                name = "Customer2"
            }
            val order1 = Order.new {
                name = "Order1"
                customer = customer1
            }
            val order2 = Order.new {
                name = "Order2"
                customer = customer1
            }
            val order3 = Order.new {
                name = "Order1"
                customer = customer2
            }

            commit()

            /**
             * ```sql
             * SELECT CUSTOMERS.ID, CUSTOMERS."emailAddress", CUSTOMERS."fullName" FROM CUSTOMERS
             * SELECT ORDERS.ID, ORDERS."orderName", ORDERS.CUSTOMER_ID FROM ORDERS WHERE ORDERS.CUSTOMER_ID IN (1, 2)
             * ```
             */
            Customer.all().with(Customer::orders)

            val cache = TransactionManager.current().entityCache

            cache.getReferrers<Order>(customer1.id, Orders.customer)
                ?.toList().orEmpty() shouldBeEqualTo listOf(order1, order2)

            cache.getReferrers<Order>(customer2.id, Orders.customer)
                ?.toList().orEmpty() shouldBeEqualTo listOf(order3)
        }
    }

    object TestTable: IntIdTable("TestTable") {
        val value = integer("value")
    }

    class TestEntityA(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TestEntityA>(TestTable)

        var value by TestTable.value
    }

    class TestEntityB(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TestEntityB>(TestTable)

        var value by TestTable.value
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `different entities mapped to the same table`(testDB: TestDB) {
        withTables(testDB, TestTable) {
            val entityA = TestEntityA.new {
                value = 1
            }
            val entityB = TestEntityB.new {
                value = 2
            }

            flushCache()

            TestTable.selectAll().toList().map { it[TestTable.value] } shouldBeEqualTo listOf(1, 2)

            entityA.value = 3
            entityB.value = 4

            flushCache()

            TestTable.selectAll().toList().map { it[TestTable.value] } shouldBeEqualTo listOf(3, 4)
        }
    }
}
