package io.bluetape4k.workshop.cbor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.cbor.course.CourseRepository
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    classes = [CborApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CborConfigTest {

    companion object: KLogging()

    @Autowired
    private val courseRepository: CourseRepository = uninitialized()

    @Test
    fun `context loading`() {
        courseRepository.shouldNotBeNull()
    }
}
