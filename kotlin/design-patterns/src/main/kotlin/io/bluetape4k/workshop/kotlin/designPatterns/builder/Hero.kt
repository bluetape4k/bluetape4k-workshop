package io.bluetape4k.workshop.kotlin.designPatterns.builder

internal class Hero private constructor(builder: Hero.Builder) {

    val profession = builder.profession
    val name = builder.name
    val hairType = builder.hairType
    val hairColor = builder.hairColor
    val armor = builder.armor
    val weapon = builder.weapon

    override fun toString(): String {
        return buildString {
            append("This is a ")
                .append(profession)
                .append(" named ")
                .append(name)

            if (hairColor != null || hairType != null) {
                append(" with ")
                hairColor?.run { append(this).append(' ') }
                hairType?.run { append(this).append(' ') }
            }

            armor?.run { append(" wearing ").append(this) }
            weapon?.run { append(" and wielding a ").append(this) }
            append('.')
        }
    }

    class Builder(val profession: Profession, val name: String) {

        var hairType: HairType? = null
        var hairColor: HairColor? = null
        var armor: Armor? = null
        var weapon: Weapon? = null

        fun withHairType(hairType: HairType) = apply {
            this.hairType = hairType
        }

        fun withHairColor(hairColor: HairColor) = apply {
            this.hairColor = hairColor
        }

        fun withArmor(armor: Armor) = apply {
            this.armor = armor
        }

        fun withWeapon(weapon: Weapon) = apply {
            this.weapon = weapon
        }

        fun build(): Hero {
            return Hero(this)
        }
    }
}
