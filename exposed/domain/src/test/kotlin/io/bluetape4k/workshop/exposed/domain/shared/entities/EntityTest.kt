package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.id.SnowflakeIdTable
import io.bluetape4k.exposed.sql.timebasedGenerated
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
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
import org.jetbrains.exposed.dao.exceptions.EntityNotFoundException
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.inTopLevelTransaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
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

class EntityTest: AbstractExposedTest() {

    companion object: KLogging()

    @Test
    fun `defaults 01`() {
        withTables(EntityTestData.YTable, EntityTestData.XTable) {
            val x = EntityTestData.XEntity.new { }
            x.b1.shouldBeTrue()
            x.b2.shouldBeFalse()
        }
    }

    @Test
    fun `defaults 02`() {
        withTables(EntityTestData.YTable, EntityTestData.XTable) {
            val a = EntityTestData.AEntity.create(false, EntityTestData.XType.A)
            val b = EntityTestData.AEntity.create(false, EntityTestData.XType.B) as EntityTestData.BEntity
            val y = EntityTestData.YEntity.new { x = false }

            a.b1.shouldBeFalse()
            b.b1.shouldBeFalse()
            b.b2.shouldBeFalse()

            b.y = y

            b.y!!.x.shouldBeFalse()
            y.b.shouldNotBeNull()
        }
    }

    @Test
    fun `text field outside the transaction`() {
        val objectsToVerify = arrayListOf<Pair<Human, TestDB>>()

        withTables(Humans) { testDb ->
            val y1 = Human.new { h = "foo" }

            flushCache()
            y1.refresh(flush = false)

            objectsToVerify.add(y1 to testDb)
        }

        objectsToVerify.forEach { (human, testDb) ->
            log.debug { "Verifying $human in $testDb" }
            human.h shouldBeEqualTo "foo"
        }
    }

    @Test
    fun `new with id and refresh`() {
        val objectsToVerify = arrayListOf<Pair<Human, TestDB>>()

        withTables(Humans) { testDb ->
            val x = Human.new(2) { h = "foo" }
            x.refresh(flush = true)
            objectsToVerify.add(x to testDb)
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

    @Test
    fun `one field entity`() {
        withTables(OneAutoFieldTable) {
            val entity = SingleFieldEntity.new { }
            commit()
            entity.id.value shouldBeGreaterThan 0
        }
    }

    @Test
    fun `back reference 01`() {
        withTables(EntityTestData.YTable, EntityTestData.XTable) {
            val y = EntityTestData.YEntity.new { }
            flushCache()

            val b = EntityTestData.BEntity.new { }
            b.y = y

            y.b shouldBeEqualTo b
        }
    }

    @Test
    fun `back reference 02`() {
        withTables(EntityTestData.YTable, EntityTestData.XTable) {
            val b = EntityTestData.BEntity.new { }
            flushCache()

            val y = EntityTestData.YEntity.new { }
            b.y = y

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

    @Test
    fun `self reference table`() {
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

    @Test
    fun `insert child without flush`() {
        withTables(Boards, Posts, Categories) {
            val parent = Post.new { this.category = Category.new { title = "title" } }
            Post.new { this.parent = parent }

            Post.all().count() shouldBeEqualTo 2L

            // 이 것도 되네 ㅋㅋ (Hibernate는 안됨)
            parent.children.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `insert non child without flush`() {
        withTables(Boards, Posts, Categories) {
            val board = Board.new { name = "ireelevant" }
            Post.new { this.board = board }  // first flush before referencing

            flushCache().size shouldBeEqualTo 1
        }
    }

    @Test
    fun `queries within other query iterator works fine`() {
        withTables(Boards, Posts, Categories) {
            val board1 = Board.new { name = "irrelevant" }
            val board2 = Board.new { name = "relavant" }
            val post1 = Post.new { board = board1 }

            Board.all().forEach { board ->
                board.posts.count() to board.posts.toList()
                val text = Post.find { Posts.board eq board.id }.joinToString { post ->
                    post.board?.name.orEmpty()
                }
                log.debug { "text: $text" }
            }
        }
    }

    @Test
    fun `insert child with flush`() {
        withTables(Boards, Posts, Categories) {
            val parent = Post.new { this.category = Category.new { title = "title" } }
            flushCache()
            parent.id._value.shouldNotBeNull()

            Post.new { this.parent = parent }

            flushCache().size shouldBeEqualTo 1

            Post.all().count() shouldBeEqualTo 2L
            parent.children.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `insert child with child`() {
        withTables(Boards, Posts, Categories) {
            val parent = Post.new { this.category = Category.new { title = "title1" } }
            val child1 = Post.new {
                this.parent = parent
                this.category = Category.new { title = "title2" }
            }
            Post.new { this.parent = child1 }
            flushCache()

            Post.all().count() shouldBeEqualTo 3L
            parent.children.count() shouldBeEqualTo 1L
            child1.children.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `optional referrers with different keys`() {
        withTables(Boards, Posts, Categories) {
            val board = Board.new { name = "irrelevant" }
            val post1 = Post.new {
                this.board = board
                this.category = Category.new { title = "title" }
            }
            board.posts.count() shouldBeEqualTo 1L
            board.posts.single() shouldBeEqualTo post1

            Post.new { this.board = board }
            board.posts.count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `error on set to deleted entity`() {
        withTables(Boards) {
            assertFailsWith<EntityNotFoundException> {
                val board = Board.new { name = "irrelevant" }
                board.delete()
                board.name = "new name"
            }
        }
    }

    @Test
    fun `cache invalidated on DSL delete`() {
        withTables(Boards) {
            val board1 = Board.new { name = "irrelevant" }
            Board.testCache(board1.id).shouldNotBeNull()
            board1.delete()
            Board.testCache(board1.id).shouldBeNull()

            val board2 = Board.new { name = "irrelevant" }
            Board.testCache(board2.id).shouldNotBeNull()

            // DSL Delete 를 수행하면 Cache가 지워진다.
            Boards.deleteWhere { Boards.id eq board2.id }
            Board.testCache(board2.id).shouldBeNull()
        }
    }

    @Test
    fun `cache invalidated on DSL update`() {
        withTables(Boards) {
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

    @Test
    fun `cache invalidated on DSL upsert`() {
        withTables(Items) {
            val oldPrice = 20.0.toBigDecimal()
            val itemA = Item.new {
                name = "itemA"
                price = oldPrice
            }
            itemA.price shouldBeEqualTo oldPrice
            Item.testCache(itemA.id).shouldNotBeNull()
        }
    }

    @Test
    fun `Dao findByIdAndUpdate`() {
        withTables(Items) {
            val oldPrice = 20.0.toBigDecimal()
            val item = Item.new {
                name = "Item A"
                price = oldPrice
            }
            item.price shouldBeEqualTo oldPrice
            Item.testCache(item.id).shouldNotBeNull()

            val newPrice = 50.0.toBigDecimal()
            val updatedItem = Item.findByIdAndUpdate(item.id.value) {
                it.price = newPrice
            }

            updatedItem shouldBeEqualTo item

            updatedItem.shouldNotBeNull()
            updatedItem.price shouldBeEqualTo newPrice
            Item.testCache(item.id).shouldNotBeNull()

            item.price shouldBeEqualTo newPrice
            // NOTE: refresh(flush=false)이면 Cache 값으로 다시 채워진다.
            item.refresh(flush = false)
            item.price shouldBeEqualTo oldPrice
            Item.testCache(item.id).shouldNotBeNull()

            updatedItem shouldBeEqualTo item
            Item.testCache(updatedItem.id).shouldNotBeNull()
        }
    }

    @Test
    fun `Dao findSingleByAndUpdate`() {
        withTables(Items) {
            val oldPrice = 20.0.toBigDecimal()
            val item = Item.new {
                name = "Item A"
                price = oldPrice
            }
            item.price shouldBeEqualTo oldPrice
            Item.testCache(item.id).shouldNotBeNull()

            val newPrice = 50.0.toBigDecimal()
            val updatedItem = Item.findSingleByAndUpdate(Items.name eq "Item A") {
                it.price = newPrice
            }

            updatedItem shouldBeEqualTo item

            updatedItem.shouldNotBeNull()
            updatedItem.price shouldBeEqualTo newPrice
            Item.testCache(item.id).shouldNotBeNull()

            item.price shouldBeEqualTo newPrice
            // NOTE: refresh(flush=false)이면 Cache 값으로 다시 채워진다.
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

    @Test
    fun `one-to-one reference`() {
        withTables(Humans, Users) {
            repeat(3) {
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

    @Test
    fun `hierarchy entity`() {
        withTables(SelfReferenceTable) {
            val parent = SelfReferenceEntity.new { }
            val child1 = SelfReferenceEntity.new { this.parent = parent.id }
            val child2 = SelfReferenceEntity.new { this.parent = parent.id }
            val grandChild = SelfReferenceEntity.new { this.parent = child1.id }

            parent.children.count() shouldBeEqualTo 2
            child1.children.count() shouldBeEqualTo 1
            child2.children.count() shouldBeEqualTo 0
            grandChild.children.count() shouldBeEqualTo 0
        }
    }

    @Test
    fun `self references`() {
        withTables(SelfReferenceTable) {
            repeat(5) { SelfReferenceEntity.new { } }

            val ref1 = SelfReferenceEntity.new { }
            ref1.parent = ref1.id

            val refRow = SelfReferenceTable.selectAll()
                .where { SelfReferenceTable.id eq ref1.id }
                .single()

            refRow[SelfReferenceTable.parentId]!!.value shouldBeEqualTo ref1.id._value
        }
    }

    @Test
    fun `non entityId reference`() {
        withTables(Posts, Boards, Categories) {
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
            category1.posts.count() shouldBeEqualTo 2L
            Posts.selectAll()
                .where { Posts.optCategory eq category1.uniqueId }
                .count() shouldBeEqualTo 2L

            Post.find { Posts.optCategory eq category1.uniqueId }
                .count() shouldBeEqualTo 2
        }
    }

    @Test
    fun `call limit on relation doesnt mutate the cached value`() {
        withTables(Posts, Boards, Categories) {
            val category1 = Category.new { title = "category1" }

            Post.new {
                optCategory = category1
                category = Category.new { title = "title" }
            }

            Post.new {
                optCategory = category1
            }

            commit()

            category1.posts.count() shouldBeEqualTo 2L
            category1.posts.toList() shouldHaveSize 2
            category1.posts.limit(1).toList() shouldHaveSize 1
            category1.posts.limit(1).count() shouldBeEqualTo 1L
            category1.posts.count() shouldBeEqualTo 2L
            category1.posts.toList() shouldHaveSize 2
        }
    }

    @Test
    fun `order by on entities`() {
        withTables(Categories) {
            Categories.deleteAll()

            val category1 = Category.new { title = "Test1" }
            val category3 = Category.new { title = "Test3" }
            val category2 = Category.new { title = "Test2" }

            Category.all().toList() shouldBeEqualTo listOf(category1, category3, category2)
            Category.all()
                .orderBy(Categories.title to SortOrder.ASC)
                .toList() shouldBeEqualTo listOf(category1, category2, category3)

            Category.all()
                .orderBy(Categories.title to SortOrder.DESC)
                .toList() shouldBeEqualTo listOf(category3, category2, category1)
        }
    }

    @Test
    fun `update of inserted entities goes before an insert`() {
        withTables(Categories, Posts, Boards) {
            val category1 = Category.new { title = "category1" }
            val category2 = Category.new { title = "category2" }

            val post1 = Post.new { category = category1 }

            flushCache()
            post1.category shouldBeEqualTo category1

            post1.category = category2

            val post2 = Post.new { category = category1 }

            flushCache()
            Post.reload(post1)
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

    @Test
    fun `new id with get`() {
        withTables(Parents, Children) {
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

    @Test
    fun `newly created entity flushed successfully`() {
        withTables(Boards) {
            val board = Board.new { name = "Board1" }.apply {
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

    @Test
    fun `shareing entity between transaction`() {
        withTables(Humans) {
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
}
