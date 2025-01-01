package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.id.SnowflakeIdTable
import io.bluetape4k.exposed.sql.timebasedGenerated
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
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
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.idParam
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.inTopLevelTransaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
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
    @Test
    fun `preload references on a sized iterable`() {
        withTables(Regions, Schools) {
            val region1 = Region.new { name = "United Kingdom" }
            val region2 = Region.new { name = "England" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val school2 = School.new { name = "Harrow"; region = region1 }
            val school3 = School.new { name = "Winchester"; region = region2 }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
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

    @Test
    fun `iteration over sized iterable with preload`() {
        fun HashMap<String, Pair<Int, Long>>.assertEachQueryExecutedOnlyOnce() {
            forEach { (_, stats) ->
                val executionCount = stats.first
                executionCount shouldBeEqualTo 1
            }
        }

        withTables(Regions, Schools) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val school2 = School.new { name = "Harrow"; region = region1 }

            commit()

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                debug = true

                val allSchools = School.all().with(School::region).toList()

                allSchools shouldHaveSize 2

                // 기대: 모든 School을 선택하는 데 1개의 쿼리, 참조된 Region을 선택하는 데 1개의 쿼리
                statementCount shouldBeEqualTo 2
                statementStats.size shouldBeEqualTo statementCount
                statementStats.assertEachQueryExecutedOnlyOnce()

                // reset tracker
                statementCount = 0
                statementStats.clear()

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

    @Test
    fun `preload optional refrences on an entity`() {
        withTables(Regions, Schools) {
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
                val school2 = School.find {
                    Schools.id eq school1.id
                }.first().load(School::secondaryRegion)

                Region.testCache(school2.readValues[Schools.region]).shouldBeNull()
                // load 를 통해 secondaryRegion을 로드한다.
                Region.testCache(school2.readValues[Schools.secondaryRegion]!!) shouldBeEqualTo region2
            }
        }
    }

    @Test
    fun `preload referrers on a sized iterable`() {
        withTables(Regions, Schools, Students) {
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

    @Test
    fun `preload referrers on a entity`() {
        withTables(Regions, Schools, Students) {
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

    @Test
    fun `preload optional referrers on a sized iterable`() {
        withTables(Regions, Schools, Students, Detentions) {
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

    @Test
    fun `preload inner table link on a sized iterable`() {
        withTables(Regions, Schools, Holidays, SchoolHolidays) {
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

    @Test
    fun `preload inner table link on a entity`() {
        withTables(Regions, Schools, Holidays, SchoolHolidays) {
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

    @Test
    fun `preload relation at depth`() {
        withTables(Regions, Schools, Holidays, SchoolHolidays, Students, Notes) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school1 }
            val note1 = Note.new { text = "Note text"; student = student1 }
            val note2 = Note.new { text = "Note text"; student = student2 }

            commit()

            School.all().with(School::students, Student::notes)

            val cache = TransactionManager.current().entityCache

            cache.getReferrers<Student>(school1.id, Students.school)?.toList().orEmpty() shouldBeEqualTo
                    listOf(student1, student2)
            cache.getReferrers<Note>(student1.id, Notes.student)?.single() shouldBeEqualTo note1
            cache.getReferrers<Note>(student2.id, Notes.student)?.single() shouldBeEqualTo note2
        }
    }

    @Test
    fun `preload back referrence on a sized iterable`() {
        withTables(Regions, Schools, Students, StudentBios) {
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

    @Test
    fun `preload back referrence on a entity`() {
        withTables(Regions, Schools, Students, StudentBios) {
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

    @Test
    fun `reference cache doesn't fully invalidated on set entity reference`() {
        withTables(Regions, Schools, Students, StudentBios) {
            val region1 = Region.new { name = "United Kingdom" }
            val school1 = School.new { name = "Eton"; region = region1 }
            val student1 = Student.new { name = "James Smith"; school = school1 }
            val student2 = Student.new { name = "Jack Smith"; school = school1 }
            val bio1 = StudentBio.new { dateOfBirth = "2000-01-01"; student = student1 }

            student1.bio shouldBeEqualTo bio1
            bio1.student shouldBeEqualTo student1
        }
    }

    @Test
    fun `nested entity initialization`() {
        withTables(Posts, Categories, Boards) {
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

    @Test
    fun `explicit entity constructor`() {
        var createBoardCalled = false
        fun createBoard(id: EntityID<Int>): Board {
            createBoardCalled = true
            return Board(id)
        }

        val boardEntityClass = object: IntEntityClass<Board>(Boards, entityCtor = ::createBoard) {}

        withTables(Boards) {
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

    @Test
    fun `select from string id table with primary key by column`() {
        withTables(RequestsTable) {
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

    @Test
    fun `database generated value`() {
        withTables(CreditCards) { testDb ->
            when (testDb) {
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

            val creditCardId = CreditCards.insertAndGetId {
                it[number] = "0000111122223333"
            }.value

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

    @Test
    fun `use entityId parameters`() {
        withTables(CreditCards) {
            val newCard = CreditCard.new {
                number = "0000111122223333"
                spendingLimit = 10000uL
            }

            val conditionalId = Case()
                .When(CreditCards.spendingLimit less 500uL, CreditCards.id)
                .Else(idParam(newCard.id, CreditCards.id))

            CreditCards.select(conditionalId)
                .single()[conditionalId] shouldBeEqualTo newCard.id

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

    @Test
    fun `eager loading with string parent id`() {

        withTables(Countries, Dishes, configure = { keepLoadedReferencesOutOfTransaction = true }) {
            val koreaId = Countries.insertAndGetId {
                it[id] = "KOR"
                it[name] = "Korea"
            }.value
            val korea = Country.findById(koreaId)!!

            Dish.new {
                name = "Kimchi"
                country = korea
            }
            Dish.new {
                name = "Bibimbap"
                country = korea
            }
            Dish.new {
                name = "Bulgogi"
                country = korea
            }

            debug = true

            // SELECT countries.id, countries."name" FROM countries
            // INSERT INTO dishes ("name", country_id) VALUES ('Kimchi', 'KOR')
            // INSERT INTO dishes ("name", country_id) VALUES ('Bibimbap', 'KOR')
            // INSERT INTO dishes ("name", country_id) VALUES ('Bulgogi', 'KOR')
            // SELECT dishes.id, dishes."name", dishes.country_id FROM dishes WHERE dishes.country_id = 'KOR'
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

    @Test
    fun `eager loading with reference different from parent id`() {
        withTables(Customers, Orders, configure = { keepLoadedReferencesOutOfTransaction = true }) {
            val customer1 = Customer.new {
                emailAddress = "customer1@testing.com"
                name = "Customer1"
            }
            val order1 = Order.new {
                name = "Order1"
                customer = customer1
            }
            val order2 = Order.new {
                name = "Order2"
                customer = customer1
            }

            // SELECT customers.id, customers."emailAddress", customers."fullName" FROM customers
            // INSERT INTO orders ("orderName", customer_id) VALUES ('Order1', 1)
            // INSERT INTO orders ("orderName", customer_id) VALUES ('Order2', 1)
            // SELECT orders.id, orders."orderName", orders.customer_id FROM orders WHERE orders.customer_id = 1
            Customer.all().with(Customer::orders)

            val cache = TransactionManager.current().entityCache

            cache.getReferrers<Order>(customer1.id, Orders.customer)
                ?.toList().orEmpty() shouldBeEqualTo listOf(order1, order2)
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

    @Test
    fun `different entities mapped to the same table`() {
        withTables(TestTable) {
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
