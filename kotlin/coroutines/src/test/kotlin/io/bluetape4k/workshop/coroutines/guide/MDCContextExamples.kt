package io.bluetape4k.workshop.coroutines.guide

import io.bluetape4k.junit5.coroutines.runSuspendTest
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.withLoggingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class MDCContextExamples {

    companion object: KLoggingChannel()

    @Test
    fun `with mdc context`() = runSuspendTest {
        MDC.put("traceId", "100")
        log.debug { "Before operation" }
        MDC.get("traceId") shouldBeEqualTo "100"

        withContext(Dispatchers.IO + MDCContext()) {
            MDC.put("traceId", "200")
            log.debug { "Inside operation" }
            MDC.get("traceId") shouldBeEqualTo "200"
        }

        withLoggingContext("traceId" to "300") {
            log.debug { "Inside withLoggingContext" }
            MDC.get("traceId") shouldBeEqualTo "300"
        }

        log.debug { "After operation" }
        MDC.get("traceId") shouldBeEqualTo "100"
    }
}
