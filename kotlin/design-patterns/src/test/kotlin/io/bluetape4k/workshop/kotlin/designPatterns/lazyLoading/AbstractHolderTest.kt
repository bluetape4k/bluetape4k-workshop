package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

internal abstract class AbstractHolderTest {

    companion object: KLogging()

    internal abstract fun getInternalHeavyValue(): Heavy?

    internal abstract fun getHeavy(): Heavy?

    @Test
    @Timeout(3, unit = TimeUnit.SECONDS)
    fun `get heavy instance`() {

        getInternalHeavyValue()?.shouldBeNull()

        getHeavy().shouldNotBeNull()
        getInternalHeavyValue().shouldNotBeNull()

        getInternalHeavyValue() shouldBeEqualTo getHeavy()

    }
}
