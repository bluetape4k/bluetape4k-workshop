package io.bluetape4k.workshop.kotlin.designPatterns.singleton

/**
 * Kotlin `object` 키워드를 사용하여 Singleton 인스턴스를 생성한다.
 */
object IvoryTowerObject {

    fun getInstance(): IvoryTowerObject = this
}
