package io.bluetape4k.workshop.exposed

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.dto.UserCreateRequest
import io.bluetape4k.workshop.exposed.dto.UserUpdateRequest
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AbstractExposedApplicationTest {

    companion object: KLogging() {
        @JvmStatic
        val faker = Fakers.faker

        @JvmStatic
        fun newUserCreateRequest(): UserCreateRequest =
            UserCreateRequest(
                name = faker.name().fullName(),
                age = faker.number().numberBetween(1, 100)
            )

        @JvmStatic
        fun newUserUpdateRequest(): UserUpdateRequest =
            UserUpdateRequest(
                name = faker.name().fullName(),
                age = null
            )
    }

}
