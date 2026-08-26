package io.bluetape4k.workshop.optimization.shiftcoverage.web

import java.net.URI
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** demo console의 directory URL을 실제 static entrypoint로 연결합니다. */
@Profile("demo")
@RestController
class ShiftCoverageConsoleController {
    @GetMapping("/shift-coverage", "/shift-coverage/")
    fun index(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create("/shift-coverage/index.html"))
        .build()
}
