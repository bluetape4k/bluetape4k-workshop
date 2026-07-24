package io.bluetape4k.workshop.buildlogic.highcontention

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class HighContentionMatrixEntry(
    val profileId: String,
    val implementations: List<String>,
)

internal data class HighContentionChildKey(
    val profileId: String,
    val implementation: String,
)

internal object HighContentionWorkflowIdentity {
    private val HOSTED_RUN_ID = Regex("""^(?:examples|nightly)-([0-9]+)-([1-9][0-9]*)$""")

    fun fromRunId(runId: String): String =
        HOSTED_RUN_ID.matchEntire(runId)
            ?.let { match -> "${match.groupValues[1]}-${match.groupValues[2]}" }
            ?: "local-0"
}

object HighContentionRootCallerBoundary {
    private val allowedProjectProperties = setOf(
        "highContentionRunId",
        "highContentionProfileId",
        "highContentionImplementation",
    )

    fun validate(
        projectPropertyNames: Set<String>,
        systemPropertyNames: Set<String>,
        environmentNames: Set<String>,
    ) {
        if (
            projectPropertyNames.any {
                it.startsWith("highContention") && it !in allowedProjectProperties
            }
        ) {
            throw GradleException("caller supplied a reserved high-contention project property")
        }
        if (
            systemPropertyNames.any {
                it.startsWith("high.contention.") || it.startsWith("highContention")
            }
        ) {
            throw GradleException("caller supplied a reserved high-contention system property")
        }
        if (
            environmentNames.any {
                it.startsWith("HIGH_CONTENTION_") ||
                    it.startsWith("BLUETAPE_HIGH_CONTENTION_")
            }
        ) {
            throw GradleException("caller supplied a reserved high-contention environment channel")
        }
    }
}

internal object HighContentionSuiteSelection {
    fun select(
        entries: List<HighContentionMatrixEntry>,
        profileId: String?,
        implementation: String?,
    ): List<HighContentionChildKey> {
        profileId?.let(::requireIdentifier)
        implementation?.let(::requireIdentifier)
        val selected = entries
            .asSequence()
            .filter { profileId == null || it.profileId == profileId }
            .flatMap { entry ->
                entry.implementations
                    .asSequence()
                    .filter { implementation == null || it == implementation }
                    .map { HighContentionChildKey(entry.profileId, it) }
            }
            .toList()
        require(selected.isNotEmpty()) { "high-contention selection must not be empty" }
        require(selected.distinct().size == selected.size) {
            "high-contention selection contains a duplicate child"
        }
        return selected
    }

    private fun requireIdentifier(value: String) {
        require(IDENTIFIER.matches(value) && value != "." && value != "..") {
            "high-contention filter must be a bounded identifier"
        }
    }

    private val IDENTIFIER = Regex("[a-z0-9][a-z0-9._-]{0,63}")
}

internal enum class HighContentionChildStatus {
    PASS,
    FAIL,
    ERROR,
    UNAVAILABLE,
}

internal data class HighContentionChildGate(
    val cleanupZeroLive: Boolean,
    val childProcessesZeroLive: Boolean,
    val artifactValid: Boolean,
    val parentCleanupSucceeded: Boolean,
    val errorContinuable: Boolean = true,
)

internal enum class HighContentionContinuation {
    CONTINUE,
    CONTINUE_WITH_FINAL_FAILURE,
    STOP,
}

internal object HighContentionContinuationPolicy {
    fun evaluate(
        status: HighContentionChildStatus,
        gate: HighContentionChildGate,
    ): HighContentionContinuation {
        if (
            !gate.cleanupZeroLive ||
            !gate.childProcessesZeroLive ||
            !gate.artifactValid ||
            !gate.parentCleanupSucceeded ||
            (status == HighContentionChildStatus.ERROR && !gate.errorContinuable)
        ) {
            return HighContentionContinuation.STOP
        }
        return if (status == HighContentionChildStatus.PASS) {
            HighContentionContinuation.CONTINUE
        } else {
            HighContentionContinuation.CONTINUE_WITH_FINAL_FAILURE
        }
    }
}

internal data class HighContentionDeadlineBudget(
    val absoluteRunDeadlineNanos: Long,
    val runCleanupReserveNanos: Long,
) {
    private val runExecutionDeadlineNanos =
        Math.subtractExact(absoluteRunDeadlineNanos, runCleanupReserveNanos)

    init {
        require(runCleanupReserveNanos > 0) { "run cleanup reserve must be positive" }
        require(runExecutionDeadlineNanos > 0) { "run execution deadline must be positive" }
    }

    fun canStartProfile(
        nowNanos: Long,
        profileDeadlineNanos: Long,
    ): Boolean =
        profileDeadlineNanos > 0 &&
            nowNanos <= runExecutionDeadlineNanos - profileDeadlineNanos

    fun clipExecutionPhase(
        configuredBudgetNanos: Long,
        nowNanos: Long,
        profileExecutionDeadlineNanos: Long,
    ): Long =
        minOf(
            configuredBudgetNanos,
            (profileExecutionDeadlineNanos - nowNanos).coerceAtLeast(0),
            (runExecutionDeadlineNanos - nowNanos).coerceAtLeast(0),
        )
}

internal data class HighContentionResourceAllocation(
    val resourceKey: String,
    val resourceType: String,
    val labels: Map<String, String>,
)

internal class HighContentionResourceLabelAllocator {
    private val allocated = mutableSetOf<Pair<String, String>>()

    fun allocate(
        runId: String,
        profileId: String,
    ): List<HighContentionResourceAllocation> {
        val tuple = runId to profileId
        check(allocated.add(tuple)) { "high-contention label tuple is already allocated" }
        return RESOURCE_TYPES.map { (resourceKey, resourceType) ->
            HighContentionResourceAllocation(
                resourceKey = resourceKey,
                resourceType = resourceType,
                labels = sortedMapOf(
                    RUN_ID_LABEL to runId,
                    PROFILE_ID_LABEL to profileId,
                    RESOURCE_KEY_LABEL to resourceKey,
                    RESOURCE_TYPE_LABEL to resourceType,
                ),
            )
        }
    }

    fun release(
        runId: String,
        profileId: String,
    ) {
        check(allocated.remove(runId to profileId)) {
            "high-contention label tuple was not allocated"
        }
    }

    private companion object {
        const val RUN_ID_LABEL = "io.bluetape4k.high-contention.run-id"
        const val PROFILE_ID_LABEL = "io.bluetape4k.high-contention.profile-id"
        const val RESOURCE_KEY_LABEL = "io.bluetape4k.high-contention.resource-key"
        const val RESOURCE_TYPE_LABEL = "io.bluetape4k.high-contention.resource-type"
        val RESOURCE_TYPES = listOf(
            "network" to "network",
            "redis" to "container",
            "toxiproxy" to "container",
        )
    }
}

internal data class HighContentionDockerObject(
    val id: String,
    val labels: Map<String, String>,
)

internal interface HighContentionDockerCli {
    fun find(
        labels: Map<String, String>,
        absoluteDeadlineNanos: Long,
    ): List<HighContentionDockerObject>

    fun delete(
        resource: HighContentionDockerObject,
        absoluteDeadlineNanos: Long,
    ): Boolean
}

internal class HighContentionDockerCleanup(
    private val docker: HighContentionDockerCli,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepMillis: (Long) -> Unit = Thread::sleep,
) {
    fun cleanup(
        expectedResources: List<HighContentionResourceAllocation>,
        absoluteDeadlineNanos: Long,
        quietPeriodMillis: Long,
        pollIntervalMillis: Long,
    ): Int {
        require(expectedResources.isNotEmpty()) { "expected resources must not be empty" }
        require(quietPeriodMillis >= pollIntervalMillis * 2) {
            "cleanup quiet period must cover at least two polls"
        }
        require(pollIntervalMillis > 0) { "cleanup poll interval must be positive" }
        val expectedByLabels = expectedResources.associateBy { it.labels }
        val commonLabels = expectedResources.first().labels.filterKeys {
            it.endsWith("run-id") || it.endsWith("profile-id")
        }
        var quietSinceNanos: Long? = null
        var deletedCount = 0
        while (nanoTime() < absoluteDeadlineNanos) {
            val discovered = docker.find(commonLabels, absoluteDeadlineNanos)
            val duplicates = discovered.groupBy(HighContentionDockerObject::labels)
                .filterValues { it.size > 1 }
            check(duplicates.isEmpty()) { "high-contention Docker label collision detected" }
            discovered.forEach { resource ->
                check(expectedByLabels.containsKey(resource.labels)) {
                    "high-contention Docker labels do not match the parent allocation"
                }
            }
            if (discovered.isEmpty()) {
                val quietStart = quietSinceNanos ?: nanoTime().also { quietSinceNanos = it }
                if (
                    nanoTime() - quietStart >=
                    TimeUnit.MILLISECONDS.toNanos(quietPeriodMillis)
                ) {
                    return deletedCount
                }
            } else {
                quietSinceNanos = null
                discovered
                    .sortedByDescending {
                        expectedByLabels.getValue(it.labels).resourceType == "container"
                    }
                    .forEach {
                        check(nanoTime() < absoluteDeadlineNanos) {
                            "high-contention Docker cleanup deadline expired during deletion"
                        }
                        if (docker.delete(it, absoluteDeadlineNanos)) {
                            deletedCount += 1
                        }
                    }
            }
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(
                (absoluteDeadlineNanos - nanoTime()).coerceAtLeast(0L),
            )
            if (remainingMillis > 0L) {
                sleepMillis(minOf(pollIntervalMillis, remainingMillis))
            }
        }
        error("high-contention Docker cleanup did not converge to stable zero")
    }
}

internal class JdkHighContentionDockerCli(
    private val executable: String = "docker",
    private val commandTimeoutMillis: Long = 10_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val commandExecutor: HighContentionDockerCommandExecutor =
        JdkHighContentionDockerCommandExecutor(),
) : HighContentionDockerCli {

    override fun find(
        labels: Map<String, String>,
        absoluteDeadlineNanos: Long,
    ): List<HighContentionDockerObject> {
        require(labels.isNotEmpty()) { "Docker discovery labels must not be empty" }
        val filters = labels.entries
            .sortedBy(Map.Entry<String, String>::key)
            .flatMap { (key, value) -> listOf("--filter", "label=$key=$value") }
        val containers = command(
            listOf(executable, "ps", "-aq") + filters,
            absoluteDeadlineNanos,
        ).orEmpty().lineSequence().filter(String::isNotBlank).mapNotNull { id ->
            inspectContainer(id, absoluteDeadlineNanos)
        }
        val networks = command(
            listOf(executable, "network", "ls", "-q") + filters,
            absoluteDeadlineNanos,
        ).orEmpty().lineSequence().filter(String::isNotBlank).mapNotNull { id ->
            inspectNetwork(id, absoluteDeadlineNanos)
        }
        return (containers + networks).toList()
    }

    override fun delete(
        resource: HighContentionDockerObject,
        absoluteDeadlineNanos: Long,
    ): Boolean =
        when (
            resource.labels["io.bluetape4k.high-contention.resource-type"]
        ) {
            "container" ->
                command(
                    listOf(executable, "rm", "-f", resource.id),
                    absoluteDeadlineNanos,
                    missingObjectId = resource.id,
                ) != null

            "network" ->
                command(
                    listOf(executable, "network", "rm", resource.id),
                    absoluteDeadlineNanos,
                    missingObjectId = resource.id,
                ) != null

            else -> error("Docker resource type is outside the cleanup allowlist")
        }

    private fun inspectContainer(
        id: String,
        absoluteDeadlineNanos: Long,
    ): HighContentionDockerObject? {
        val validId = requireDockerId(id)
        val labels = command(
            listOf(
                executable,
                "inspect",
                "--format",
                "{{json .Config.Labels}}",
                validId,
            ),
            absoluteDeadlineNanos,
            missingObjectId = validId,
        ) ?: return null
        return HighContentionDockerObject(validId, parseLabels(labels))
    }

    private fun inspectNetwork(
        id: String,
        absoluteDeadlineNanos: Long,
    ): HighContentionDockerObject? {
        val validId = requireDockerId(id)
        val labels = command(
            listOf(
                executable,
                "network",
                "inspect",
                "--format",
                "{{json .Labels}}",
                validId,
            ),
            absoluteDeadlineNanos,
            missingObjectId = validId,
        ) ?: return null
        return HighContentionDockerObject(validId, parseLabels(labels))
    }

    private fun parseLabels(json: String): Map<String, String> {
        val labels = JsonSlurper().parseText(json.trim()) as? Map<*, *>
            ?: error("Docker labels are malformed")
        return labels.entries
            .map { (key, value) ->
                (key as? String ?: error("Docker label key is malformed")) to
                    (value as? String ?: error("Docker label value is malformed"))
            }
            .filter { (key, _) -> key.startsWith("io.bluetape4k.high-contention.") }
            .toMap()
    }

    private fun requireDockerId(value: String): String {
        val id = value.trim()
        require(DOCKER_ID.matches(id)) { "Docker returned an invalid resource ID" }
        return id
    }

    private fun command(
        arguments: List<String>,
        absoluteDeadlineNanos: Long,
        missingObjectId: String? = null,
    ): String? {
        val remainingNanos = absoluteDeadlineNanos - nanoTime()
        check(remainingNanos > 0L) {
            "Docker cleanup deadline expired before command execution"
        }
        val effectiveTimeoutMillis = minOf(
            commandTimeoutMillis,
            TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
        )
        val result = commandExecutor.execute(arguments, effectiveTimeoutMillis)
        check(result.output.length <= MAX_OUTPUT_CHARS) {
            "Docker cleanup command output exceeded its bound"
        }
        if (result.exitCode != 0) {
            if (
                missingObjectId != null &&
                HighContentionDockerNotFound.matches(result.output, missingObjectId)
            ) {
                return null
            }
            error("Docker cleanup command failed")
        }
        return result.output
    }

    private companion object {
        val DOCKER_ID = Regex("[0-9a-f]{12,64}")
        const val MAX_OUTPUT_CHARS = 65_536L
    }
}

internal data class HighContentionDockerCommandResult(
    val exitCode: Int,
    val output: String,
)

internal fun interface HighContentionDockerCommandExecutor {
    fun execute(
        arguments: List<String>,
        timeoutMillis: Long,
    ): HighContentionDockerCommandResult
}

internal class JdkHighContentionDockerCommandExecutor : HighContentionDockerCommandExecutor {
    override fun execute(
        arguments: List<String>,
        timeoutMillis: Long,
    ): HighContentionDockerCommandResult {
        val outputFile = Files.createTempFile("high-contention-docker-", ".log")
        var process: Process? = null
        try {
            process = ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start()
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    "Docker cleanup command did not terminate after timeout"
                }
                error("Docker cleanup command timed out")
            }
            check(Files.size(outputFile) <= MAX_OUTPUT_BYTES) {
                "Docker cleanup command output exceeded its bound"
            }
            return HighContentionDockerCommandResult(
                exitCode = process.exitValue(),
                output = Files.readString(outputFile),
            )
        } catch (error: InterruptedException) {
            process?.destroyForcibly()
            val terminated = process?.waitFor(500, TimeUnit.MILLISECONDS) ?: true
            Thread.currentThread().interrupt()
            check(terminated) {
                "Docker cleanup command did not terminate after interruption"
            }
            throw IllegalStateException("Docker cleanup command was interrupted", error)
        } finally {
            process?.takeIf(Process::isAlive)?.destroyForcibly()
            Files.deleteIfExists(outputFile)
        }
    }

    private companion object {
        const val MAX_OUTPUT_BYTES = 65_536L
    }
}

internal object HighContentionDockerNotFound {
    fun matches(
        output: String,
        expectedId: String,
    ): Boolean {
        val escapedId = Regex.escape(expectedId)
        val options = setOf(RegexOption.IGNORE_CASE)
        return output.lineSequence().any { line ->
            line.trim().matches(
                Regex(""".*No such (?:object|container):?\s+$escapedId\s*""", options),
            ) || line.trim().matches(
                Regex(""".*network\s+$escapedId\s+not found\s*""", options),
            )
        }
    }
}

internal object HighContentionRunRoot {
    fun create(
        outputRoot: Path,
        runId: String,
    ): Path {
        val selection = HighContentionProfileSelection.validate(
            runId = runId,
            profileId = "burst",
            allowedProfiles = setOf("burst"),
        )
        val absoluteRoot = outputRoot.toAbsolutePath().normalize()
        if (
            !Files.isDirectory(absoluteRoot, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(absoluteRoot)
        ) {
            throw IllegalStateException("high-contention output root is not a trusted directory")
        }
        val trustedRoot = absoluteRoot.toRealPath(NOFOLLOW_LINKS)
        val target = trustedRoot.resolve(selection.runId)
        try {
            Files.createDirectory(target)
        } catch (error: Exception) {
            throw IllegalStateException("high-contention run directory must be created exactly once", error)
        }
        if (
            Files.isSymbolicLink(target) ||
            target.toRealPath(NOFOLLOW_LINKS).parent != trustedRoot
        ) {
            Files.deleteIfExists(target)
            throw IllegalStateException("high-contention run directory escaped its output root")
        }
        forceDirectory(trustedRoot)
        return target.toRealPath(NOFOLLOW_LINKS)
    }

    fun openExisting(
        outputRoot: Path,
        runId: String,
    ): Path {
        val trustedRoot = outputRoot.toAbsolutePath().normalize().toRealPath(NOFOLLOW_LINKS)
        val target = trustedRoot.resolve(
            HighContentionProfileSelection.validate(
                runId = runId,
                profileId = "burst",
                allowedProfiles = setOf("burst"),
            ).runId,
        )
        if (
            !Files.isDirectory(target, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(target) ||
            target.toRealPath(NOFOLLOW_LINKS).parent != trustedRoot
        ) {
            throw IllegalStateException("high-contention run directory is not trusted")
        }
        return target.toRealPath(NOFOLLOW_LINKS)
    }
}

object HighContentionBootstrapFailure {
    fun prepare(
        uploadRoot: Path,
        runId: String,
    ) {
        val configuredRoot = uploadRoot.toAbsolutePath().normalize()
        createTrustedDirectories(configuredRoot)
        val failureRoot = HighContentionRunRoot.create(configuredRoot, runId)
        HighContentionArtifactValidator.writeFailureFallback(failureRoot)
    }
}

internal data class HighContentionChildProcessResult(
    val timedOut: Boolean,
    val exitCode: Int?,
    val observedPids: Set<Long>,
)

internal class HighContentionChildProcessRunner(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun run(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: Path,
        outputLog: Path,
        workerPidFile: Path,
        ownerToken: String,
        timeoutMillis: Long,
        cleanupTimeoutMillis: Long,
    ): HighContentionChildProcessResult {
        require(command.isNotEmpty()) { "child command must not be empty" }
        require(timeoutMillis > 0) { "child timeout must be positive" }
        require(cleanupTimeoutMillis > 0) { "child cleanup timeout must be positive" }
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputLog.toFile())
            .apply { environment().putAll(environment) }
            .start()
        val ownedRoots = mutableListOf<HighContentionProcessRef>(
            JdkHighContentionProcessRef(process.toHandle()),
        )
        val reaper = HighContentionProcessReaper(
            discoverOwned = {
                discoverHighContentionOwnedProcesses(
                    propertyName = PROCESS_OWNER_PROPERTY,
                    ownerToken = ownerToken,
                )
            },
        )
        var completed = false
        var recordedWorkerPid: Long? = null
        var observed = emptySet<Long>()
        var primaryFailure: Throwable? = null
        try {
            completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            recordedWorkerPid = readWorkerPid(workerPidFile)
            recordedWorkerPid?.let { pid ->
                findHighContentionOwnedProcess(
                    pid = pid,
                    propertyName = PROCESS_OWNER_PROPERTY,
                    ownerToken = ownerToken,
                )?.let(ownedRoots::add)
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                observed = reaper.reap(
                    rootProcesses = ownedRoots,
                    gracefulTimeoutMillis = if (completed) 0 else GRACEFUL_TIMEOUT_MILLIS,
                    absoluteDeadlineNanos = Math.addExact(
                        nanoTime(),
                        TimeUnit.MILLISECONDS.toNanos(cleanupTimeoutMillis),
                    ),
                    quietPeriodMillis = STABLE_ZERO_QUIET_PERIOD_MILLIS,
                    pollIntervalMillis = POLL_INTERVAL_MILLIS,
                )
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) {
                    throw cleanupFailure
                }
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
        return HighContentionChildProcessResult(
            timedOut = !completed,
            exitCode = if (completed) process.exitValue() else null,
            observedPids = observed + listOfNotNull(recordedWorkerPid),
        )
    }

    private fun readWorkerPid(path: Path): Long? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return null
        }
        if (
            !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path) ||
            Files.size(path) !in 1..32
        ) {
            error("high-contention worker PID artifact is invalid")
        }
        val value = Files.readString(path).trim()
        require(PID.matches(value)) { "high-contention worker PID is invalid" }
        return value.toLong()
    }

    companion object {
        const val PROCESS_OWNER_PROPERTY = "highContentionProcessOwner"
        private const val GRACEFUL_TIMEOUT_MILLIS = 500L
        private const val STABLE_ZERO_QUIET_PERIOD_MILLIS = 500L
        private const val POLL_INTERVAL_MILLIS = 25L
        private val PID = Regex("[1-9][0-9]{0,18}")
    }
}

internal object HighContentionChildArtifactFinalizer {
    fun finalize(
        runRoot: Path,
        child: HighContentionChildKey,
    ) {
        if (child.implementation != "ticket-spring") {
            return
        }
        val legacyReport = runRoot.resolve(
            "ticket-spring-${child.profileId}-report.json",
        )
        if (Files.isRegularFile(legacyReport, NOFOLLOW_LINKS) && !Files.isSymbolicLink(legacyReport)) {
            val canonicalReport = runRoot.resolve(
                "reports/${child.implementation}/${child.profileId}.json",
            )
            createTrustedDirectories(canonicalReport.parent)
            try {
                Files.move(legacyReport, canonicalReport, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(legacyReport, canonicalReport)
            }
            forceDirectory(canonicalReport.parent)
        }
        Files.deleteIfExists(runRoot.resolve("ticket-topology.jsonl"))
        forceDirectory(runRoot)
    }
}

internal object HighContentionChildFailureDiagnostic {
    fun describe(
        child: HighContentionChildKey,
        outputLog: Path,
        exitCode: Int?,
        secrets: List<String>,
    ): String {
        val output = if (
            Files.isRegularFile(outputLog, NOFOLLOW_LINKS) &&
            Files.size(outputLog) <= MAX_OUTPUT_BYTES
        ) {
            Files.readString(outputLog)
        } else {
            ""
        }
        val classification = when {
            "not bound to its live parent" in output -> "PARENT_CAPABILITY_MISMATCH"
            "capability digest does not match" in output -> "CAPABILITY_DIGEST_MISMATCH"
            "capability channel is incomplete" in output -> "CAPABILITY_CHANNEL_INCOMPLETE"
            "source worktree must be clean" in output -> "DIRTY_SOURCE"
            "Compilation error" in output -> "CHILD_COMPILATION_ERROR"
            "There were failing tests" in output -> "CHILD_TEST_FAILURE"
            exitCode == 137 -> "CHILD_PROCESS_KILLED"
            else -> "CHILD_EXECUTION_ERROR"
        }
        val excerpt = output
            .lines()
            .takeLast(MAX_EXCERPT_LINES)
            .joinToString("\n")
            .takeLast(MAX_EXCERPT_CHARS)
        val redacted = secrets.fold(excerpt) { sanitized, secret ->
            if (secret.isBlank()) sanitized else sanitized.replace(secret, "[REDACTED]")
        }
        return buildString {
            append(child.implementation)
            append("/")
            append(child.profileId)
            append(": ")
            append(classification)
            if (redacted.isNotBlank()) {
                append("\n--- bounded child output tail ---\n")
                append(redacted)
            }
        }
    }

    private const val MAX_OUTPUT_BYTES = 4L * 1024 * 1024
    private const val MAX_EXCERPT_LINES = 40
    private const val MAX_EXCERPT_CHARS = 16 * 1024
}

internal object HighContentionChildGradleRuntime {
    val arguments = listOf(
        "-Dorg.gradle.jvmargs=-Xmx2g",
        "-Pkotlin.compiler.execution.strategy=in-process",
    )
}

@DisableCachingByDefault(because = "Runs heavyweight child profiles in isolated external Gradle processes.")
abstract class HighContentionSuiteTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryRoot: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractRoot: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val gradleWrapper: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val validationScript: RegularFileProperty

    @get:Input
    abstract val runId: Property<String>

    @get:Input
    abstract val mode: Property<String>

    @get:Input
    @get:Optional
    abstract val profileId: Property<String>

    @get:Input
    @get:Optional
    abstract val implementation: Property<String>

    @get:Internal
    abstract val outputRoot: DirectoryProperty

    @get:Internal
    abstract val uploadRoot: DirectoryProperty

    @get:Internal
    abstract val coordinatorWorkRoot: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun coordinate() {
        val repository = repositoryRoot.get().asFile.toPath().toRealPath()
        val contract = contractRoot.get().asFile.toPath().toRealPath(NOFOLLOW_LINKS)
        val wrapper = gradleWrapper.get().asFile.toPath().toRealPath(NOFOLLOW_LINKS)
        val selectedMode = mode.get()
        if (selectedMode !in setOf("ci-correctness", "local-reference")) {
            throw GradleException("unsupported high-contention suite mode")
        }
        val manifest = loadManifest(contract, selectedMode)
        val selectedRunId = HighContentionProfileSelection.validate(
            runId = runId.orNull.orEmpty(),
            profileId = profileId.orNull ?: manifest.entries.first().profileId,
            allowedProfiles = manifest.entries.mapTo(mutableSetOf(), HighContentionMatrixEntry::profileId),
        ).runId
        val selectedChildren = HighContentionSuiteSelection.select(
            entries = manifest.entries,
            profileId = profileId.orNull,
            implementation = implementation.orNull,
        )
        if (
            selectedMode == "local-reference" ||
            (System.getenv("CI") == "true" && System.getenv("GITHUB_ACTIONS") == "true")
        ) {
            HighContentionSourceState.requireClean(repository)
        }

        val configuredOutputRoot = outputRoot.get().asFile.toPath().toAbsolutePath().normalize()
        createTrustedDirectories(configuredOutputRoot)
        val runRoot = HighContentionRunRoot.create(configuredOutputRoot, selectedRunId)
        val configuredUploadRoot = uploadRoot.get().asFile.toPath().toAbsolutePath().normalize()
        createTrustedDirectories(configuredUploadRoot)
        val runUploadRoot = HighContentionRunRoot.openExisting(configuredUploadRoot, selectedRunId)
        val configuredWorkRoot = coordinatorWorkRoot.get().asFile.toPath().toAbsolutePath().normalize()
        createTrustedDirectories(configuredWorkRoot)
        val workRoot = HighContentionRunRoot.create(configuredWorkRoot, selectedRunId)

        val startedNanos = System.nanoTime()
        val budget = HighContentionDeadlineBudget(
            absoluteRunDeadlineNanos = Math.addExact(
                startedNanos,
                TimeUnit.MILLISECONDS.toNanos(manifest.runDeadlineMillis),
            ),
            runCleanupReserveNanos =
                TimeUnit.MILLISECONDS.toNanos(manifest.runCleanupReserveMillis),
        )
        val manifestBytes = runManifestBytes(
            runId = selectedRunId,
            mode = selectedMode,
            workflowRunAndAttempt = HighContentionWorkflowIdentity.fromRunId(selectedRunId),
            children = selectedChildren,
        )
        writeNoReplace(runRoot.resolve("run-manifest.json"), manifestBytes)
        val parentManifestDigest = sha256(manifestBytes)
        val runJournal = runRoot.resolve("run-journal.jsonl")
        FileChannel.open(runJournal, CREATE_NEW, WRITE).use { it.force(true) }
        forceDirectory(runRoot)

        val allocator = HighContentionResourceLabelAllocator()
        var finalFailure = false
        var primaryFailure: Throwable? = null
        try {
            selectedChildren.forEachIndexed { index, child ->
                val profileDeadlineMillis = loadProfileDeadlineMillis(
                    contract = contract,
                    mode = selectedMode,
                    profileId = child.profileId,
                )
                if (
                    !budget.canStartProfile(
                        nowNanos = System.nanoTime(),
                        profileDeadlineNanos =
                            TimeUnit.MILLISECONDS.toNanos(profileDeadlineMillis),
                    )
                ) {
                    throw GradleException(
                        "complete profile deadline does not remain in the run execution budget",
                    )
                }
                val resources = allocator.allocate(selectedRunId, child.profileId)
                val workerPidFile = workerPidPath(runRoot, child)
                val projectCache = Files.createDirectory(workRoot.resolve("project-cache-$index"))
                val outputLog = workRoot.resolve("child-$index.log")
                var parentDeletedResourceCount = 0
                var parentCleanupSucceeded = false
                var resourcesReleased = false
                fun cleanupAllocatedResources() {
                    if (resourcesReleased) {
                        return
                    }
                    try {
                        parentDeletedResourceCount = HighContentionDockerCleanup(
                            docker = JdkHighContentionDockerCli(),
                        ).cleanup(
                            expectedResources = resources,
                            absoluteDeadlineNanos = Math.addExact(
                                System.nanoTime(),
                                TimeUnit.MILLISECONDS.toNanos(
                                    manifest.dockerDiscoveryMillis,
                                ),
                            ),
                            quietPeriodMillis = manifest.dockerCleanupQuietPeriodMillis,
                            pollIntervalMillis = manifest.dockerCleanupPollIntervalMillis,
                        )
                        parentCleanupSucceeded = true
                    } finally {
                        allocator.release(selectedRunId, child.profileId)
                        resourcesReleased = true
                    }
                }
                val result: HighContentionChildProcessResult
                val report: Path
                try {
                    val capability = childCapability()
                    val descriptor = writeChildDescriptor(
                        runRoot = runRoot,
                        runId = selectedRunId,
                        mode = selectedMode,
                        child = child,
                        parentManifestDigest = parentManifestDigest,
                        resources = resources,
                        capability = capability,
                    )
                    appendJournal(
                        runJournal,
                        mapOf(
                            "schemaVersion" to 1,
                            "event" to "CHILD_STARTING",
                            "ordinal" to index,
                            "profileId" to child.profileId,
                            "implementation" to child.implementation,
                        ),
                    )
                    val ownerToken = hmacSha256(capability, Files.readAllBytes(descriptor))
                    val command = childCommand(
                        wrapper = wrapper,
                        child = child,
                        mode = selectedMode,
                        runId = selectedRunId,
                        projectCache = projectCache,
                        ownerToken = ownerToken,
                    )
                    result = HighContentionChildProcessRunner().run(
                        command = command,
                        environment = mapOf(
                            HIGH_CONTENTION_CHILD_DESCRIPTOR_ENV to descriptor.toString(),
                            HIGH_CONTENTION_CHILD_CAPABILITY_ENV to capability,
                        ),
                        workingDirectory = repository,
                        outputLog = outputLog,
                        workerPidFile = workerPidFile,
                        ownerToken = ownerToken,
                        timeoutMillis = profileDeadlineMillis,
                        cleanupTimeoutMillis = manifest.childProcessCleanupMillis,
                    )
                    if (result.exitCode != 0) {
                        throw GradleException(
                            "high-contention child failed: ${
                                HighContentionChildFailureDiagnostic.describe(
                                    child = child,
                                    outputLog = outputLog,
                                    exitCode = result.exitCode,
                                    secrets = listOf(capability, ownerToken),
                                )
                            }",
                        )
                    }
                    Files.deleteIfExists(workerPidFile)
                    forceDirectory(workerPidFile.parent)
                    HighContentionChildArtifactFinalizer.finalize(runRoot, child)
                    report = reportPath(runRoot, child)
                    cleanupAllocatedResources()
                } catch (error: Throwable) {
                    try {
                        cleanupAllocatedResources()
                    } catch (cleanupFailure: Throwable) {
                        error.addSuppressed(cleanupFailure)
                    } finally {
                        if (!resourcesReleased) {
                            allocator.release(selectedRunId, child.profileId)
                            resourcesReleased = true
                        }
                    }
                    throw error
                }
                val reportExists =
                    Files.isRegularFile(report, NOFOLLOW_LINKS) && !Files.isSymbolicLink(report)
                val status = readStatus(report)
                val errorCode = readErrorCode(report)
                writeChildJournal(
                    runRoot = runRoot,
                    child = child,
                    resources = resources,
                    result = result,
                    parentDeletedResourceCount = parentDeletedResourceCount,
                    parentCleanupSucceeded = parentCleanupSucceeded,
                )
                val gate = HighContentionChildGate(
                    cleanupZeroLive = parentCleanupSucceeded,
                    childProcessesZeroLive = true,
                    artifactValid =
                        reportExists &&
                            !(
                                status == HighContentionChildStatus.PASS &&
                                    parentDeletedResourceCount > 0
                                ),
                    parentCleanupSucceeded = parentCleanupSucceeded,
                    errorContinuable = errorCode !in FATAL_ERROR_CODES,
                )
                val continuation = if (!reportExists || result.timedOut) {
                    HighContentionContinuation.STOP
                } else {
                    HighContentionContinuationPolicy.evaluate(status, gate)
                }
                appendJournal(
                    runJournal,
                    mapOf(
                        "schemaVersion" to 1,
                        "event" to "CHILD_FINISHED",
                        "ordinal" to index,
                        "profileId" to child.profileId,
                        "implementation" to child.implementation,
                        "terminalStatus" to status.name,
                        "cleanupZeroLive" to true,
                        "childProcessesZeroLive" to true,
                        "parentDeletedResourceCount" to parentDeletedResourceCount,
                        "observedProcessCount" to result.observedPids.size,
                        "continuation" to continuation.name,
                    ),
                )
                Files.deleteIfExists(outputLog)
                deleteTree(projectCache)
                when (continuation) {
                    HighContentionContinuation.CONTINUE -> Unit
                    HighContentionContinuation.CONTINUE_WITH_FINAL_FAILURE -> finalFailure = true
                    HighContentionContinuation.STOP -> {
                        throw GradleException("high-contention child failed its continuation gate")
                    }
                }
            }
            appendJournal(
                runJournal,
                mapOf(
                    "schemaVersion" to 1,
                    "event" to "RUN_FINISHED",
                    "result" to if (finalFailure) "FAIL" else "PASS",
                    "maxActiveTopologies" to 1,
                    "cleanupZeroLive" to true,
                ),
            )
        } catch (error: Throwable) {
            primaryFailure = error
            runCatching {
                appendJournal(
                    runJournal,
                    mapOf(
                        "schemaVersion" to 1,
                        "event" to "RUN_FINISHED",
                        "result" to "ERROR",
                        "maxActiveTopologies" to 1,
                        "cleanupZeroLive" to true,
                    ),
                )
            }
        } finally {
            runCatching { deleteTree(runRoot.resolve("internal")) }
            runCatching { Files.deleteIfExists(runRoot.resolve("ticket-topology.jsonl")) }
            try {
                Files.deleteIfExists(
                    runUploadRoot.resolve(HighContentionArtifactValidator.FAILURE_FILE),
                )
                HighContentionArtifactValidator(
                    nodeExecutable = Path.of("node"),
                    script = validationScript.get().asFile.toPath().toRealPath(NOFOLLOW_LINKS),
                    timeoutMillis = manifest.artifactFinalizationMillis,
                ).validate(contract, runRoot, runUploadRoot)
            } catch (validationError: Throwable) {
                if (primaryFailure == null) {
                    primaryFailure = validationError
                } else {
                    primaryFailure.addSuppressed(validationError)
                }
            }
            runCatching { deleteTree(workRoot) }
        }
        primaryFailure?.let {
            throw GradleException("high-contention suite failed", it)
        }
        if (finalFailure) {
            throw GradleException("high-contention suite completed with failing child results")
        }
    }

    private fun childCommand(
        wrapper: Path,
        child: HighContentionChildKey,
        mode: String,
        runId: String,
        projectCache: Path,
        ownerToken: String,
    ): List<String> {
        val task = when (mode) {
            "ci-correctness" -> "highContentionCiProfile"
            "local-reference" -> "highContentionLocalReferenceProfile"
            else -> error("unsupported mode")
        }
        val projectPath = IMPLEMENTATION_PROJECTS[child.implementation]
            ?: throw GradleException("unsupported high-contention implementation")
        return buildList {
            if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) {
                addAll(listOf("cmd.exe", "/d", "/c"))
            }
            add(wrapper.toString())
            add("-D${HighContentionChildProcessRunner.PROCESS_OWNER_PROPERTY}=$ownerToken")
            addAll(HighContentionChildGradleRuntime.arguments)
            add("$projectPath:$task")
            add("--no-daemon")
            add("--no-watch-fs")
            add("--no-configuration-cache")
            add("--max-workers=1")
            add("--console=plain")
            add("--project-cache-dir")
            add(projectCache.toString())
            add("-PhighContentionRunId=$runId")
            add("-PhighContentionProfileId=${child.profileId}")
        }
    }

    private fun writeChildDescriptor(
        runRoot: Path,
        runId: String,
        mode: String,
        child: HighContentionChildKey,
        parentManifestDigest: String,
        resources: List<HighContentionResourceAllocation>,
        capability: String,
    ): Path {
        val descriptorRoot = runRoot.resolve("internal/children")
        createTrustedDirectories(descriptorRoot)
        createTrustedDirectories(workerPidPath(runRoot, child).parent)
        val fields = linkedMapOf<String, Any?>(
            "childDescriptorSchemaVersion" to 1,
            "runId" to runId,
            "profileId" to child.profileId,
            "mode" to mode,
            "implementation" to child.implementation,
            "parentManifestDigest" to parentManifestDigest,
            "parentPid" to ProcessHandle.current().pid(),
            "parentStartEpochMillis" to ProcessHandle.current().info().startInstant()
                .orElseThrow {
                    GradleException("high-contention coordinator start time is unavailable")
                }
                .toEpochMilli(),
            "resourceLabels" to resources.map { resource ->
                mapOf(
                    "resourceKey" to resource.resourceKey,
                    "resourceType" to resource.resourceType,
                    "labels" to resource.labels,
                )
            },
        )
        fields["capabilityDigest"] =
            HighContentionChildDescriptorValidator.capabilityDigest(fields, capability)
        val descriptor = descriptorRoot.resolve(
            "${child.implementation}-${child.profileId}.json",
        )
        writeNoReplace(
            descriptor,
            JsonOutput.toJson(fields).plus("\n").toByteArray(),
        )
        return descriptor
    }

    private fun childCapability(): String =
        ByteArray(32)
            .also(SecureRandom()::nextBytes)
            .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private fun writeChildJournal(
        runRoot: Path,
        child: HighContentionChildKey,
        resources: List<HighContentionResourceAllocation>,
        result: HighContentionChildProcessResult,
        parentDeletedResourceCount: Int,
        parentCleanupSucceeded: Boolean,
    ) {
        val path = childJournalPath(runRoot, child)
        createTrustedDirectories(path.parent)
        writeNoReplace(
            path,
            JsonOutput.toJson(
                mapOf(
                    "schemaVersion" to 1,
                    "event" to "CHILD_TERMINAL",
                    "profileId" to child.profileId,
                    "implementation" to child.implementation,
                    "resources" to resources.map { it.labels },
                    "timedOut" to result.timedOut,
                    "exitCode" to result.exitCode,
                    "cleanupZeroLive" to parentCleanupSucceeded,
                    "parentDeletedResourceCount" to parentDeletedResourceCount,
                    "observedPids" to result.observedPids.sorted(),
                ),
            ).plus("\n").toByteArray(),
        )
    }

    private fun readStatus(report: Path): HighContentionChildStatus {
        if (!Files.isRegularFile(report, NOFOLLOW_LINKS) || Files.isSymbolicLink(report)) {
            return HighContentionChildStatus.ERROR
        }
        val text = Files.readString(report)
        return HighContentionChildStatus.entries.firstOrNull {
            Regex(""""terminalStatus"\s*:\s*"${it.name}"""").containsMatchIn(text)
        } ?: HighContentionChildStatus.ERROR
    }

    private fun readErrorCode(report: Path): String {
        if (!Files.isRegularFile(report, NOFOLLOW_LINKS) || Files.isSymbolicLink(report)) {
            return "MISSING_REPORT"
        }
        return Regex(""""errorCode"\s*:\s*"([A-Z_]+)"""")
            .find(Files.readString(report))
            ?.groupValues
            ?.get(1)
            ?: "INVALID_REPORT"
    }

    private fun reportPath(
        runRoot: Path,
        child: HighContentionChildKey,
    ): Path = runRoot.resolve("reports/${child.implementation}/${child.profileId}.json")

    private fun childJournalPath(
        runRoot: Path,
        child: HighContentionChildKey,
    ): Path =
        runRoot.resolve(
            "children/${child.implementation}/${child.profileId}/child-journal.jsonl",
        )

    private fun workerPidPath(
        runRoot: Path,
        child: HighContentionChildKey,
    ): Path =
        runRoot.resolve(
            "children/${child.implementation}/${child.profileId}/worker.pid",
        )

    private fun runManifestBytes(
        runId: String,
        mode: String,
        workflowRunAndAttempt: String,
        children: List<HighContentionChildKey>,
    ): ByteArray =
        JsonOutput.toJson(
            mapOf(
                "schemaVersion" to 1,
                "runId" to runId,
                "mode" to mode,
                "workflowRunAndAttempt" to workflowRunAndAttempt,
                "expectedChildren" to children.map {
                    mapOf(
                        "profileId" to it.profileId,
                        "implementation" to it.implementation,
                    )
                },
            ),
        ).plus("\n").toByteArray()

    private fun loadManifest(
        contractRoot: Path,
        mode: String,
    ): LoadedManifest {
        val manifest = JsonSlurper().parse(contractRoot.resolve("suite-manifest.json").toFile())
            as? Map<*, *> ?: throw GradleException("high-contention suite manifest is invalid")
        val entries = (manifest["entries"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .filter { it["mode"] == mode }
            .map { entry ->
                HighContentionMatrixEntry(
                    profileId = entry["profileId"] as? String
                        ?: throw GradleException("high-contention profileId is invalid"),
                    implementations = (entry["implementations"] as? List<*>)
                        ?.map {
                            it as? String
                                ?: throw GradleException("high-contention implementation is invalid")
                        }
                        ?: throw GradleException("high-contention implementations are invalid"),
                )
            }
        if (entries.isEmpty()) {
            throw GradleException("high-contention suite manifest has no entries for its mode")
        }
        val cleanupBudgets = manifest["runCleanupActionBudgetsMs"] as? Map<*, *>
            ?: throw GradleException("high-contention run cleanup budgets are invalid")
        return LoadedManifest(
            entries = entries,
            runDeadlineMillis = number(manifest, "runDeadlineMs"),
            runCleanupReserveMillis = number(manifest, "runCleanupReserveMs"),
            childProcessCleanupMillis = number(cleanupBudgets, "childProcesses"),
            dockerDiscoveryMillis = number(cleanupBudgets, "dockerDiscovery"),
            artifactFinalizationMillis = number(cleanupBudgets, "artifactFinalization"),
            dockerCleanupPollIntervalMillis =
                number(manifest, "dockerCleanupPollIntervalMs"),
            dockerCleanupQuietPeriodMillis =
                number(manifest, "dockerCleanupQuietPeriodMs"),
        )
    }

    private fun loadProfileDeadlineMillis(
        contract: Path,
        mode: String,
        profileId: String,
    ): Long {
        val profile = JsonSlurper().parse(
            contract.resolve("profiles/$mode/$profileId.json").toFile(),
        ) as? Map<*, *> ?: throw GradleException("high-contention profile is invalid")
        return number(profile, "profileDeadlineMs")
    }

    private fun number(
        fields: Map<*, *>,
        name: String,
    ): Long {
        val value = (fields[name] as? Number)?.toLong()
            ?: throw GradleException("$name is invalid")
        if (value <= 0) {
            throw GradleException("$name must be positive")
        }
        return value
    }

    private data class LoadedManifest(
        val entries: List<HighContentionMatrixEntry>,
        val runDeadlineMillis: Long,
        val runCleanupReserveMillis: Long,
        val childProcessCleanupMillis: Long,
        val dockerDiscoveryMillis: Long,
        val artifactFinalizationMillis: Long,
        val dockerCleanupPollIntervalMillis: Long,
        val dockerCleanupQuietPeriodMillis: Long,
    )

    private companion object {
        val IMPLEMENTATION_PROJECTS = mapOf(
            "job-core" to ":operations-job-console-core",
            "job-spring" to ":operations-job-console-spring",
            "job-ktor" to ":operations-job-console-ktor",
            "ticket-spring" to ":commerce-concert-ticket-flash-sale",
        )
        val FATAL_ERROR_CODES = setOf(
            "PARENT_CLEANUP_ERROR",
            "JOURNAL_ERROR",
            "REPORT_SERIALIZATION",
            "MISSING_REPORT",
            "INVALID_REPORT",
        )
    }
}

private fun appendJournal(
    path: Path,
    record: Map<String, Any?>,
) {
    val bytes = JsonOutput.toJson(record).plus("\n").toByteArray()
    FileChannel.open(path, APPEND, WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
        channel.force(true)
    }
}

private fun writeNoReplace(
    path: Path,
    bytes: ByteArray,
) {
    createTrustedDirectories(path.parent)
    val temporary = path.parent.resolve(".${path.fileName}.tmp")
    FileChannel.open(temporary, CREATE_NEW, WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
        channel.force(true)
    }
    try {
        try {
            Files.move(temporary, path, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path)
        }
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw IllegalStateException("high-contention artifact did not remain a regular file")
        }
        forceDirectory(path.parent)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun createTrustedDirectories(path: Path) {
    val absolute = path.toAbsolutePath().normalize()
    var current = absolute.root
    absolute.forEach { component ->
        current = current.resolve(component)
        if (Files.exists(current, NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(current, NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                throw IllegalStateException("high-contention path contains an untrusted parent")
            }
        } else {
            Files.createDirectory(current)
            forceDirectory(current.parent)
        }
    }
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) {
        return
    }
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun hmacSha256(
    key: String,
    bytes: ByteArray,
): String =
    Mac.getInstance("HmacSHA256")
        .apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        .doFinal(bytes)
        .joinToString("") { "%02x".format(it) }

private fun forceDirectory(path: Path) {
    try {
        FileChannel.open(path, READ).use { it.force(true) }
    } catch (_: UnsupportedOperationException) {
        // Directory fsync is not supported by every file provider.
    }
}
