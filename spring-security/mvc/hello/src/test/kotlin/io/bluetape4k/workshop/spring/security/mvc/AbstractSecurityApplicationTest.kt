package io.bluetape4k.workshop.spring.security.mvc

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc

@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractSecurityApplicationTest {

    companion object: KLogging()

    @Autowired
    protected val mockMvc: MockMvc = uninitialized()
}
