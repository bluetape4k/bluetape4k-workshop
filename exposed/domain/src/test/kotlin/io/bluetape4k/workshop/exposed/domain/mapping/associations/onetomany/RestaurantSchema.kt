package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object ResturantSchema {

    object RestaurantTable: IntIdTable("restaurant") {
        val name = varchar("name", 255)
    }

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
