package io.bluetape4k.workshop.exposed.domain.shared

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Postgres:
 * ```sql
 * CREATE TABLE IF NOT EXISTS category (
 *      id INT PRIMARY KEY,
 *      "name" VARCHAR(20) NOT NULL
 * );
 * ```
 */
object Category: Table("Category") {
    val id = integer("id")
    val name = varchar("name", length = 20)

    override val primaryKey = PrimaryKey(id)
}

const val DEFAULT_CATEGORY_ID = 0

/**
 * Postgres:
 * ```sql
 * CREATE TABLE IF NOT EXISTS item (
 *      id INT PRIMARY KEY,
 *      "name" VARCHAR(20) NOT NULL,
 *      "categoryId" INT DEFAULT 0 NOT NULL,
 *
 *      CONSTRAINT fk_item_categoryid__id FOREIGN KEY ("categoryId")
 *      REFERENCES category(id) ON DELETE SET DEFAULT
 * )
 * ```
 */
object Item: Table("Item") {
    val id = integer("id")
    val name = varchar("name", length = 20)

    val categoryId = integer("categoryId")
        .default(DEFAULT_CATEGORY_ID)
        .references(
            Category.id,
            onDelete = ReferenceOption.SET_DEFAULT,
            onUpdate = ReferenceOption.NO_ACTION
        )

    override val primaryKey = PrimaryKey(id)
}
