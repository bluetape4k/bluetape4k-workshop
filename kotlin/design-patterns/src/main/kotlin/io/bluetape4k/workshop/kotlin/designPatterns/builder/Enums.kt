package io.bluetape4k.workshop.kotlin.designPatterns.builder

internal enum class Armor(val title: String) {

    CLOTHES("clothes"),
    LEATHER("leather"),
    CHAIN_MAIL("chain mail"),
    PLATE_MAIL("plate mail");

    override fun toString(): String = title
}

internal enum class HairColor {

    WHITE, BLOND, RED, BROWN, BLACK;

    override fun toString(): String = name.lowercase()
}

internal enum class HairType(val title: String) {

    BALD("bald"),
    SHORT("short"),
    CURLY("curly"),
    LONG_STRAIGHT("long straight"),
    LONG_CURLY("long curly");

    override fun toString(): String = title
}

internal enum class Profession {

    WARRIOR, THIEF, MAGE, PRIEST;

    override fun toString(): String = name.lowercase()
}

internal enum class Weapon {

    DAGGER, SWORD, AXE, WAR_HAMMER, BOW;

    override fun toString(): String = name.lowercase()
}
