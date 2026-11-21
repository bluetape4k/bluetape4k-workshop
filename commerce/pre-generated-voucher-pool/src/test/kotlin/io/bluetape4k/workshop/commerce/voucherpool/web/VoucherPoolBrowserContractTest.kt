package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * VoucherPoolBrowserContractTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolBrowserContractTest {

    private companion object: KLogging() {
/**
 * MAX_BROWSER_OUTPUT_BYTES은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val MAX_BROWSER_OUTPUT_BYTES = 16 * 1024
    }

    @Test
/**
 * 이 시나리오는 "browser behavior passes the dependency free Node contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `browser behavior passes the dependency free Node contract`() {
        val harness = ClassPathResource("browser/voucher-pool-browser-contract.mjs")
        val application = ClassPathResource("static/app.js")
        val html = ClassPathResource("static/index.html")

        harness.exists().shouldBeTrue()
        application.exists().shouldBeTrue()
        html.exists().shouldBeTrue()

        val result = runNode(
            arguments = listOf(harness.file.absolutePath, application.file.absolutePath, html.file.absolutePath),
            timeout = 20.seconds(),
        )
        result.exitCode shouldBeEqualTo 0 // "browser contract failed:\n${result.output}"
    }

    @Test
/**
 * 이 시나리오는 "browser runner terminates a timed out Node process before reading bounded output" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `browser runner terminates a timed out Node process before reading bounded output`() {
        val failure = assertFailsWith<IllegalStateException> {
            runNode(
                arguments = listOf("-e", "process.stdout.write('started'); setInterval(() => {}, 1000)"),
                timeout = 100.millis(),
            )
        }

        failure.message shouldContain "timed out"
    }

    @Test
/**
 * 이 시나리오는 "browser runner drains large failure output without exceeding its diagnostic bound" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `browser runner drains large failure output without exceeding its diagnostic bound`() {
        val result = runNode(
            arguments = listOf("-e", "process.stdout.write('x'.repeat(256 * 1024)); process.exitCode = 7"),
            timeout = 20.seconds(),
        )

        result.exitCode shouldBeEqualTo 7
        result.output.toUtf8Bytes().size shouldBeLessOrEqualTo MAX_BROWSER_OUTPUT_BYTES
    }

    @Test
/**
 * 이 시나리오는 "browser exposes semantic customer and operator workflows" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "browser keeps voucher and operator secrets memory only" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "reveal replacement and revoke preview require explicit confirmation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "keyboard focus live announcements and bounded polling remain explicit" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * staticText은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        resource.exists().shouldBeTrue()

        return resource.inputStream.bufferedReader().use { it.readText() }
    }

    @Suppress("UnreachableCode") // Detekt mis-resolves Java Process control flow; both branches execute in tests.
/**
 * runNode은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun runNode(
        arguments: List<String>,
        timeout: Duration,
    ): BrowserProcessResult {
/**
 * process은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val process = try {
            ProcessBuilder(listOf("node") + arguments).redirectErrorStream(true).start()
        } catch (failure: Exception) {
            throw AssertionError("Node is required to execute the voucher-pool browser contract", failure)
        }
/**
 * output은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val output = BoundedProcessOutput(process.inputStream, MAX_BROWSER_OUTPUT_BYTES)

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(2.seconds()).shouldBeTrue()
            error("browser contract timed out: ${output.await()}")
        }
        return BrowserProcessResult(process.exitValue(), output.await())
    }
}

/**
 * BoundedProcessOutput 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class BoundedProcessOutput(
    input: InputStream,
    limit: Int,
) {
/**
 * bos은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val bos = ByteArrayOutputStream(limit)
/**
 * drain은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val drain = thread(start = true, isDaemon = true, name = "voucher-pool-node-output") {
        input.use { stream ->
/**
 * chunk은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
/**
 * count은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val count = stream.read(chunk)
                if (count < 0) break
/**
 * accepted은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val accepted = minOf(count, limit - bos.size()).coerceAtLeast(0)
                if (accepted > 0) bos.write(chunk, 0, accepted)
            }
        }
    }

/**
 * await은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun await(): String {
        drain.join(2.seconds().toMillis())
        drain.isAlive.shouldBeFalse()

        return bos.toByteArray().toUtf8String()
    }
}

/**
 * BrowserProcessResult 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private data class BrowserProcessResult(
/**
 * exitCode은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val exitCode: Int,
/**
 * output은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val output: String,
)
