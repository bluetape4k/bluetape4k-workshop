import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit

internal data class HighContentionValidationProcessResult(
    val timedOut: Boolean,
    val exitCode: Int?,
    val stdout: String,
)

internal fun interface HighContentionValidationProcessLauncher {
    fun launch(
        command: List<String>,
        timeoutMillis: Long,
    ): HighContentionValidationProcessResult
}

internal class JdkHighContentionValidationProcessLauncher :
    HighContentionValidationProcessLauncher {

    override fun launch(
        command: List<String>,
        timeoutMillis: Long,
    ): HighContentionValidationProcessResult {
        require(command.isNotEmpty()) { "validation command must not be empty" }
        require(timeoutMillis > 0) { "validation timeout must be positive" }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(500, TimeUnit.MILLISECONDS)
            }
            return HighContentionValidationProcessResult(
                timedOut = true,
                exitCode = null,
                stdout = "",
            )
        }
        val output = ByteArrayOutputStream()
        process.inputStream.use { input ->
            val buffer = ByteArray(4_096)
            while (output.size() <= MAX_OUTPUT_BYTES) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                output.write(buffer, 0, read)
            }
        }
        val boundedOutput = if (output.size() <= MAX_OUTPUT_BYTES) {
            output.toString(Charsets.UTF_8)
        } else {
            ""
        }
        return HighContentionValidationProcessResult(
            timedOut = false,
            exitCode = process.exitValue(),
            stdout = boundedOutput,
        )
    }

    private companion object {
        const val MAX_OUTPUT_BYTES = 16_384
    }
}

internal class HighContentionArtifactValidator(
    private val nodeExecutable: Path,
    private val script: Path,
    private val timeoutMillis: Long,
    private val processLauncher: HighContentionValidationProcessLauncher =
        JdkHighContentionValidationProcessLauncher(),
) {

    fun validate(
        contractRoot: Path,
        runRoot: Path,
        uploadRoot: Path,
    ) {
        try {
            val trustedContractRoot = requireTrustedDirectory(contractRoot, "contract root")
            val trustedRunRoot = requireTrustedDirectory(runRoot, "run root")
            val trustedUploadRoot = requireTrustedDirectory(uploadRoot, "upload root")
            require(timeoutMillis > 0) { "validation timeout must be positive" }
            val command = listOf(
                nodeExecutable.toString(),
                script.toString(),
                trustedContractRoot.toString(),
                trustedRunRoot.toString(),
            )
            val result = processLauncher.launch(command, timeoutMillis)
            if (
                result.timedOut ||
                result.exitCode != 0 ||
                !VALID_SUCCESS.matches(result.stdout.trim())
            ) {
                throw IllegalStateException("high-contention run validation process failed")
            }
            requireTerminalFile(trustedRunRoot.resolve("summary.json"), SUMMARY_RESULT)
            requireTerminalFile(
                trustedRunRoot.resolve("upload-manifest.json"),
                UPLOAD_MANIFEST_RESULT,
            )
            if (Files.exists(trustedUploadRoot.resolve(FAILURE_FILE), NOFOLLOW_LINKS)) {
                throw IllegalStateException("validation fallback exists beside successful terminal artifacts")
            }
        } catch (error: Throwable) {
            writeFailureFallback(uploadRoot)
            throw IllegalStateException("high-contention artifact validation failed", error)
        }
    }

    private fun requireTerminalFile(
        path: Path,
        requiredContent: Regex,
    ) {
        if (
            !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path) ||
            Files.size(path) !in 1..MAX_TERMINAL_BYTES
        ) {
            throw IllegalStateException("high-contention terminal artifact is missing or invalid")
        }
        val before = Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        val content = Files.readString(path)
        val after = Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (
            before.fileKey() != after.fileKey() ||
            !requiredContent.containsMatchIn(content)
        ) {
            throw IllegalStateException("high-contention terminal artifact changed or is malformed")
        }
    }

    private fun requireTrustedDirectory(
        path: Path,
        description: String,
    ): Path {
        val absolute = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(absolute, NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute)) {
            throw IllegalStateException("$description is not a trusted directory")
        }
        return absolute.toRealPath(NOFOLLOW_LINKS)
    }

    companion object {
        fun writeFailureFallback(uploadRoot: Path) {
            val trustedUploadRoot = requireTrustedDirectoryStatic(uploadRoot, "upload root")
            val target = trustedUploadRoot.resolve(FAILURE_FILE)
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                if (
                    Files.isRegularFile(target, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(target) &&
                    Files.readAllBytes(target).contentEquals(FAILURE_BYTES)
                ) {
                    return
                }
                throw IllegalStateException("high-contention validation fallback already exists")
            }
            val temporary = trustedUploadRoot.resolve(".$FAILURE_FILE.tmp")
            FileChannel.open(temporary, CREATE_NEW, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(FAILURE_BYTES)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            try {
                try {
                    Files.move(temporary, target, ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target)
                }
                forceDirectory(trustedUploadRoot)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        private fun requireTrustedDirectoryStatic(
            path: Path,
            description: String,
        ): Path {
            val absolute = path.toAbsolutePath().normalize()
            if (!Files.isDirectory(absolute, NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute)) {
                throw IllegalStateException("$description is not a trusted directory")
            }
            return absolute.toRealPath(NOFOLLOW_LINKS)
        }

        private fun forceDirectory(path: Path) {
            try {
                FileChannel.open(path, READ).use { it.force(true) }
            } catch (_: UnsupportedOperationException) {
                // Directory fsync is not supported by every file provider.
            }
        }

        const val FAILURE_FILE = "upload-failure-summary.json"
        val FAILURE_BYTES: ByteArray =
            """
            {"schemaVersion":1,"result":"ERROR","errorCode":"ARTIFACT_VALIDATION_FAILED"}
            """.trimIndent().plus("\n").toByteArray()

        private const val MAX_TERMINAL_BYTES = 1_048_576L
        private val VALID_SUCCESS =
            Regex("""\{"result":"PASS"}""")
        private val SUMMARY_RESULT =
            Regex(
                """(?s)(?=.*"schemaVersion"\s*:\s*1)(?=.*"result"\s*:\s*"(?:PASS|FAIL|ERROR)")""",
                RegexOption.DOT_MATCHES_ALL,
            )
        private val UPLOAD_MANIFEST_RESULT =
            Regex(
                """(?s)(?=.*"schemaVersion"\s*:\s*1)(?=.*"files"\s*:\s*\[)""",
                RegexOption.DOT_MATCHES_ALL,
            )
    }
}
