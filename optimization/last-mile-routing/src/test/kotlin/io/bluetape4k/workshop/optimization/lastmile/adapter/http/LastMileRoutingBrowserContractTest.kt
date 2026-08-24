package io.bluetape4k.workshop.optimization.lastmile.adapter.http

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class LastMileRoutingBrowserContractTest {
    @Test
    fun `browser projection uses DOM text APIs and local synthetic assets`() {
        val root = Path.of("src/main/resources/static/last-mile-routing")
        val html = Files.readString(root.resolve("index.html"))
        val javascript = Files.readString(root.resolve("app.js"))

        html.contains("/last-mile-routing/app.js").shouldBeTrue()
        html.contains("/last-mile-routing/app.css").shouldBeTrue()
        javascript.contains("textContent").shouldBeTrue()
        javascript.contains("innerHTML").shouldBeFalse()
        javascript.contains("onclick").shouldBeFalse()
        javascript.contains("eval(").shouldBeFalse()
        javascript.contains("document.createElementNS").shouldBeTrue()
    }
}
