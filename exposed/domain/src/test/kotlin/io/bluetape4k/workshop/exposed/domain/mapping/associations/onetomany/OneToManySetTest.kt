package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.exposed.dao.id.KsuidEntity
import io.bluetape4k.exposed.dao.id.KsuidEntityClass
import io.bluetape4k.exposed.dao.id.KsuidTable
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.dao.idValue
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.Serializable
import java.util.*


class OneToManySetTest: AbstractExposedTest() {

    companion object: KLogging()

    object BiddingItemTable: TimebasedUUIDTable("bidding_items") {
        val name = varchar("name", 255)
    }

    object BidTable: TimebasedUUIDTable("bids") {
        val amount = decimal("amount", 10, 2).default(0.toBigDecimal())
        val itemId = reference("item_id", BiddingItemTable, onDelete = CASCADE, onUpdate = CASCADE)
    }

    class BiddingItem(id: EntityID<UUID>): TimebasedUUIDEntity(id) {
        companion object: TimebasedUUIDEntityClass<BiddingItem>(BiddingItemTable)

        var name by BiddingItemTable.name
        val bids by Bid referrersOn BidTable.itemId

        override fun equals(other: Any?): Boolean = other is BiddingItem && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String {
            return ToStringBuilder(this)
                .add("id", id._value)
                .add("name", name)
                .toString()
        }
    }

    class Bid(id: EntityID<UUID>): TimebasedUUIDEntity(id) {
        companion object: TimebasedUUIDEntityClass<Bid>(BidTable)

        var amount by BidTable.amount
        var item by BiddingItem referencedOn BidTable.itemId

        override fun equals(other: Any?): Boolean = other is Bid && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String {
            return ToStringBuilder(this)
                .add("id", id._value)
                .add("amount", amount)
                .toString()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many set with bidirectional`(testDB: TestDB) {
        withTables(testDB, BiddingItemTable, BidTable) {
            val item1 = BiddingItem.new { name = "TV" }
            val bid1 = Bid.new { amount = 100.toBigDecimal(); item = item1 }
            val bid2 = Bid.new { amount = 200.toBigDecimal(); item = item1 }
            val bid3 = Bid.new { amount = 300.toBigDecimal(); item = item1 }

            flushCache()
            entityCache.clear()

            val loaded = BiddingItem.findById(item1.id)!!
            loaded shouldBeEqualTo item1
            loaded.bids.toSet() shouldContainSame setOf(bid1, bid2, bid3)

            val bidToRemove = loaded.bids.first()
            bidToRemove.delete()

            entityCache.clear()

            val loaded2 = BiddingItem.findById(item1.id)!!
            loaded2 shouldBeEqualTo item1
            loaded2.bids.toSet() shouldContainSame setOf(bid1, bid2, bid3) - bidToRemove

            log.debug { "delete bidding item" }
            loaded2.delete()
            entityCache.clear()

            BiddingItem.all().count() shouldBeEqualTo 0L
            // onDelete = CASCADE로 인해 bids도 삭제된다.
            Bid.all().count() shouldBeEqualTo 0L
        }
    }

    object ProductTable: KsuidTable("products") {
        val name = varchar("name", 255)
        val description = text("description").nullable()
        val initialPrice = decimal("initial_price", 10, 2).nullable()
        val reservePrice = decimal("reserve_price", 10, 2).nullable()
        val startDate = date("start_date").nullable()
        val endDate = date("end_date").nullable()

        val status = enumerationByName("status", 10, ProductStatus::class).clientDefault { ProductStatus.ACTIVE }
    }

    object ProductImageTable: KsuidTable("product_images") {
        val name = varchar("name", 255)
        val filename = varchar("filename", 255).nullable()
        val sizeX = integer("size_x").nullable()
        val sizeY = integer("size_y").nullable()
        val productId: Column<EntityID<String>?> =
            optReference("product_id", ProductTable, onDelete = CASCADE, onUpdate = CASCADE)

        init {
            uniqueIndex("product_image_name_product_id", name, productId)
        }
    }

    class Product(id: EntityID<String>): KsuidEntity(id) {
        companion object: KsuidEntityClass<Product>(ProductTable)

        var name by ProductTable.name
        var description by ProductTable.description
        var initialPrice by ProductTable.initialPrice
        var reservePrice by ProductTable.reservePrice
        var startDate by ProductTable.startDate
        var endDate by ProductTable.endDate
        var status by ProductTable.status

        /**
         * JPA의 ElementCollection과 같은 역할을 수행한다. [ProductImage]는 Entity 가 아니라 Value Object 이다.
         * 단, 컬렉션의 요소를 조작하는 것은 `addImages`, `removeImages` 메소드를 통해서만 가능하다.
         *
         * [ProductImage] 를 얻고 가져온다.
         *
         * ```
         * SELECT PRODUCT_IMAGES.ID,
         *        PRODUCT_IMAGES."name",
         *        PRODUCT_IMAGES.FILENAME,
         *        PRODUCT_IMAGES.SIZE_X,
         *        PRODUCT_IMAGES.SIZE_Y,
         *        PRODUCT_IMAGES.PRODUCT_ID
         *   FROM PRODUCT_IMAGES
         *  WHERE PRODUCT_IMAGES.PRODUCT_ID = 'UIifYemgA0N7SFN5E5ZzXLcIuOu'
         * ```
         */
        val images: Set<ProductImage>
            get() = ProductImageTable.selectAll()
                .where { ProductImageTable.productId eq id }
                .map {
                    ProductImage(
                        name = it[ProductImageTable.name],
                        filename = it[ProductImageTable.filename],
                        sizeX = it[ProductImageTable.sizeX],
                        sizeY = it[ProductImageTable.sizeY]
                    )
                }
                .toSet()

        fun addImages(vararg imagesToAdd: ProductImage) {
            // 중복된 값이 들어가지 않도록 image name, productId 조합이 unique key로 설정되어 있다.
            ProductImageTable.batchInsert(imagesToAdd.asList(), true, false) { image ->
                this[ProductImageTable.name] = image.name
                this[ProductImageTable.filename] = image.filename
                this[ProductImageTable.sizeX] = image.sizeX
                this[ProductImageTable.sizeY] = image.sizeY
                this[ProductImageTable.productId] = this@Product.id
            }
        }

        fun removeImages(vararg imagesToRemove: ProductImage) {
            ProductImageTable.deleteWhere {
                ProductImageTable.productId eq this@Product.id and
                        (ProductImageTable.name inList imagesToRemove.map { it.name })
            }
        }

        override fun equals(other: Any?): Boolean = other is Product && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String {
            return ToStringBuilder(this)
                .add("id", id._value)
                .add("name", name)
                .add("status", status)
                .toString()
        }
    }

    /**
     * [ProductImage]는 Entity가 아니라 Value Object (Component) 이다.
     */
    data class ProductImage(
        val name: String,
        val filename: String? = null,
        val sizeX: Int? = null,
        val sizeY: Int? = null,
    ): Serializable

    enum class ProductStatus {
        UNKNOWN,
        ACTIVE,
        INACTIVE,
        SOLD_OUT
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many with embeddable component by @ElementCollection`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB != TestDB.H2 }   // H2 는 INSERT IGNORE 를 지원하지 않는다.

        withTables(testDB, ProductTable, ProductImageTable) {
            val product1 = Product.new { name = "Car" }
            val image1 = ProductImage("front")
            val image2 = ProductImage("interior")
            val image3 = ProductImage("engine room")

            product1.addImages(image1, image2, image3)

            entityCache.clear()

            val loaded = Product.findById(product1.id)!!
            log.debug { "loaded product: $loaded" }
            loaded.images shouldContainSame setOf(image1, image2, image3)

            val imageToRemove = image2
            loaded.removeImages(imageToRemove)

            entityCache.clear()

            val loaded2 = Product.findById(product1.id)!!
            loaded2 shouldBeEqualTo product1
            loaded2.images shouldContainSame setOf(image1, image3)

            /**
             * [Product]를 삭제하면 [ProductImage]도 삭제된다. (onDelete = CASCADE)
             *
             * ```sql
             * DELETE FROM PRODUCTS WHERE PRODUCTS.ID = 'UIyfyDIDLsuZyAnrUI8vndcqF7f'
             * ```
             */
            loaded2.delete()

            Product.all().count() shouldBeEqualTo 0L
            ProductImageTable.selectAll().count() shouldBeEqualTo 0L
        }
    }
}
