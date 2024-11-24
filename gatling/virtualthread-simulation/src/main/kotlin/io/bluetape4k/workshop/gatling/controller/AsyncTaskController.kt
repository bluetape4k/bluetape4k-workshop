package io.bluetape4k.workshop.gatling.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.gatling.service.AsyncTaskService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.system.measureTimeMillis

@RestController
@RequestMapping("/async")
class AsyncTaskController(
    private val asyncTaskService: AsyncTaskService,
) {

    companion object: KLogging()

    @GetMapping("/{seconds}")
    fun task(@PathVariable seconds: Int): Long {
        return measureTimeMillis {
            asyncTaskService.delay(seconds)
        }
    }

}
