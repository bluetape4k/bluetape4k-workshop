package io.bluetape4k.workshop.kotlin.designPatterns.singleton

/**
 * Enum 기반의 Singleton 인스턴스를 생성합니다.
 *
 * 참고: Effective Java 2nd Edition (Joshua Bloch) p. 18
 *
 * 이 구현은 스레드 안전합니다. 하지만 다른 메서드를 추가하고 그 메서드의 스레드 안전성은 개발자의 책임입니다.
 */
enum class EnumIvoryTower {

    INSTANCE;

    override fun toString(): String = javaClass.canonicalName + "@" + hashCode()

}
