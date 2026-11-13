package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
@Profile("demo")
internal class FieldServiceConsoleController {

    companion object: KLogging()

    @GetMapping("/field-service")
    fun index(): String = "forward:/field-service/index.html"
}
