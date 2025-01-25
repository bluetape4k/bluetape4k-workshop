package io.bluetape4k.workshop.exposed.domain.mapping.onetoone

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.load
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MapIdOneToOneTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS AUTHORS (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * )
     * ```
     *
     */
    object Authors: IntIdTable("authors") {
        val name = varchar("name", 255)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS PICTURES (
     *      AUTHOR_ID INT PRIMARY KEY,
     *      "path" VARCHAR(255) NOT NULL,
     *
     *      CONSTRAINT FK_PICTURES_ID__ID FOREIGN KEY (ID) REFERENCES AUTHORS(ID)
     *          ON DELETE CASCADE ON UPDATE CASCADE
     * )
     * ```
     */
    object Pictures: IdTable<Int>("pictures") {
        override val id = reference("author_id", Authors, onDelete = CASCADE, onUpdate = CASCADE)
        val path = varchar("path", 255)

        override val primaryKey = PrimaryKey(id)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS BIOGRAPHYS (
     *      AUTHOR_ID INT PRIMARY KEY,
     *      INFORMATION VARCHAR(255) NULL,
     *
     *      CONSTRAINT FK_BIOGRAPHYS_ID__ID FOREIGN KEY (ID) REFERENCES AUTHORS(ID)
     *          ON DELETE CASCADE ON UPDATE CASCADE
     * )
     * ```
     */
    object Biographys: IdTable<Int>("biographys") {
        // @MapsId 와 같다. (Authors.id 를 Id로 사용한다)
        override val id = reference("author_id", Authors, onDelete = CASCADE, onUpdate = CASCADE)
        val infomation = varchar("information", 255).nullable()

        override val primaryKey = PrimaryKey(id)
    }

    class Author(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Author>(Authors)

        var name by Authors.name
        val picture by Picture backReferencedOn Pictures
        val biography by Biography backReferencedOn Biographys

        override fun equals(other: Any?): Boolean = other is Author && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Author(id=$id, name=$name)"
    }

    class Picture(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Picture>(Pictures)

        var path by Pictures.path
        var author by Author referencedOn Pictures.id

        override fun equals(other: Any?): Boolean = other is Picture && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Picture(id=$id, path=$path)"
    }

    class Biography(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Biography>(Biographys)

        var infomation by Biographys.infomation
        var author by Author referencedOn Biographys.id

        override fun equals(other: Any?): Boolean = other is Biography && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Biography(id=$id, infomation=$infomation)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-one share main entity identifier`(testDB: TestDB) {
        withTables(testDB, Authors, Pictures, Biographys) {
            val author = Author.new {
                name = faker.name().name()
            }
            val picture = Picture.new {
                path = faker.internet().url()
                this.author = author
            }
            val biography = Biography.new {
                infomation = faker.name().fullName()
                this.author = author
            }

            entityCache.clear()

            val author2 = Author.findById(author.id.value)!!
            author2 shouldBeEqualTo author

            val biography2 = Biography.findById(author.id.value)!!
            biography2 shouldBeEqualTo biography
            biography2.author shouldBeEqualTo author

            val picture2 = Picture.findById(author.id.value)!!
            picture2 shouldBeEqualTo picture
            picture2.author shouldBeEqualTo author

            entityCache.clear()

            /**
             * eager loading
             *
             * ```sql
             * SELECT AUTHORS.ID, AUTHORS."name" FROM AUTHORS WHERE AUTHORS.ID = 1
             * SELECT BIOGRAPHYS.ID, BIOGRAPHYS.INFORMATION FROM BIOGRAPHYS WHERE BIOGRAPHYS.ID = 1
             * SELECT PICTURES.ID, PICTURES."path" FROM PICTURES WHERE PICTURES.ID = 1
             * ```
             */
            val author3 = Author.findById(author.id)!!.load(Author::picture, Author::biography)

            entityCache.clear()

            // Load by join
            val authors = Authors.innerJoin(Pictures).innerJoin(Biographys)
                .selectAll()
                .where { Authors.id eq author.id }
                .map { Author.wrapRow(it) }

            authors.forEach {
                log.debug { it }
            }
            authors shouldHaveSize 1
            val author4 = authors.first()
            author4 shouldBeEqualTo author
            author4.picture shouldBeEqualTo picture
            author4.biography shouldBeEqualTo biography

            // cascade delete (author -> biography, picture)
            author.delete()
            entityCache.clear()

            /**
             * ```sql
             * SELECT COUNT(AUTHORS.ID) FROM AUTHORS
             * ```
             */
            Author.count() shouldBeEqualTo 0L

            /**
             * Pictures, Biographys 는 author 가 삭제되면 같이 삭제된다.
             *
             * `Picture.count()` 는 자신만의 entity id 가 없으므로 실행할 수 없다. (SELECT COUNT(AUTHOR_ID) FROM PICTURES)
             *
             * ```sql
             * SELECT COUNT(*) FROM PICTURES
             * SELECT COUNT(*) FROM BIOGRAPHYS
             */
            Picture.all().count() shouldBeEqualTo 0L
            Biography.all().count() shouldBeEqualTo 0L
        }
    }
}
