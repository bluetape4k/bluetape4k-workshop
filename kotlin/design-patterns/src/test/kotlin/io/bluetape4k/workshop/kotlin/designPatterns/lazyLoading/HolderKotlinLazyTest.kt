package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

internal class HolderKotlinLazyTest {

    companion object: KLogging()

    private val holder = HolderKotlinLazy()

    @Test
    @Timeout(3, unit = TimeUnit.SECONDS)
    fun `lazy loading`() {
        repeat(5) {
            log.debug { "Getting heavy ... $it" }
            holder.getHeavy().shouldNotBeNull()
        }
    }
}
