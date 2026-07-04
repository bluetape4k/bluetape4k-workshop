package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * 생성에 많은 비용이 들어가는 클래스
 */
internal class Heavy {

    companion object: KLogging()

    init {
        log.info { "Creating Heavy ... " }

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1))

        log.info { "... Heavy created" }
    }
}
