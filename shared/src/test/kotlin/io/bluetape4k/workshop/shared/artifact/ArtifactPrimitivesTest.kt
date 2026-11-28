package io.bluetape4k.workshop.shared.artifact

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.io.artifact.ArtifactPrimitiveException
import io.bluetape4k.io.artifact.AtomicArtifactWriter
import io.bluetape4k.io.artifact.TrustedArtifactPaths
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

internal class ArtifactPrimitivesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `trusted paths reject traversal and descendant symlinks`() {
        val root = TrustedArtifactPaths.ensureTrustedDirectory(tempDir.resolve("artifacts"))
        TrustedArtifactPaths.requireIdentifier("run-01", "runId") shouldBeEqualTo "run-01"

        val outside = Files.createDirectory(tempDir.resolve("outside"))
        Files.createSymbolicLink(root.resolve("linked"), outside)

        val failure = assertFailsWith<ArtifactPrimitiveException> {
            TrustedArtifactPaths.resolveCreateTarget(root, Path.of("linked", "report.json"))
        }
        failure.message.shouldNotContain("secret")
        assertFailsWith<IllegalArgumentException> {
            TrustedArtifactPaths.resolveCreateTarget(root, Path.of("..", "escape.json"))
        }
    }

    @Test
    fun `atomic writer publishes exact bytes without replacing a target`() {
        val root = TrustedArtifactPaths.createNewTrustedDirectory(tempDir, "run-01")
        val bytes = "terminal-report\n".toByteArray()
        val target = AtomicArtifactWriter.writeNoReplace(root, Path.of("reports", "terminal.json"), bytes)

        Files.readAllBytes(target).toList() shouldBeEqualTo bytes.toList()
        val failure = assertFailsWith<ArtifactPrimitiveException> {
            AtomicArtifactWriter.writeNoReplace(root, Path.of("reports", "terminal.json"), "replacement".toByteArray())
        }
        failure.message.shouldContain("target already exists")
        Files.readAllBytes(target).toList() shouldBeEqualTo bytes.toList()
    }

    @Test
    fun `redaction guard combines common and consumer sentinels case insensitively`() {
        ArtifactRedactionGuard.verify("safe terminal result", setOf("private-token"))

        val failure = assertFailsWith<ArtifactRedactionException> {
            ArtifactRedactionGuard.verify("PRIVATE-TOKEN leaked", setOf("private-token"))
        }
        failure.message.shouldContain("redaction")
        assertFailsWith<ArtifactRedactionException> {
            ArtifactRedactionGuard.verify("redis://secret-host")
        }
    }

    @Test
    fun `hash chain commits only after a fsynced JSONL append`() {
        val path = tempDir.resolve("journal.jsonl")
        val payload = "{\"event\":\"setup\"}".toByteArray()
        val record = "{\"sequence\":0,\"payloadSha256\":\"${HashChainJsonl.sha256(payload)}\"}".toByteArray()
        val cursor = HashChainCursor()

        FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
            val digest = HashChainJsonl.append(channel, cursor, payload, record)
            digest.sequence shouldBeEqualTo 0L
            digest.previousRecordSha256 shouldBeEqualTo HashChainJsonl.GENESIS_SHA256
        }

        val bytes = Files.readAllBytes(path)
        bytes.decodeToString() shouldContain "\n"
        val verified = HashChainCursor().verify(
            sequence = 0L,
            previousRecordSha256 = HashChainJsonl.GENESIS_SHA256,
            payloadSha256 = HashChainJsonl.sha256(payload),
            payloadBytes = payload,
            recordBytes = record,
        )
        verified.recordSha256 shouldBeEqualTo HashChainJsonl.sha256(record)
    }

    @Test
    fun `complete JSONL lines ignore only a torn final suffix`() {
        val complete = "{\"sequence\":0}\n".toByteArray()
        val torn = complete + "{\"sequence\":".toByteArray()
        val lines = HashChainJsonl.completeLines(torn) { bytes ->
            bytes.contentEquals("{\"sequence\":0}".toByteArray())
        }
        lines shouldHaveSize 1

        assertFailsWith<IllegalStateException> {
            HashChainJsonl.completeLines(complete + "{\"sequence\":1}".toByteArray()) { true }
        }
        assertFailsWith<IllegalStateException> {
            HashChainJsonl.completeLines(complete + "\n".toByteArray()) { false }
        }
    }
}
