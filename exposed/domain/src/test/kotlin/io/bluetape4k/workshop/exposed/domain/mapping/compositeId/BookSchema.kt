package io.bluetape4k.workshop.exposed.domain.mapping.compositeId

import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SizedIterable
import java.util.*

object BookSchema {
    val allTables = arrayOf(Publishers, Authors, Books, Reviews, Offices)

    /**
     * CompositeIdTable with 2 key columns - int & uuid (both db-generated)
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS PUBLISHERS (
     *      PUB_ID INT AUTO_INCREMENT,
     *      ISBN_CODE UUID,
     *      PUBLISHER_NAME VARCHAR(32) NOT NULL,
     *
     *      CONSTRAINT pk_publishers PRIMARY KEY (PUB_ID, ISBN_CODE)
     * )
     * ```
     * @see [Publisher]
     */
    object Publishers: CompositeIdTable("publishers") {
        val pubId = integer("pub_id").autoIncrement().entityId()
        val isbn = uuid("isbn_code").autoGenerate().entityId()
        val name = varchar("publisher_name", 32)

        override val primaryKey = PrimaryKey(pubId, isbn)
    }

    /**
     * [Publishers] 테이블을 참조하는 Author 테이블
     * ```sql
     * CREATE TABLE IF NOT EXISTS AUTHORS (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      PUBLISHER_ID INT NOT NULL,
     *      PUBLISHER_ISBN UUID NOT NULL,
     *      PEN_NAME VARCHAR(32) NOT NULL,
     *
     *      CONSTRAINT FK_AUTHORS_PUBLISHER_ID_PUBLISHER_ISBN__PUB_ID_ISBN_CODE
     *          FOREIGN KEY (PUBLISHER_ID, PUBLISHER_ISBN) REFERENCES PUBLISHERS(PUB_ID, ISBN_CODE)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Authors: IntIdTable("authors") {
        val publisherId = integer("publisher_id")
        val publisherIsbn = uuid("publisher_isbn")
        val penName = varchar("pen_name", 32)

        // FK constraint with multiple columns is created as a table-level constraint
        init {
            foreignKey(publisherId, publisherIsbn, target = Publishers.primaryKey)
        }
    }

    /**
     * CompositeIdTable with 1 key column - int (db-generated)
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS BOOKS (
     *      BOOK_ID INT AUTO_INCREMENT PRIMARY KEY,
     *      TITLE VARCHAR(32) NOT NULL,
     *      AUTHOR_ID INT NULL,
     *
     *      CONSTRAINT FK_BOOKS_AUTHOR_ID__ID
     *          FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS(ID)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Books: CompositeIdTable("books") {
        val bookId = integer("book_id").autoIncrement().entityId()
        val title = varchar("title", 32)
        val author = optReference("author_id", Authors)

        override val primaryKey = PrimaryKey(bookId)
    }

    /**
     * CompositeIdTable with 2 key columns - string & long (neither db-generated)
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS REVIEWS (
     *      CODE VARCHAR(8),
     *      "rank" BIGINT,
     *      BOOK_ID INT NOT NULL,
     *
     *      CONSTRAINT pk_reviews PRIMARY KEY (CODE, "rank"),
     *      CONSTRAINT FK_REVIEWS_BOOK_ID__BOOK_ID
     *          FOREIGN KEY (BOOK_ID) REFERENCES BOOKS(BOOK_ID)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Reviews: CompositeIdTable("reviews") {
        val content = varchar("code", 8).entityId()
        val rank = long("rank").entityId()
        val book = integer("book_id")

        override val primaryKey = PrimaryKey(content, rank)

        init {
            foreignKey(book, target = Books.primaryKey)
        }
    }

    /**
     * CompositeIdTable with 3 key columns - string, string, & int (none db-generated)
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS OFFICES (
     *      ZIP_CODE VARCHAR(8),
     *      "name" VARCHAR(64),
     *      AREA_CODE INT,
     *      STAFF BIGINT NULL,
     *      PUBLISHER_ID INT NULL,
     *      PUBLISHER_ISBN UUID NULL,
     *
     *      CONSTRAINT pk_offices PRIMARY KEY (ZIP_CODE, "name", AREA_CODE),
     *      CONSTRAINT FK_OFFICES_PUBLISHER_ID_PUBLISHER_ISBN__PUB_ID_ISBN_CODE
     *          FOREIGN KEY (PUBLISHER_ID, PUBLISHER_ISBN)
     *          REFERENCES PUBLISHERS(PUB_ID, ISBN_CODE)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Offices: CompositeIdTable("offices") {
        val zipCode = varchar("zip_code", 8).entityId()
        val name = varchar("name", 64).entityId()
        val areaCode = integer("area_code").entityId()
        val staff = long("staff").nullable()
        val publisherId = integer("publisher_id").nullable()
        val publisherIsbn = uuid("publisher_isbn").nullable()

        override val primaryKey = PrimaryKey(zipCode, name, areaCode)

        init {
            // Publishers 는 publisherId, publisherIsbn 두 컬럼으로 구성된 PK
            foreignKey(publisherId, publisherIsbn, target = Publishers.primaryKey)
        }
    }

    class Publisher(id: EntityID<CompositeID>): CompositeEntity(id) {
        companion object: CompositeEntityClass<Publisher>(Publishers) {
            fun new(isbn: UUID, init: Publisher.() -> Unit): Publisher {
                val compositeId = CompositeID {
                    it[Publishers.isbn] = isbn
                }
                return Publisher.new(compositeId) {
                    init()
                }
            }
        }

        var name: String by Publishers.name
        val authors: SizedIterable<Author> by Author referrersOn Authors
        val office: Office? by Office optionalBackReferencedOn Offices
        val allOffices: SizedIterable<Office> by Office optionalReferrersOn Offices

        override fun equals(other: Any?): Boolean =
            other is Publisher && other.id._value == id._value

        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String = "Publisher(id=${id._value}, name=$name)"
    }

    class Author(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Author>(Authors)

        var publisher by Publisher referencedOn Authors
        var penName by Authors.penName

        override fun equals(other: Any?): Boolean = other is Author && other.id._value == id._value
        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String = "Author(id=${id._value}, penName=$penName)"
    }

    class Book(id: EntityID<CompositeID>): CompositeEntity(id) {
        companion object: CompositeEntityClass<Book>(Books)

        var title by Books.title
        var author by Author optionalReferencedOn Books.author
        val review by Review backReferencedOn Reviews

        override fun equals(other: Any?): Boolean = other is Book && other.id._value == id._value
        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String = "Book(id=${id._value}, title=$title)"
    }

    class Review(id: EntityID<CompositeID>): CompositeEntity(id) {
        companion object: CompositeEntityClass<Review>(Reviews)

        var book by Book referencedOn Reviews

        override fun equals(other: Any?): Boolean = other is Review && other.id._value == id._value
        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String = "Review(id=${id._value}, book=${book.id._value})"
    }

    class Office(id: EntityID<CompositeID>): CompositeEntity(id) {
        companion object: CompositeEntityClass<Office>(Offices)

        var staff by Offices.staff
        var publisher by Publisher optionalReferencedOn Offices

        override fun equals(other: Any?): Boolean = other is Office && other.id._value == id._value
        override fun hashCode(): Int = id._value.hashCode()
        override fun toString(): String = "Office(id=${id._value}, staff=$staff)"
    }

}
