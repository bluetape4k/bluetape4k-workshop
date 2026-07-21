package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class VoucherPoolBrowserContractTest {
    @Test
    fun `browser behavior passes the dependency free Node contract`() {
        val harness = ClassPathResource("browser/voucher-pool-browser-contract.mjs")
        val application = ClassPathResource("static/app.js")
        val html = ClassPathResource("static/index.html")
        check(harness.exists()) { "browser contract harness is missing" }
        check(application.exists()) { "browser application is missing" }
        check(html.exists()) { "browser HTML is missing" }

        val result = runNode(
            listOf(harness.file.absolutePath, application.file.absolutePath, html.file.absolutePath),
            Duration.ofSeconds(20),
        )
        check(result.exitCode == 0) { "browser contract failed:\n${result.output}" }
    }

    @Test
    fun `browser runner terminates a timed out Node process before reading bounded output`() {
        val failure = assertFailsWith<IllegalStateException> {
            runNode(
                listOf("-e", "process.stdout.write('started'); setInterval(() => {}, 1000)"),
                Duration.ofMillis(100),
            )
        }

        failure.message.orEmpty().contains("timed out").shouldBeTrue()
    }

    @Test
    fun `browser runner drains large failure output without exceeding its diagnostic bound`() {
        val result = runNode(
            listOf("-e", "process.stdout.write('x'.repeat(256 * 1024)); process.exitCode = 7"),
            Duration.ofSeconds(20),
        )

        result.exitCode shouldBeEqualTo 7
        (result.output.toByteArray().size <= MAX_BROWSER_OUTPUT_BYTES).shouldBeTrue()
    }

    @Test
    fun `browser exposes semantic customer and operator workflows`() {
        val html = staticText("index.html")

        listOf(
            "id=\"customer-view\"",
            "id=\"operator-view\"",
            "id=\"reveal-voucher\"",
            "aria-label=\"Reveal voucher code\"",
            "id=\"reveal-confirmation\"",
            "Reveal this voucher once?",
            "id=\"revoke-preview\"",
            "id=\"revoke-confirmation\"",
            "id=\"revoke-identity\"",
            "id=\"revoke-affected-count\"",
            "id=\"live-status\"",
            "aria-live=\"polite\"",
            "aria-atomic=\"true\"",
            "type=\"password\"",
            "autocomplete=\"off\"",
            "id=\"operator-guard\"",
        ).all(html::contains).shouldBeTrue()
    }

    @Test
    fun `browser keeps voucher and operator secrets memory only`() {
        val script = staticText("app.js")

        listOf(
            "revealedCode: null",
            "operatorSecret: null",
            "operatorGuard: null",
            "clearSensitiveState",
            "clearRevealedCode",
            "pagehide",
            "beforeunload",
            "logout",
            "replaceChildren()",
            "X-Workshop-Operator-Secret",
            "cache: \"no-store\"",
        ).all(script::contains).shouldBeTrue()
        listOf(
            "localStorage",
            "sessionStorage",
            "document.cookie",
            "history.",
            "innerHTML",
        ).none(script::contains).shouldBeTrue()
        Regex("setAttribute\\([^\\n]*(operatorSecret|revealedCode)").containsMatchIn(script).shouldBeFalse()
    }

    @Test
    fun `reveal replacement and revoke preview require explicit confirmation`() {
        val script = staticText("app.js")

        listOf(
            "Reveal this voucher once?",
            "revealConfirmationAccept.focus()",
            "Reveal cancelled",
            "ALREADY_REVEALED",
            "replacementAvailable",
            "confirmReplacement",
            "confirmLostReveal: true",
            "navigateToReservation",
            "operator-escalation",
            "safeRequestId",
            "aggregateIdentity",
            "preview.revision",
            "preview.affectedCount",
            "Identity does not match",
            "Revoke cancelled",
            "Revoke ${'$'}{preview.affectedCount} vouchers?",
            "previewToken: preview.previewToken",
        ).all(script::contains).shouldBeTrue()
    }

    @Test
    fun `keyboard focus live announcements and bounded polling remain explicit`() {
        val script = staticText("app.js")
        val styles = staticText("styles.css")

        listOf(
            "revealVoucher.addEventListener(\"click\"",
            "revealConfirmation.addEventListener(\"cancel\"",
            "revokeConfirmation.addEventListener(\"cancel\"",
            "announce(\"Reveal cancelled\")",
            "announce(\"Revoke cancelled\")",
            "MAX_POLL_ATTEMPTS",
            "pollingAttempts >= MAX_POLL_ATTEMPTS",
            "Polling fallback",
            "AbortController",
            "text/event-stream",
        ).all(script::contains).shouldBeTrue()
        listOf(
            ":focus-visible",
            ".status-label::before",
            "content: \"Status: \"",
            ".sr-only",
        ).all(styles::contains).shouldBeTrue()
    }

    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        check(resource.exists()) { "static resource is missing: $name" }
        return resource.inputStream.bufferedReader().use { it.readText() }
    }

    @Suppress("UnreachableCode") // Detekt mis-resolves Java Process control flow; both branches execute in tests.
    private fun runNode(
        arguments: List<String>,
        timeout: Duration,
    ): BrowserProcessResult {
        val process = try {
            ProcessBuilder(listOf("node") + arguments).redirectErrorStream(true).start()
        } catch (failure: Exception) {
            throw AssertionError("Node is required to execute the voucher-pool browser contract", failure)
        }
        val output = BoundedProcessOutput(process.inputStream, MAX_BROWSER_OUTPUT_BYTES)
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(2, TimeUnit.SECONDS)) { "browser contract did not terminate after forced shutdown" }
            error("browser contract timed out: ${output.await()}")
        }
        return BrowserProcessResult(process.exitValue(), output.await())
    }

    private companion object {
        const val MAX_BROWSER_OUTPUT_BYTES = 16 * 1024
    }
}

private class BoundedProcessOutput(
    input: InputStream,
    private val limit: Int,
) {
    private val bytes = ByteArrayOutputStream(limit)
    private val drain = thread(start = true, isDaemon = true, name = "voucher-pool-node-output") {
        input.use { stream ->
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                val accepted = minOf(count, limit - bytes.size()).coerceAtLeast(0)
                if (accepted > 0) bytes.write(chunk, 0, accepted)
            }
        }
    }

    fun await(): String {
        drain.join(TimeUnit.SECONDS.toMillis(2))
        check(!drain.isAlive) { "browser contract output drain did not terminate" }
        return bytes.toByteArray().decodeToString()
    }
}

private data class BrowserProcessResult(
    val exitCode: Int,
    val output: String,
)
