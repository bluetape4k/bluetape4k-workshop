package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

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

        override fun equals(other: Any?): Boolean = other is Restaurant && other.id._value == id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)

        override fun toString(): String = "Restaurant(id=$id, name=$name)"
    }

    class Menu(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Menu>(MenuTable)

        var name by MenuTable.name
        var price by MenuTable.price

        // many-to-one relationship
        var restaurant by Restaurant referencedOn MenuTable.restaurant

        override fun equals(other: Any?): Boolean = other is Menu && other.id._value == id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "Menu(id=$id, name=$name, price=$price, restaurant=$restaurant)"
        }
    }

}
