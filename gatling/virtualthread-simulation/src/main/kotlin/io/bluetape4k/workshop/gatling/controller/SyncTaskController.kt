package io.bluetape4k.workshop.gatling.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.gatling.service.SyncTaskService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.system.measureTimeMillis

@RestController
@RequestMapping("/sync")
class SyncTaskController(
    private val syncTaskService: SyncTaskService,
) {

    companion object: KLogging()

    @GetMapping("/{seconds}")
    fun runTask(@PathVariable seconds: Int): Long {
        return measureTimeMillis {
            syncTaskService.delay(seconds)
        }
    }
}
