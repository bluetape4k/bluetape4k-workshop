package io.bluetape4k.workshop.protobuf

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    classes = [ProtobufApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CourseConfigTest @Autowired constructor(
    private val courseRepository: CourseRepository,
) {

    companion object : KLoggingChannel()

    @Test
    fun `context loading`() {
        courseRepository.shouldNotBeNull()
    }
}
