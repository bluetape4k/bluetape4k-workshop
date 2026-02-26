package io.bluetape4k.workshop.application.event.aspect

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.junit5.output.OutputCapture
import io.bluetape4k.junit5.output.OutputCapturer
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.application.event.EventApplication
import kotlinx.coroutines.delay
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@OutputCapture
@SpringBootTest(classes = [EventApplication::class])
class AspectFlowEventEmitterTest(
    @param:Autowired private val myEventService: MyEventService,
) {
    companion object: KLoggingChannel() {
        val faker = Fakers.faker
    }

    @Test
    fun `run operation then publish event`(output: OutputCapturer) = runSuspendIO {

        val id = faker.idNumber().valid()
        val params = OperationParams(id, faker.company().name())
        myEventService.someOperation(params)

        delay(100L)

        output.capture() shouldContain "Handle aspect event"

        // val params = someOperation(params)
        // val myAspectParams = MyAspectParams.create(params.id)
        // publish AspectEvent(src, myAspectParams)
        output.expect {
            it shouldContain "src=io.bluetape4k.workshop.application.event.aspect.MyEventService"
            it shouldContain "message=${MyAspectParams(id)}"
        }
    }
}
