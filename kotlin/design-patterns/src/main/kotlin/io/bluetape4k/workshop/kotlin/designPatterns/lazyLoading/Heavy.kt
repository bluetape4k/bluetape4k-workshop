package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info

/**
 * 생성에 많은 비용이 들어가는 클래스
 */
internal class Heavy {

    companion object: KLogging()

    init {
        log.info { "Creating Heavy ... " }

        Thread.sleep(1000)

        log.info { "... Heavy created" }
    }
}
