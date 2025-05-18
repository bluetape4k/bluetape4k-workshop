package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading

import io.bluetape4k.logging.KLogging

/**
 * Kotlin 의 `lazy` 를 사용하여 지연 생성을 수행
 */
internal class HolderKotlinLazy {

    companion object: KLogging()

    private val _heavy: Heavy by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Heavy() }

    fun getHeavy(): Heavy = _heavy
}
