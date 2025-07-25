package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object ResturantSchema {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS restaurant (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * );
     * ```
     */
    object RestaurantTable: IntIdTable("restaurant") {
        val name = varchar("name", 255)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS menu (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      price DECIMAL(10, 2) NOT NULL,
     *      restaurant_id INT NOT NULL,
     *
     *      CONSTRAINT fk_menu_restaurant_id__id FOREIGN KEY (restaurant_id)
     *      REFERENCES restaurant(id) ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     *
     * CREATE INDEX menu_restaurant_id ON menu (restaurant_id);
     * ```
     */
    object MenuTable: IntIdTable("menu") {
        val name = varchar("name", 255)
        val price = decimal("price", 10, 2)

        // reference to Restaurant
        val restaurant = reference("restaurant_id", RestaurantTable).index()
    }

    class Restaurant(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Restaurant>(RestaurantTable)

        var name by RestaurantTable.name

        // one-to-many relationship
        val menus by Menu referrersOn MenuTable.restaurant

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("name", name)
                .toString()
    }

    class Menu(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Menu>(MenuTable)

        var name by MenuTable.name
        var price by MenuTable.price

        // many-to-one relationship
        var restaurant by Restaurant referencedOn MenuTable.restaurant

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("name", name)
                .add("price", price)
                .add("restaurant id", restaurant.id._value)
                .toString()
    }

}
