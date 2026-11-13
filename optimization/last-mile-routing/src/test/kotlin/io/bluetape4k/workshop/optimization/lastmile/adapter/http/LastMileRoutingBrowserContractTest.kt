package io.bluetape4k.workshop.optimization.lastmile.adapter.http

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LastMileRoutingBrowserContractTest {

    companion object: KLogging()

    @Test
    fun `browser projection uses DOM text APIs and local synthetic assets`() {
        val root = Path.of("src/main/resources/static/last-mile-routing")
        val html = Files.readString(root.resolve("index.html"))
        val javascript = Files.readString(root.resolve("app.js"))

        html shouldContain "/last-mile-routing/app.js"
        html shouldContain "/last-mile-routing/app.css"

        javascript shouldContain "textContent"
        javascript shouldNotContain "innerHTML"
        javascript shouldNotContain "onclick"
        javascript shouldNotContain "eval("
        javascript shouldContain "document.createElementNS"
    }
}
