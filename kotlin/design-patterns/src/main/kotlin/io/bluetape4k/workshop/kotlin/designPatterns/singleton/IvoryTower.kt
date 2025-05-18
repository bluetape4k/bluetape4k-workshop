package io.bluetape4k.workshop.kotlin.designPatterns.singleton

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug

/**
 * `IvoryTower` 는 외부에서 생성할 수 없고, `IvoryTower.getInstance()` 메서드를 통해서 Singleton 인스턴스만 얻을 수 있다.
 */
class IvoryTower private constructor() {

    companion object: KLogging() {

        private val INSTANCE: IvoryTower = IvoryTower().apply {
            log.debug { "Creating IvoryTower instance" }
        }

        @JvmStatic
        fun getInstance(): IvoryTower = INSTANCE
    }
}
