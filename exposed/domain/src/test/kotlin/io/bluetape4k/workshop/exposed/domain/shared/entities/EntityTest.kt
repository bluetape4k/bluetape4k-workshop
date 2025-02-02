package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.id.SnowflakeIdTable
import io.bluetape4k.exposed.sql.timebasedGenerated
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.POSTGRESQL
import io.bluetape4k.workshop.exposed.dao.idValue
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.AEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.BEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XTable
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XType.A
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XType.B
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.YEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.YTable
import io.bluetape4k.workshop.exposed.withTables
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
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder.ASC
import org.jetbrains.exposed.sql.SortOrder.DESC
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

@Suppress("UNUSED_VARIABLE")
class EntityTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * 기본값을 이용하여 Entity를 생성합니다.
     *
     * ```sql
     * INSERT INTO xtable (b1, b2) VALUES (TRUE, FALSE)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults 01`(testDB: TestDB) {
        withTables(testDB, YTable, XTable) {
            // INSERT INTO XTABLE (B1, B2) VALUES (TRUE, FALSE)
            val x = XEntity.new { }
            x.b1.shouldBeTrue()
            x.b2.shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults 02`(testDB: TestDB) {
        withTables(testDB, YTable, XTable) {
            // INSERT INTO XTABLE (B1, B2, Y1) VALUES (FALSE, FALSE, NULL)
            val a = AEntity.create(false, A)
            // INSERT INTO XTABLE (B1, B2, Y1) VALUES (FALSE, FALSE, '2ylqB2ttjrAtFxMhKKNxd')
            val b = AEntity.create(false, B) as BEntity
            // INSERT INTO YTABLE (UUID, X) VALUES ('2ylqB2ttjrAtFxMhKKNxd', FALSE)
            val y = YEntity.new { x = false }

            a.b1.shouldBeFalse()
            b.b1.shouldBeFalse()
            b.b2.shouldBeFalse()

            b.y = y

            // SELECT xtable.id, xtable.b1, xtable.b2, xtable.y1 FROM xtable WHERE xtable.y1 = '2yyUmsCl04kze6hatBKj5'
            b.y!!.x.shouldBeFalse()
            y.b.shouldNotBeNull()
        }
    }

    /**
     * [Humans] 의 Text 컬럼이 `eagerLoading = true` 이므로, 조회 시 Text 컬럼도 조회됩니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `text field outside the transaction`(testDB: TestDB) {
        val objectsToVerify = arrayListOf<Pair<Human, TestDB>>()

        withTables(testDB, Humans) {
            // INSERT INTO HUMAN (H) VALUES ('foo')
            val y1 = Human.new { h = "foo" }

            entityCache.clear()

            /**
             * ```sql
             * SELECT human.id, human.h FROM human WHERE human.id = 1
             * ```
             */
            y1.refresh(flush = false)

            objectsToVerify.add(y1 to testDB)
        }
        objectsToVerify.forEach { (human, testDB) ->
            log.debug { "Verifying $human in $testDB" }
            human.h shouldBeEqualTo "foo"
        }
    }

    /**
     * 엔티티 생성 시 `id`를 지정하여 생성합니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `new with id and refresh`(testDB: TestDB) {
        val objectsToVerify = arrayListOf<Pair<Human, TestDB>>()

        withTables(testDB, Humans) {
            // INSERT INTO human (id, h) VALUES (2, 'foo')
            val x = Human.new(2) { h = "foo" }

            // SELECT human.id, human.h FROM human WHERE human.id = 2
            x.refresh(flush = true)
            objectsToVerify.add(x to testDB)
        }

        objectsToVerify.forEach { (human, testDB) ->
            log.debug { "Verifying $human in $testDB" }
            human.h shouldBeEqualTo "foo"
            human.id.value shouldBeEqualTo 2
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS single (
     *      id SERIAL PRIMARY KEY
     * )
     * ```
     */
    internal object OneAutoFieldTable: IntIdTable("single")

    internal class SingleFieldEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<SingleFieldEntity>(OneAutoFieldTable)

        override fun equals(other: Any?): Boolean = other is SingleFieldEntity && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "SingleFieldEntity(id=$idValue)"
    }

    /**
     * Primary Key 하나만 있는 엔티티에 대한 작업
     *
     * ```sql
     * INSERT INTO single  DEFAULT VALUES
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one field entity`(testDB: TestDB) {
        withTables(testDB, OneAutoFieldTable) {
            val entity = SingleFieldEntity.new { }
            commit()
            entity.id.value shouldBeGreaterThan 0
        }
    }

    /**
     * one-to-one 관계의 엔티티 사용 예
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `back reference 01`(testDB: TestDB) {
        withTables(testDB, YTable, XTable) {
            /**
             * ```sql
             * INSERT INTO YTABLE (UUID) VALUES ('2ylqB3I4kotn2yLdTR2uo')
             * ```
             */
            val y = YEntity.new { }
            entityCache.clear()

            /**
             * ```sql
             * INSERT INTO XTABLE (B1, B2, Y1) VALUES (TRUE, FALSE, '2ylqB3I4kotn2yLdTR2uo')
             * ```
             */
            val b = BEntity.new { }
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

    /**
     * one-to-one 관계의 엔티티 사용 예
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `back reference 02`(testDB: TestDB) {
        withTables(testDB, YTable, XTable) {
            /**
             * ```sql
             * INSERT INTO xtable (b1, b2) VALUES (TRUE, FALSE)
             * ```
             */
            val b = BEntity.new { }
            entityCache.clear()

            /**
             * ```sql
             * INSERT INTO ytable (uuid) VALUES ('2yyth2K9Vn5H7zre4NsdE')
             * ```
             */
            val y = YEntity.new { }

            /**
             * ```sql
             * UPDATE xtable SET y1='2yyth2K9Vn5H7zre4NsdE' WHERE id = 1
             * ```
             */
            b.y = y

            /**
             * ```sql
             * SELECT xtable.id, xtable.b1, xtable.b2, xtable.y1
             *   FROM xtable
             *  WHERE xtable.y1 = '2yyth2K9Vn5H7zre4NsdE'
             * ```
             */
            y.b shouldBeEqualTo b
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS board (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * );
     * ALTER TABLE board ADD CONSTRAINT board_name_unique UNIQUE ("name");
     * ```
     */
    object Boards: IntIdTable("board") {
        val name = varchar("name", 255).uniqueIndex()
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS posts (
     *      id BIGSERIAL PRIMARY KEY,
     *      board INT NULL,
     *      parent BIGINT NULL,
     *      category VARCHAR(22) NULL,
     *      "optCategory" VARCHAR(22) NULL
     * );
     *
     * ALTER TABLE posts
     *      ADD CONSTRAINT posts_category_unique UNIQUE (category);
     *
     * ALTER TABLE posts
     *      ADD CONSTRAINT fk_posts_board__id FOREIGN KEY (board) REFERENCES board(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT;
     * ALTER TABLE posts
     *      ADD CONSTRAINT fk_posts_parent__id FOREIGN KEY (parent) REFERENCES posts(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT;
     * ALTER TABLE posts
     *      ADD CONSTRAINT fk_posts_category__uniqueid FOREIGN KEY (category) REFERENCES categories("uniqueId")
     *          ON DELETE RESTRICT ON UPDATE RESTRICT;
     * ALTER TABLE posts
     *      ADD CONSTRAINT fk_posts_optcategory__uniqueid FOREIGN KEY ("optCategory") REFERENCES categories("uniqueId")
     *          ON DELETE RESTRICT ON UPDATE RESTRICT;
     * ```
     */
    object Posts: LongIdTable("posts") {
        val boardId = optReference("board_id", Boards.id)
        val parentId = optReference("parent_id", Posts.id)
        val categoryId = optReference("category_uniqueId", Categories.uniqueId).uniqueIndex()
        val optCategoryId = optReference("optCategory_uniqueId", Categories.uniqueId)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS categories (
     *      id SERIAL PRIMARY KEY,
     *      "uniqueId" VARCHAR(22) NOT NULL,
     *      title VARCHAR(50) NOT NULL
     * );
     *
     * ALTER TABLE categories
     *      ADD CONSTRAINT categories_uniqueid_unique UNIQUE ("uniqueId");
     * ```
     */
    object Categories: IntIdTable("categories") {
        val uniqueId = varchar("uniqueId", 22).timebasedGenerated().uniqueIndex()
        val title = varchar("title", 50)
    }

    class Board(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Board>(Boards)

        var name by Boards.name
        val posts by Post optionalReferrersOn Posts.boardId  // one-to-many

        override fun equals(other: Any?): Boolean = other is Board && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Board(id=$idValue, name=$name)"
    }

    class Post(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Post>(Posts)

        var board by Board optionalReferencedOn Posts.boardId     // many-to-one
        var parent by Post optionalReferencedOn Posts.parentId     // many-to-one
        val children by Post optionalReferrersOn Posts.parentId   // one-to-many
        var category: Category? by Category optionalReferencedOn Posts.categoryId   // many-to-one
        var optCategory: Category? by Category optionalReferencedOn Posts.optCategoryId  // many-to-one

        override fun equals(other: Any?): Boolean = other is Post && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String =
            "Post(id=$idValue, parent=${parent?.idValue}, boardId=${board?.idValue}, categoryId=${category?.idValue})"
    }

    class Category(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Category>(Categories)

        val uniqueId by Categories.uniqueId
        var title by Categories.title
        val posts by Post optionalReferrersOn Posts.optCategoryId  // one-to-many

        override fun equals(other: Any?): Boolean = other is Category && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Category(id=$idValue, title=$title)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `self reference table`(testDB: TestDB) {
        SchemaUtils
            .sortTablesByReferences(listOf(Posts, Boards, Categories)) shouldBeEqualTo
                listOf(
                    Boards,
                    Categories,
                    Posts
                )
        SchemaUtils
            .sortTablesByReferences(listOf(Categories, Posts, Boards)) shouldBeEqualTo
                listOf(
                    Categories,
                    Boards,
                    Posts
                )
        SchemaUtils
            .sortTablesByReferences(listOf(Posts)) shouldBeEqualTo listOf(Boards, Categories, Posts)
    }

    /**
     * flush 없이도 자식 엔티티를 추가할 수 있습니다.
     *
     * ```sql
     * -- create post & categories
     * INSERT INTO categories (title, "uniqueId") VALUES ('title', '2yyu0zikfU2NzODARoW04');
     * INSERT INTO posts ("category_uniqueId") VALUES ('2yyu0zikfU2NzODARoW04');
     *
     * -- create post
     * INSERT INTO posts (parent_id) VALUES (1);
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert child without flush`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {

            val parent = Post.new {
                this.category = Category.new { title = "title" }
            }
            Post.new { this.parent = parent }

            Post.all().count() shouldBeEqualTo 2L

            /**
             * one-to-many의 count 만 따로 수행하는 쿼리가 된다.
             *
             * JPA 에서는 `@Formula` 를 사용해서 직접 쿼리를 작성해야 합니다.
             * ```
             * @Formula("(select count(p.id) from post p where p.parent_id= id)")
             * private var childrenCount: Long = 0
             * ```
             *
             * Hibernate의 `@LazyCollection(EXTRA)` 사용하면 되는데, Deprecated 되었습니다.
             *
             * ```sql
             * SELECT COUNT(*) FROM posts WHERE posts.parent_id = 1
             * ```
             */
            parent.children.count() shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert non child without flush`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            // 여기서 Board에 대해 flush 된다.
            // INSERT INTO board ("name") VALUES ('ireelevant')
            val board = Board.new { name = "ireelevant" }

            // 여기서 Post 에 대해 flush 된다.
            // INSERT INTO posts (board_id) VALUES (1)
            Post.new { this.board = board }  // first flush before referencing

            flushCache().size shouldBeEqualTo 1
        }
    }

    /**
     * 부모 엔티티들을 조회하여 작업 시, 자식 엔티티들을 조회하는 작업이 가능합니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `queries within other query iterator works fine`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            val board1 = Board.new { name = "irrelevant" }
            val board2 = Board.new { name = "relavant" }
            val post1 = Post.new { board = board1 }

            Board.all().forEach { board ->
                board.posts.count() to board.posts.toList()

                val text = Post
                    .find { Posts.boardId eq board.id }
                    .joinToString { post -> post.board?.name.orEmpty() }
                log.debug { "text: $text" }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert child with flush`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            /**
             * ```sql
             * INSERT INTO categories (title, "uniqueId") VALUES ('title', '2yyUmrU195lH4hYKQGUhm');
             * INSERT INTO posts ("category_uniqueId") VALUES ('2yyUmrU195lH4hYKQGUhm')
             *
             */
            val parent = Post.new {
                this.category = Category.new { title = "title" }
            }
            flushCache()
            parent.id._value.shouldNotBeNull()

            // INSERT INTO posts (parent_id) VALUES (1)
            Post.new { this.parent = parent }

            flushCache().size shouldBeEqualTo 1

            // SELECT COUNT(*) FROM POSTS
            Post.all().count() shouldBeEqualTo 2L

            // SELECT COUNT(*) FROM POSTS WHERE POSTS.PARENT = 1
            parent.children.count() shouldBeEqualTo 1L
        }
    }

    /**
     * Insert child with child
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert child with child`(testDB: TestDB) {
        withTables(testDB, Boards, Posts, Categories) {
            val parent = Post.new {
                this.category = Category.new { title = "title1" }
            }
            val child1 = Post.new {
                this.parent = parent
                this.category = Category.new { title = "title2" }
            }
            // insert grand child
            Post.new {
                this.parent = child1
            }

            entityCache.clear()

            // SELECT COUNT(*) FROM posts
            Post.all().count() shouldBeEqualTo 3L

            // SELECT COUNT(*) FROM posts WHERE posts.parent_id = 1
            parent.children.count() shouldBeEqualTo 1L

            // SELECT COUNT(*) FROM posts WHERE posts.parent_id = 2
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

    /**
     * 삭제된 엔티티를 갱신하려고 하면 예외가 발생합니다.
     */
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

    /**
     * DSL 방식으로 행을 삭제했을 때에도, cache 에서도 삭제됩니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `cache invalidated on DSL delete`(testDB: TestDB) {
        withTables(testDB, Boards) {
            // 엔티티 생성 및 삭제 시, 캐시에서 제거됩니다.
            val board1 = Board.new { name = "irrelevant" }
            Board.testCache(board1.id).shouldNotBeNull()
            board1.delete()
            Board.testCache(board1.id).shouldBeNull()

            val board2 = Board.new { name = "irrelevant" }
            Board.testCache(board2.id).shouldNotBeNull()

            // DSL 방식으로 Delete 해도 Cache예서 제거된다.
            Boards.deleteWhere { id eq board2.id }
            Board.testCache(board2.id).shouldBeNull()
        }
    }

    /**
     * DSL 방식으로 행을 업데이트했을 때에도, cache 에서도 삭제됩니다.
     */
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

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS items (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      price DECIMAL(10, 2) NOT NULL
     * );
     *
     * ALTER TABLE items ADD CONSTRAINT items_name_unique UNIQUE ("name")
     * ```
     */
    object Items: IntIdTable("items") {
        val name = varchar("name", 255).uniqueIndex()
        val price = decimal("price", 10, 2)
    }

    class Item(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Item>(Items)

        var name by Items.name
        var price by Items.price

        override fun equals(other: Any?): Boolean = other is Item && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Item(id=$id, name=$name, price=$price)"
    }

    /**
     * DSL 방식으로 `upsert` 시에 Cache가 갱신됩니다.
     */
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
            val conflictKeys =
                if (testDB in TestDB.ALL_MYSQL_LIKE) emptyArray<Column<*>>()
                else arrayOf(Items.name)

            /**
             * ```sql
             * INSERT INTO items ("name", price) VALUES ('Item A', 50.0)
             *      ON CONFLICT ("name") DO
             *          UPDATE SET price=EXCLUDED.price
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
             * SELECT ITEMS.ID, ITEMS."name", ITEMS.PRICE
             *   FROM ITEMS
             *  WHERE ITEMS.ID = 1
             * ```
             */
            itemA.refresh(flush = false)
            itemA.price shouldBeEqualTo newPrice
            Item.testCache(itemA.id).shouldNotBeNull()

            /**
             * `batchUpsert` 시에도 캐시는 개싱된다.
             *
             * ```sql
             * INSERT INTO items ("name", price) VALUES ('Item A', 100.0) ON CONFLICT ("name") DO UPDATE SET price=EXCLUDED.price
             * INSERT INTO items ("name", price) VALUES ('Item B', 100.0) ON CONFLICT ("name") DO UPDATE SET price=EXCLUDED.price
             * INSERT INTO items ("name", price) VALUES ('Item C', 100.0) ON CONFLICT ("name") DO UPDATE SET price=EXCLUDED.price
             * INSERT INTO items ("name", price) VALUES ('Item D', 100.0) ON CONFLICT ("name") DO UPDATE SET price=EXCLUDED.price
             * INSERT INTO items ("name", price) VALUES ('Item E', 100.0) ON CONFLICT ("name") DO UPDATE SET price=EXCLUDED.price
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
             * Postgres:
             * ```sql
             * SELECT items.id, items."name", items.price
             *   FROM items
             *  WHERE items.id = 1 FOR UPDATE
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

            /**
             * NOTE: refresh(flush=false)이면 Cache 값이 다시 채워진다.
             *
             * ```sql
             * SELECT items.id, items."name", items.price FROM items
             *  WHERE items.id = 1
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

            /**
             * NOTE: refresh(flush=false)이면 Cache 값이 다시 채워진다.
             *
             * ```sql
             * SELECT ITEMS.ID, ITEMS."name", ITEMS.PRICE
             *   FROM ITEMS
             *  WHERE ITEMS.ID = 1
             * ```
             */
            item.refresh(flush = false)
            item.price shouldBeEqualTo oldPrice
            Item.testCache(item.id).shouldNotBeNull()

            updatedItem shouldBeEqualTo item
            Item.testCache(updatedItem.id).shouldNotBeNull()
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS human (
     *      id SERIAL PRIMARY KEY,
     *      h TEXT NOT NULL
     * )
     * ```
     */
    object Humans: IntIdTable("human") {
        val h = text("h", eagerLoading = true)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS "user" (
     *      id INT NOT NULL,
     *      "name" TEXT NOT NULL,
     *
     *      CONSTRAINT fk_user_id__id FOREIGN KEY (id) REFERENCES human(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Users: IdTable<Int>("user") {
        override val id = reference("id", Humans)   // Humans.id 를 id 로 사용한다.
        val name = text("name")
    }

    open class Human(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Human>(Humans)

        var h by Humans.h

        override fun equals(other: Any?): Boolean = other is Human && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Human(id=$id, h=$h)"
    }

    open class User(id: EntityID<Int>): Entity<Int>(id) {
        companion object: EntityClass<Int, User>(Users) {
            operator fun invoke(name: String): User {
                val h = Human.new {
                    h = name.take(2)
                }
                return User.new(h.id.value) {
                    this.name = name
                }
            }
        }

        val human: Human by Human referencedOn Users.id
        var name: String by Users.name

        override fun equals(other: Any?): Boolean = other is User && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "User(id=$id, name=$name, human=$human)"
    }

    /**
     * one-to-one 관계 엔티티를 생성하기.
     *
     * [User] 엔티티의 생성자에서 [Human]도 엔티티를 생성합니다.
     *
     * ```sql
     * INSERT INTO human (h) VALUES ('te');
     * INSERT INTO "user" (id, "name") VALUES (1, 'testUser');
     *
     * INSERT INTO human (h) VALUES ('te');
     * INSERT INTO "user" (id, "name") VALUES (2, 'testUser');
     *
     * INSERT INTO human (h) VALUES ('te');
     * INSERT INTO "user" (id, "name") VALUES (3, 'testUser');
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-one reference`(testDB: TestDB) {
        withTables(testDB, Humans, Users) {
            repeat(3) {
                val user = User("testUser")  // User와 Hunam을 같이 생성한다.
                user.human.h shouldBeEqualTo "te"
                user.name shouldBeEqualTo "testUser"
                user.id.value shouldBeEqualTo user.human.id.value
            }
        }
    }

    /**
     * Self reference table
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS selfreference (
     *      id SERIAL PRIMARY KEY,
     *      parent_id INT NULL
     * );
     *
     * ALTER TABLE selfreference
     *      ADD CONSTRAINT fk_selfreference_parent_id__id
     *      FOREIGN KEY (parent_id) REFERENCES selfreference(id)
     *      ON DELETE RESTRICT ON UPDATE RESTRICT;
     * ```
     */
    private object SelfReferenceTable: IntIdTable() {
        val parentId = optReference("parent_id", SelfReferenceTable)
    }

    class SelfReferenceEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<SelfReferenceEntity>(SelfReferenceTable)

        var parent: EntityID<Int>? by SelfReferenceTable.parentId
        val children: SizedIterable<SelfReferenceEntity> by SelfReferenceEntity optionalReferrersOn SelfReferenceTable.parentId

        override fun equals(other: Any?): Boolean = other is SelfReferenceEntity && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "SelfReferenceEntity(id=$idValue, parent=$parent)"
    }

    /**
     * 계층구조 (Hierarchy) 를 가지는 엔티티 사용법
     */
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
             * SELECT COUNT(*) FROM selfreference WHERE selfreference.parent_id = 1
             * SELECT COUNT(*) FROM selfreference WHERE selfreference.parent_id = 2
             * SELECT COUNT(*) FROM selfreference WHERE selfreference.parent_id = 3
             * SELECT COUNT(*) FROM selfreference WHERE selfreference.parent_id = 4
             * ```
             */
            parent.children.count() shouldBeEqualTo 2
            child1.children.count() shouldBeEqualTo 1
            child2.children.count() shouldBeEqualTo 0
            grandChild.children.count() shouldBeEqualTo 0
        }
    }

    /**
     * Self reference entity
     *
     * ```sql
     * SELECT selfreference.id,
     *        selfreference.parent_id
     *   FROM selfreference
     *  WHERE selfreference.id = 6
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `self references`(testDB: TestDB) {
        withTables(testDB, SelfReferenceTable) {
            repeat(5) { SelfReferenceEntity.new { } }

            val ref1 = SelfReferenceEntity.new { }
            ref1.parent = ref1.id

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

            val category1: Category = Category.new { title = "category1" }

            val post1 = Post.new {
                optCategory = category1
                category = Category.new { title = "title" }
            }

            // category 에 대한 reference 가 없다.
            val post2 = Post.new {
                optCategory = category1
                parent = post1
            }

            /**
             * ```sql
             * SELECT COUNT(*) FROM POSTS
             * ```
             */
            Post.all().count() shouldBeEqualTo 2L
            /**
             * ```sql
             * SELECT COUNT(*) FROM POSTS
             *  WHERE POSTS."optCategory" = '2ylpPHjEQlD7I6FiOM0Gt'
             * ```
             */
            category1.posts.count() shouldBeEqualTo 2L

            /**
             * ```sql
             * SELECT COUNT(*) FROM POSTS
             *  WHERE POSTS."optCategory" = '2ylpPHjEQlD7I6FiOM0Gt'
             */
            Posts.selectAll()
                .where { Posts.optCategoryId eq category1.uniqueId }
                .count() shouldBeEqualTo 2L

            /**
             * ```sql
             * SELECT COUNT(*) FROM POSTS
             *  WHERE POSTS."optCategory" = '2ylpPHjEQlD7I6FiOM0Gt'
             * ```
             */
            Post.find { Posts.optCategoryId eq category1.uniqueId }
                .count() shouldBeEqualTo 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `call limit on relation doesnt mutate the cached value`(testDB: TestDB) {
        withTables(testDB, Posts, Boards, Categories) {
            /**
             * ```sql
             * INSERT INTO categories (title, "uniqueId") VALUES ('title', '2yyUmpg6lTvWZhXuKomLe')
             * ```
             */
            val category1 = Category.new { title = "category1" }

            /**
             * ```sql
             * INSERT INTO posts ("optCategory_uniqueId", "category_uniqueId")
             * VALUES ('2yyUmpfoSgPuW7iTuwrmC', '2yyUmpg6lTvWZhXuKomLe')
             * ```
             */
            Post.new {
                optCategory = category1
                category = Category.new { title = "title" }
            }

            /**
             * ```sql
             * INSERT INTO posts ("optCategory_uniqueId", "category_uniqueId")
             * VALUES ('2yyUmpfoSgPuW7iTuwrmC', NULL)
             * ```
             */
            Post.new {
                optCategory = category1
            }

            commit()

            /**
             * ```sql
             * SELECT COUNT(*) FROM posts WHERE posts."optCategory_uniqueId" = '2yyUmpfoSgPuW7iTuwrmC'
             * ```
             */
            category1.posts.count() shouldBeEqualTo 2L

            /**
             * ```sql
             * SELECT posts.id,
             *        posts.board_id,
             *        posts.parent_id,
             *        posts."category_uniqueId",
             *        posts."optCategory_uniqueId"
             *   FROM posts
             *  WHERE posts."optCategory_uniqueId" = '2yyUmpfoSgPuW7iTuwrmC'
             * ```
             */
            category1.posts.toList() shouldHaveSize 2

            /**
             * ```sql
             * SELECT posts.id,
             *        posts.board_id,
             *        posts.parent_id,
             *        posts."category_uniqueId",
             *        posts."optCategory_uniqueId"
             *   FROM posts
             *  WHERE posts."optCategory_uniqueId" = '2yyUmpfoSgPuW7iTuwrmC'
             *  LIMIT 1
             * ```
             */
            category1.posts.limit(1).toList() shouldHaveSize 1

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM (
             *      SELECT posts.id posts_id,
             *             posts.board_id posts_board_id,
             *             posts.parent_id posts_parent_id,
             *             posts."category_uniqueId" posts_category_uniqueId,
             *             posts."optCategory_uniqueId" posts_optCategory_uniqueId
             *        FROM posts
             *       WHERE posts."optCategory_uniqueId" = '2yyUmpfoSgPuW7iTuwrmC'
             *       LIMIT 1
             *   ) subquery
             * ```
             */
            category1.posts.limit(1).count() shouldBeEqualTo 1L
            category1.posts.count() shouldBeEqualTo 2L
            category1.posts.toList() shouldHaveSize 2
        }
    }

    /**
     * Order By on Entities
     *
     * ```sql
     * SELECT categories.id, categories."uniqueId", categories.title
     *   FROM categories
     *  ORDER BY categories.title ASC
     * ```
     * ```sql
     * SELECT categories.id, categories."uniqueId", categories.title
     *   FROM categories
     *  ORDER BY categories.title DESC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `order by on entities`(testDB: TestDB) {
        withTables(testDB, Categories) {
            Categories.deleteAll()

            val category1 = Category.new { title = "Test1" }
            val category3 = Category.new { title = "Test3" }
            val category2 = Category.new { title = "Test2" }

            Category.all().toList() shouldBeEqualTo listOf(category1, category3, category2)

            Category.all()
                .orderBy(Categories.title to ASC)
                .toList() shouldBeEqualTo listOf(category1, category2, category3)

            Category.all()
                .orderBy(Categories.title to DESC)
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

            // UPDATE posts SET "category_uniqueId"='2yyu0zVgeYZIM5rD75ABz' WHERE id = 1
            post1.category = category2

            // INSERT INTO posts ("category_uniqueId") VALUES ('2yyu0zVgeYZIM5rD75ABy')
            val post2 = Post.new { category = category1 }

            flushCache()

            /**
             * ```sql
             * SELECT posts.id,
             *        posts.board_id,
             *        posts.parent_id,
             *        posts."category_uniqueId",
             *        posts."optCategory_uniqueId"
             *   FROM posts
             *  WHERE posts.id = 1
             * ```
             */
            Post.reload(post1)
            /**
             * ```sql
             * SELECT posts.id,
             *        posts.board_id,
             *        posts.parent_id,
             *        posts."category_uniqueId",
             *        posts."optCategory_uniqueId"
             *   FROM posts
             *  WHERE posts.id = 2
             * ```
             */
            Post.reload(post2)

            post1.category shouldBeEqualTo category2
            post2.category shouldBeEqualTo category1
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS parents (id BIGINT PRIMARY KEY, "name" VARCHAR(50) NOT NULL)
     * ```
     */
    object Parents: SnowflakeIdTable("parents") {
        val name = varchar("name", 50)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS children (
     *      id BIGSERIAL PRIMARY KEY,
     *      parent_id BIGINT NOT NULL,
     *      "name" VARCHAR(80) NOT NULL,
     *
     *      CONSTRAINT fk_children_parent_id__id FOREIGN KEY (parent_id) REFERENCES parents(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Children: LongIdTable("children") {
        val parentId = reference("parent_id", Parents)
        val name = varchar("name", 80)
    }

    open class Parent(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Parent>(Parents)

        var name by Parents.name

        override fun equals(other: Any?): Boolean = other is Parent && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Parent(id=$id, name=$name)"
    }

    open class Child(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Child>(Children)

        var parent by Parent referencedOn Children.parentId
        var name by Children.name

        override fun equals(other: Any?): Boolean = other is Child && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Child(id=$id, parent=$parent, name=$name)"
    }

    /**
     * Id 값을 지정하여 엔티티를 생성합니다.
     *
     * ```sql
     * INSERT INTO parents (id, "name") VALUES (10, 'parent');
     * INSERT INTO children (id, parent_id, "name") VALUES (100, 10, 'child');
     * ```
     */
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

    /**
     * 생성된 새로운 엔티티를 `flush` 하면 `true`를 반환합니다.
     */
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

    /**
     * 트랜잭션마다 새로운 `EntityCache`를 생성합니다.
     * 그러므로 트랜잭션 간에 엔티티를 공유할 수 없습니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `sharing entity between transaction with entity cache`(testDB: TestDB) {
        withTables(testDB, Humans) {
            // Transaction 1 에서 hunam1 생성
            val human1 = newTransaction {
                maxAttempts = 1
                Human.new { h = "foo" }
            }

            newTransaction {
                maxAttempts = 1
                // 현재 케시에 존재하지 않아서 새롭게 읽는다.
                Human.testCache(human1.id).shouldBeNull()
                Humans.selectAll().single()[Humans.h] shouldBeEqualTo "foo"

                // Update 한다
                human1.h = "bar"
                Human.testCache(human1.id) shouldBeEqualTo human1
                Humans.selectAll().single()[Humans.h] shouldBeEqualTo "bar"
            }

            newTransaction {
                maxAttempts = 1
                // 현재 케시에 존재하지 않아서 새롭게 읽는다.
                Human.testCache(human1.id).shouldBeNull()
                Humans.selectAll().single()[Humans.h] shouldBeEqualTo "bar"
            }
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS regions (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * )
     * ```
     */
    object Regions: IntIdTable("regions") {
        val name = varchar("name", 255)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS students (
     *      id BIGSERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      school_id INT NOT NULL,
     *
     *      CONSTRAINT fk_students_school_id__id FOREIGN KEY (school_id) REFERENCES schools(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Students: LongIdTable("students") {
        val name = varchar("name", 255)
        val school = reference("school_id", Schools)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS student_bios (
     *      id BIGSERIAL PRIMARY KEY,
     *      date_of_birth VARCHAR(25) NOT NULL,
     *      student_id BIGINT NOT NULL,
     *
     *      CONSTRAINT fk_student_bios_student_id__id FOREIGN KEY (student_id) REFERENCES students(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object StudentBios: LongIdTable("student_bios") {
        val dateOfBirth = varchar("date_of_birth", 25)
        val student = reference("student_id", Students)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS notes (
     *      id BIGSERIAL PRIMARY KEY,
     *      "text" VARCHAR(255) NOT NULL,
     *      student_id BIGINT NOT NULL,
     *
     *      CONSTRAINT fk_notes_student_id__id FOREIGN KEY (student_id) REFERENCES students(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Notes: LongIdTable("notes") {
        val text = varchar("text", 255)
        val student = reference("student_id", Students)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS detentions (
     *      id BIGSERIAL PRIMARY KEY,
     *      reason VARCHAR(255) NOT NULL,
     *      student_id BIGINT NULL,
     *
     *      CONSTRAINT fk_detentions_student_id__id FOREIGN KEY (student_id) REFERENCES students(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     * ```
     */
    object Detentions: LongIdTable("detentions") {
        val reason = varchar("reason", 255)
        val student = optReference("student_id", Students)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS holidays (
     *      id BIGSERIAL PRIMARY KEY,
     *      holiday_start BIGINT NOT NULL,
     *      holiday_end BIGINT NOT NULL
     * )
     * ```
     */
    object Holidays: LongIdTable("holidays") {
        val holidayStart = long("holiday_start")
        val holidayEnd = long("holiday_end")
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS school_holidays (
     *      school_id INT,
     *      holiday_id BIGINT,
     *
     *      CONSTRAINT pk_school_holidays PRIMARY KEY (school_id, holiday_id),
     *      CONSTRAINT fk_school_holidays_school_id__id FOREIGN KEY (school_id) REFERENCES schools(id)
     *          ON DELETE CASCADE ON UPDATE CASCADE,
     *      CONSTRAINT fk_school_holidays_holiday_id__id FOREIGN KEY (holiday_id) REFERENCES holidays(id)
     *          ON DELETE CASCADE ON UPDATE CASCADE
     * )
     * ```
     */
    object SchoolHolidays: Table("school_holidays") {
        val school = reference("school_id", Schools, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
        val holiday = reference("holiday_id", Holidays, ReferenceOption.CASCADE, ReferenceOption.CASCADE)

        override val primaryKey = PrimaryKey(school, holiday)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS schools (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      region_id INT NOT NULL,
     *      secondary_region_id INT NULL,
     *
     *      CONSTRAINT fk_schools_region_id__id FOREIGN KEY (region_id) REFERENCES regions(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT,
     *      CONSTRAINT fk_schools_secondary_region_id__id FOREIGN KEY (secondary_region_id) REFERENCES regions(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Schools: IntIdTable("schools") {
        val name = varchar("name", 255).uniqueIndex()
        val region = reference("region_id", Regions)
        val secondaryRegion = optReference("secondary_region_id", Regions)
    }

    class Region(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Region>(Regions)

        var name by Regions.name

        override fun equals(other: Any?): Boolean = other is Region && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Region(id=$idValue, name=$name)"
    }

    @Suppress("UNCHECKED_CAST")
    abstract class ComparableLongEntity<T: LongEntity>(id: EntityID<Long>): LongEntity(id) {
        override fun equals(other: Any?): Boolean = other is ComparableLongEntity<*> && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
    }

    class Student(id: EntityID<Long>): ComparableLongEntity<Student>(id) {
        companion object: LongEntityClass<Student>(Students)

        var name by Students.name
        var school by School referencedOn Students.school
        val bio by StudentBio optionalBackReferencedOn StudentBios.student
        val notes by Note.referrersOn(Notes.student, cache = true)
        val detentions by Detention optionalReferrersOn Detentions.student

        override fun toString(): String = "Student(id=$idValue, name=$name, school=$school)"
    }

    class StudentBio(id: EntityID<Long>): ComparableLongEntity<StudentBio>(id) {
        companion object: LongEntityClass<StudentBio>(StudentBios)

        var student by Student referencedOn StudentBios.student
        var dateOfBirth by StudentBios.dateOfBirth

        override fun toString(): String = "StudentBio(id=$idValue, dateOfBirth=$dateOfBirth, student=$student)"
    }

    class Note(id: EntityID<Long>): ComparableLongEntity<Note>(id) {
        companion object: LongEntityClass<Note>(Notes)

        var text by Notes.text
        var student by Student referencedOn Notes.student

        override fun toString(): String = "Note(id=$idValue, text=$text, student=$student)"
    }

    class Detention(id: EntityID<Long>): ComparableLongEntity<Detention>(id) {
        companion object: LongEntityClass<Detention>(Detentions)

        var reason by Detentions.reason
        var student by Student optionalReferencedOn Detentions.student

        override fun toString(): String = "Detention(id=$idValue, reason=$reason, student=$student)"
    }

    class Holiday(id: EntityID<Long>): ComparableLongEntity<Holiday>(id) {
        companion object: LongEntityClass<Holiday>(Holidays)

        var holidayStart by Holidays.holidayStart
        var holidayEnd by Holidays.holidayEnd

        override fun toString(): String = "Holiday(id=$idValue, holidayStart=$holidayStart, holidayEnd=$holidayEnd)"
    }

    class School(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<School>(Schools)

        var name by Schools.name
        var region by Region referencedOn Schools.region
        var secondaryRegion by Region optionalReferencedOn Schools.secondaryRegion
        val students: SizedIterable<Student> by Student.referrersOn(Students.school, cache = true)
        var holidays: SizedIterable<Holiday> by Holiday via SchoolHolidays

        override fun equals(other: Any?): Boolean = other is School && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String =
            "School(id=$idValue, name=$name, region=$region, secondaryRegion=$secondaryRegion)"
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
                 * Eager loading many-to-one reference
                 *
                 * ```sql
                 * SELECT SCHOOLS.ID, SCHOOLS."name", SCHOOLS.REGION_ID, SCHOOLS.SECONDARY_REGION_ID
                 *   FROM SCHOOLS;
                 *
                 * SELECT REGIONS.ID, REGIONS."name"
                 *   FROM REGIONS
                 *  WHERE REGIONS.ID IN (1, 2);
                 * ```
                 */
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

    /**
     * Eager loading many-to-one reference
     */
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
                 * Postgres:
                 * ```sql
                 * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id FROM schools;
                 * SELECT regions.id, regions."name" FROM regions WHERE regions.id = 1;
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
                 * Postgres:
                 * ```sql
                 * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id FROM schools LIMIT 1;
                 * SELECT regions.id, regions."name" FROM regions WHERE regions.id = 1;
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
                 * Postgres:
                 * ```sql
                 * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id FROM schools;
                 * SELECT regions.id, regions."name" FROM regions WHERE regions.id = 1;
                 * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id FROM schools LIMIT 1;
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

    /**
     * Eager loading many-to-one reference
     *
     * ```sql
     * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id FROM schools WHERE schools.id = 1
     * SELECT regions.id, regions."name" FROM regions WHERE regions.id = 2
     * ```
     */
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

                val school2 = School
                    .find {
                        Schools.id eq school1.id
                    }
                    .first()
                    .load(School::secondaryRegion)  // eager loading for many-to-one reference

                Region.testCache(school2.readValues[Schools.region]).shouldBeNull()
                // load 를 통해 secondaryRegion을 로드한다.
                Region.testCache(school2.readValues[Schools.secondaryRegion]!!) shouldBeEqualTo region2
            }
        }
    }

    /**
     * `with(School::students)` 를 통해 `School` 엔티티의 `students`를 eager loading 한다.
     *
     * ```sql
     * -- load schools
     * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id
     *   FROM schools;
     *
     * -- load students of school
     * SELECT students.id, students."name", students.school_id
     *   FROM students
     *  WHERE students.school_id IN (1, 2, 3)
     * ```
     */
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

                School.all().with(School::students).toList()

                cache.getReferrers<Student>(school1.id, Students.school)
                    ?.toList().orEmpty() shouldBeEqualTo listOf(student1)

                cache.getReferrers<Student>(school2.id, Students.school)
                    ?.toList().orEmpty() shouldBeEqualTo listOf(
                    student2
                )
                cache.getReferrers<Student>(school3.id, Students.school)
                    ?.toList().orEmpty() shouldBeEqualTo listOf(student3, student4)
            }
        }
    }

    /**
     * One-to-many reference ([SizedIterable]) 에 대해 Eagar Loading 수행
     *
     * ```sql
     * SELECT schools.id,
     *        schools."name",
     *        schools.region_id,
     *        schools.secondary_region_id
     *   FROM schools
     *  WHERE schools.id = 1;
     *
     *  SELECT students.id,
     *         students."name",
     *         students.school_id
     *    FROM students
     *   WHERE students.school_id = 1
     * ```
     */
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

                School.find { Schools.id eq school1.id }.first().load(School::students)

                cache.getReferrers<Student>(school1.id, Students.school)?.toList().orEmpty() shouldBeEqualTo
                        listOf(student1, student2, student3)
            }
        }
    }

    /**
     * 2번의 Accosiation을 지정하여 eager loading을 수행
     *
     * ```sql
     * -- load schools
     * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id
     *   FROM schools;
     *
     * -- load students of school
     * SELECT students.id, students."name", students.school_id
     *   FROM students
     *  WHERE students.school_id = 1;
     *
     * -- load detentions of students
     * SELECT detentions.id, detentions.reason, detentions.student_id
     *   FROM detentions
     *  WHERE detentions.student_id IN (1, 2);
     * ```
     */
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

                School.all().with(School::students, Student::detentions)

                cache.getReferrers<Student>(school1.id, Students.school)?.toList().orEmpty() shouldBeEqualTo
                        listOf(student1, student2)

                cache.getReferrers<Detention>(student1.id, Detentions.student)?.toList().orEmpty() shouldBeEqualTo
                        listOf(detention1, detention2)

                cache.getReferrers<Detention>(student2.id, Detentions.student)?.toList().orEmpty().shouldBeEmpty()
            }
        }
    }

    /**
     * Eager loading one-to-many reference
     *
     * ```sql
     * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id
     *   FROM schools;
     *
     * SELECT holidays.id,
     *        holidays.holiday_start,
     *        holidays.holiday_end,
     *        school_holidays.school_id,
     *        school_holidays.holiday_id
     *   FROM holidays INNER JOIN school_holidays ON school_holidays.holiday_id = holidays.id
     *  WHERE school_holidays.school_id IN (1, 2, 3)
     * ```
     */
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

                School.all().with(School::holidays)

                cache.getReferrers<Holiday>(school1.id, SchoolHolidays.school)?.toList().orEmpty() shouldBeEqualTo
                        listOf(holiday1, holiday2)

                cache.getReferrers<Holiday>(school2.id, SchoolHolidays.school)?.toList().orEmpty() shouldBeEqualTo
                        listOf(holiday3)

                cache.getReferrers<Holiday>(school3.id, SchoolHolidays.school)?.toList().orEmpty().shouldBeEmpty()
            }

        }
    }

    /**
     * many-to-many reference 에 대한 eager loading
     *
     * ```sql
     * -- load school
     * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id
     *   FROM schools
     *  WHERE schools.id = 1;
     *
     * -- load holidays of school (many-to-many)
     * SELECT holidays.id,
     *        holidays.holiday_start,
     *        holidays.holiday_end,
     *        school_holidays.school_id,
     *        school_holidays.holiday_id
     *   FROM holidays INNER JOIN school_holidays ON school_holidays.holiday_id = holidays.id
     *  WHERE school_holidays.school_id = 1
     * ```
     */
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

            School.find { Schools.id eq school1.id }.first().load(School::holidays)

            val cache = TransactionManager.current().entityCache

            cache.getReferrers<Holiday>(school1.id, SchoolHolidays.school)?.toList().orEmpty() shouldBeEqualTo
                    listOf(holiday1, holiday2, holiday3)
        }
    }

    /**
     * 2단계의 relation 에 대해 eager loading 하기
     *
     * ```sql
     * -- load school
     * SELECT schools.id, schools."name", schools.region_id, schools.secondary_region_id
     *   FROM schools;
     *
     * -- load students of school
     * SELECT students.id, students."name", students.school_id
     *   FROM students
     *  WHERE students.school_id = 1;
     *
     * -- load notes of students
     * SELECT notes.id, notes."text", notes.student_id
     *   FROM notes
     *  WHERE notes.student_id IN (1, 2);
     * ```
     *
     */
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

            // School -> Students -> Notes 관계를 한번에 eager loading
            School.all().with(School::students, Student::notes)

            val cache = TransactionManager.current().entityCache

            cache.getReferrers<Student>(school1.id, Students.school)?.toList().orEmpty() shouldBeEqualTo
                    listOf(student1, student2)
            cache.getReferrers<Note>(student1.id, Notes.student)?.single() shouldBeEqualTo note1
            cache.getReferrers<Note>(student2.id, Notes.student)?.single() shouldBeEqualTo note2
        }
    }

    /**
     * Eager loading for one-to-one back referenced
     *
     * ```sql
     * SELECT students.id, students."name", students.school_id
     *   FROM students;
     *
     * -- eager loading for back reference
     * SELECT student_bios.id,
     *        student_bios.date_of_birth,
     *        student_bios.student_id
     *   FROM student_bios
     *  WHERE student_bios.student_id IN (1, 2)
     * ```
     */
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

                Student.all().with(Student::bio)

                cache.getReferrers<StudentBio>(student1.id, StudentBios.student)?.single() shouldBeEqualTo bio1
                cache.getReferrers<StudentBio>(student2.id, StudentBios.student)?.single() shouldBeEqualTo bio2
            }
        }
    }

    /**
     * Eager loading for back reference
     *
     * ```sql
     * SELECT students.id, students."name", students.school_id
     *   FROM students;
     *
     * SELECT student_bios.id, student_bios.date_of_birth, student_bios.student_id
     *   FROM student_bios
     *  WHERE student_bios.student_id = 1
     * ```
     */
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

                Student.all().first().load(Student::bio)

                cache.getReferrers<StudentBio>(student1.id, StudentBios.student)
                    ?.toList().orEmpty() shouldBeEqualTo listOf(bio1)

                cache.getReferrers<StudentBio>(student2.id, StudentBios.student)
                    ?.toList().orEmpty().shouldBeEmpty()
            }
        }
    }

    /**
     * one-to-one reference 관계에서 엔티티 참조를 설정한다고 해도 캐시가 완전히 무효화되지 않는다.
     *
     * `student1.bio` 를 캐시에 없으므로 DB에서 읽어온다
     *
     * ```sql
     * SELECT student_bios.id,
     *        student_bios.date_of_birth,
     *        student_bios.student_id
     *   FROM student_bios
     *  WHERE student_bios.student_id = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `reference cache doesn't fully invalidated on set entity reference`(testDB: TestDB) {
        withTables(testDB, Regions, Schools, Students, StudentBios) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school1 }
            val bio1 = StudentBio.new { dateOfBirth = "2000-01-01"; student = student1 }

            // `student1.bio`에 대한 정보가 캐시에는 없다.
            student1.bio shouldBeEqualTo bio1

            // `bio1.student` 는 `bio1` 생성 시 캐시에 저장되어 있다.
            bio1.student shouldBeEqualTo student1
        }
    }

    /**
     * 중첩된 엔티티 초기화도 가능합니다.
     *
     * ```sql
     * -- parentPost
     * INSERT INTO board ("name")
     * VALUES ('Parent Board');
     *
     * INSERT INTO categories (title, "uniqueId")
     * VALUES ('Parent Category', '2yyu0z4CdpfeOMoZ3u4AK');
     *
     * INSERT INTO posts (board_id, "category_uniqueId")
     * VALUES (1, '2yyu0z4CdpfeOMoZ3u4AK');
     *
     * -- post
     * INSERT INTO categories (title, "uniqueId")
     * VALUES ('Child Category', '2yyu0z4OWH47S1fx3U3Bk');
     *
     * INSERT INTO posts (parent_id, "category_uniqueId", "optCategory_uniqueId")
     * VALUES (1, '2yyu0z4OWH47S1fx3U3Bk', '2yyu0z4CdpfeOMoZ3u4AK');
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `nested entity initialization`(testDB: TestDB) {
        withTables(testDB, Posts, Categories, Boards) {
            val parentPost = Post.new {
                board = Board.new { name = "Parent Board" }
                category = Category.new { title = "Parent Category" }
            }
            val post = Post.new {
                parent = parentPost
                category = Category.new { title = "Child Category" }
                optCategory = parent!!.category
            }

            post.parent?.board?.name shouldBeEqualTo "Parent Board"
            post.parent?.category?.title shouldBeEqualTo "Parent Category"
            post.optCategory?.title shouldBeEqualTo "Parent Category"
            post.category?.title shouldBeEqualTo "Child Category"
        }
    }

    /**
     * 명시적으로 엔티티를 생성하는 방식
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `explicit entity constructor`(testDB: TestDB) {
        var createBoardCalled = false

        fun createBoard(id: EntityID<Int>): Board {
            createBoardCalled = true
            return Board(id)
        }

        /**
         * [IntEntityClass]를 상속받는 Object를 생성합니다.
         */
        val boardEntityClass = object: IntEntityClass<Board>(Boards, entityCtor = ::createBoard) {}

        withTables(testDB, Boards) {
            val board = boardEntityClass.new { name = "Test Board" }
            board.name shouldBeEqualTo "Test Board"

            createBoardCalled.shouldBeTrue()
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS requests (
     *      "requestId" VARCHAR(255) PRIMARY KEY
     * )
     * ```
     */
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

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS creditcards (
     *      id SERIAL PRIMARY KEY,
     *      "number" VARCHAR(16) NOT NULL,
     *      "spendingLimit" BIGINT NOT NULL
     * )
     * ```
     */
    object CreditCards: IntIdTable("CreditCards") {
        val number = varchar("number", 16)
        val spendingLimit = ulong("spendingLimit").databaseGenerated()
    }

    class CreditCard(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<CreditCard>(CreditCards)

        var number by CreditCards.number
        var spendingLimit by CreditCards.spendingLimit

        override fun equals(other: Any?): Boolean = other is CreditCard && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "CreditCard(id=$idValue, number=$number, spendingLimit=$spendingLimit)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `database generated value`(testDB: TestDB) {
        withTables(testDB, CreditCards) {
            when (testDB) {
                POSTGRESQL -> {
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

                else -> {
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
             * INSERT INTO creditcards ("number") VALUES ('0000111122223333')
             * ```
             */
            val creditCardId = CreditCards.insertAndGetId {
                it[number] = "0000111122223333"
            }.value

            /**
             * ```sql
             * SELECT creditcards.id, creditcards."number", creditcards."spendingLimit"
             *   FROM creditcards
             *  WHERE creditcards.id = 1
             * ```
             */
            CreditCards.selectAll()
                .where { CreditCards.id eq creditCardId }
                .single()[CreditCards.spendingLimit] shouldBeEqualTo 10000uL

            val creditCard = CreditCard.new {
                number = "0000111122223333"
            }.apply {
                flush()
            }
            creditCard.spendingLimit shouldBeEqualTo 10000uL
        }
    }

    /**
     * EntityID 를 Parameter 로 사용하기
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `use entityId parameters`(testDB: TestDB) {
        withTables(testDB, CreditCards) {
            val newCard = CreditCard.new {
                number = "0000111122223333"
                spendingLimit = 10000uL
            }

            val conditionalId: ExpressionWithColumnType<EntityID<Int>> = Case()
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
            CreditCards.select(conditionalId).single()[conditionalId] shouldBeEqualTo newCard.id

            /**
             * EntityID를 parameter 로 사용하기 위해 `idParam` 을 사용한다.
             * ```sql
             * SELECT creditcards."spendingLimit"
             *   FROM creditcards
             *  WHERE creditcards.id = 1
             * ```
             */
            CreditCards
                .select(CreditCards.spendingLimit)
                .where { CreditCards.id eq idParam(newCard.id, CreditCards.id) }
                .single()[CreditCards.spendingLimit] shouldBeEqualTo 10000uL

            /**
             * idParam 을 사용하지 않아도 된다.
             */
            CreditCards
                .select(CreditCards.spendingLimit)
                .where { CreditCards.id eq newCard.id }
                .single()[CreditCards.spendingLimit] shouldBeEqualTo 10000uL
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS countries (
     *      id VARCHAR(3) NOT NULL,
     *      "name" TEXT NOT NULL
     * );
     *
     * ALTER TABLE countries ADD CONSTRAINT countries_id_unique UNIQUE (id);
     * ```
     */
    object Countries: IdTable<String>("Countries") {
        override val id = varchar("id", 3).uniqueIndex().entityId()
        val name = text("name")
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS dishes (
     *      id SERIAL PRIMARY KEY,
     *      "name" TEXT NOT NULL,
     *      country_id VARCHAR(3) NOT NULL,
     *
     *      CONSTRAINT fk_dishes_country_id__id FOREIGN KEY (country_id) REFERENCES countries(id)
     *              ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     * ```
     */
    object Dishes: IntIdTable("Dishes") {
        val name = text("name")
        val country = reference("country_id", Countries)
    }

    class Country(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, Country>(Countries)

        var name by Countries.name
        val dishes by Dish referrersOn Dishes.country  // one-to-many

        override fun equals(other: Any?): Boolean = other is Country && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Country(id=$idValue, name=$name)"
    }


    class Dish(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Dish>(Dishes)

        var name by Dishes.name
        var country by Country referencedOn Dishes.country  // many-to-one

        override fun equals(other: Any?): Boolean = other is Dish && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Dish(id=$idValue, name=$name)"
    }

    /**
     * one-to-many 관계에서 eager loading을 테스트한다.
     */
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

            entityCache.clear()
            debug = true

            /**
             * Eager loading with parent id
             *
             * ```sql
             * SELECT countries.id, countries."name" FROM countries
             * ```
             * ```sql
             * SELECT dishes.id,
             *        dishes."name",
             *        dishes.country_id
             *   FROM dishes
             *  WHERE dishes.country_id = 'KOR'
             * ```
             */
            Country.all().with(Country::dishes)  // fetch eager loading

            statementStats
                .filterKeys { it.startsWith("SELECT ") }
                .forEach { (_, stats) ->
                    val (count, _) = stats
                    count shouldBeEqualTo 1       // dishes 테이블을 조회하는 쿼리는 1번만 실행되어야 한다.
                }

            debug = false
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS customers (
     *      id SERIAL PRIMARY KEY,
     *      "emailAddress" VARCHAR(30) NOT NULL,
     *      "fullName" TEXT NOT NULL
     * );
     *
     * ALTER TABLE customers
     *      ADD CONSTRAINT customers_emailaddress_unique UNIQUE ("emailAddress")
     * ```
     */
    object Customers: IntIdTable("Customers") {
        val emailAddress = varchar("emailAddress", 30).uniqueIndex()
        val fullName = text("fullName")
    }

    class Customer(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Customer>(Customers)

        var emailAddress by Customers.emailAddress
        var name by Customers.fullName
        val orders by Order referrersOn Orders.customer

        override fun equals(other: Any?): Boolean = other is Customer && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Customer(id=$idValue, emailAddress=$emailAddress, name=$name)"
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS orders (
     *      id SERIAL PRIMARY KEY,
     *      "orderName" TEXT NOT NULL,
     *      customer_id INT NOT NULL,
     *
     *      CONSTRAINT fk_orders_customer_id__id FOREIGN KEY (customer_id) REFERENCES customers(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     * ```
     */
    object Orders: IntIdTable("Orders") {
        val orderName = text("orderName")
        val customer = reference("customer_id", Customers)
    }

    class Order(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Order>(Orders)

        var name by Orders.orderName
        var customer by Customer referencedOn Orders.customer

        override fun equals(other: Any?): Boolean = other is Order && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Order(id=$idValue, name=$name)"
    }

    /**
     * `with(Customer::orders)` 를 사용하여  one-to-many 관계에서 eager loading을 테스트한다.
     *
     * ```sql
     * -- load customer
     * SELECT customers.id, customers."emailAddress", customers."fullName"
     *   FROM customers;
     *
     * -- load orders of customer
     * SELECT orders.id, orders."orderName", orders.customer_id
     *   FROM orders
     *  WHERE orders.customer_id IN (1, 2);
     * ```
     */
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

            Customer.all().with(Customer::orders)

            val cache = TransactionManager.current().entityCache

            cache.getReferrers<Order>(customer1.id, Orders.customer)
                ?.toList().orEmpty() shouldBeEqualTo listOf(order1, order2)

            cache.getReferrers<Order>(customer2.id, Orders.customer)
                ?.toList().orEmpty() shouldBeEqualTo listOf(order3)
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS testtable (
     *      id SERIAL PRIMARY KEY,
     *      "value" INT NOT NULL
     * );
     * ```
     */
    object TestTable: IntIdTable("TestTable") {
        val value = integer("value")
    }

    /**
     * [TestTable] 에 매핑됩니다.
     */
    class TestEntityA(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TestEntityA>(TestTable)

        var value by TestTable.value

        override fun equals(other: Any?): Boolean = other is TestEntityA && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "TestEntityA(id=$idValue, value=$value)"
    }

    /**
     * [TestTable] 에 매핑됩니다.
     */
    class TestEntityB(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TestEntityB>(TestTable)

        var value by TestTable.value

        override fun equals(other: Any?): Boolean = other is TestEntityB && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "TestEntityB(id=$idValue, value=$value)"
    }

    /**
     * 두 개의 엔티티가 동일한 테이블에 매핑되는 경우,
     * 서로 작동은 할 수 있으나, entityCache 에서 캐시되는 엔티티의 타입이 변경되어 문제가 발생할 수 있다.
     */
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

            entityCache.clear()


            // SELECT testtable.id, testtable."value" FROM testtable;
            TestTable.selectAll().toList().map { it[TestTable.value] } shouldBeEqualTo listOf(1, 2)

            // UPDATE testtable SET "value"=3 WHERE id = 1
            // UPDATE testtable SET "value"=4 WHERE id = 2
            entityA.value = 3
            entityB.value = 4

            entityCache.clear()

            // SELECT testtable.id, testtable."value" FROM testtable;
            TestTable.selectAll().toList().map { it[TestTable.value] } shouldBeEqualTo listOf(3, 4)

            entityCache.clear()

            // 같은 테이블을 바라보므로 두 엔티티 모두 변경된 값을 가져올 수 있다
            // 단 entityCache 에 이미 Entity 가 존제한다면, `TestEntityA` 와 `TestEntityB` 간의 Cast 가 실패하는 예외가 발생한다.
            assertFailsWith<ClassCastException> {
                TestEntityA.all().map { it.value } shouldBeEqualTo listOf(3, 4)
                TestEntityB.all().map { it.value } shouldBeEqualTo listOf(3, 4)  // 이미 TestEntityA 로 캐시되어 있어서 실패
            }
        }
    }
}
