package io.bluetape4k.workshop.application.event.aspect

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Service
import java.io.Serializable

@Service
class MyEventService {

    companion object : KLogging()

    /**
     * 예제 작업을 실행하고 [AspectEventEmitter] 를 통해 [AspectEvent] 를 발생시킵니다.
     */
    @AspectEventEmitter(
        eventType = AspectEvent::class,
        params = """#{ T(io.bluetape4k.workshop.application.event.aspect.MyAspectParams).create(id) }"""
    )
    fun someOperation(params: OperationParams): OperationParams {
        val message = "Some operations is executed. $params"
        log.debug { "message=$message" }
        return params
    }
}

data class OperationParams(
    val id: String,
    val type: String,
) : Serializable {

    init {
        id.requireNotBlank("id")
        type.requireNotBlank("type")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class MyAspectParams(
    val message: String?,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        @JvmStatic
        fun create(message: String?): MyAspectParams {
            return MyAspectParams(message)
        }
    }
}
