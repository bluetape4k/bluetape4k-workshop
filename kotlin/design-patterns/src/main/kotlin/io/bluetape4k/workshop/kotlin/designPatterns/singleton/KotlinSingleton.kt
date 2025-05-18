package io.bluetape4k.workshop.kotlin.designPatterns.singleton

/**
 * Kotlin lazy delegator를 이용하여 singleton 을 생성합니다.
 */
class KotlinSingleton private constructor() {

    companion object {
        @JvmStatic
        val INSTANCE: KotlinSingleton by lazy { KotlinSingleton() }
    }
}
