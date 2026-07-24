import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

internal interface HighContentionProcessRef {
    val identity: Any
    val pid: Long
    val isAlive: Boolean
    val descendants: List<HighContentionProcessRef>

    fun destroy()

    fun destroyForcibly()
}

internal class HighContentionProcessReaper(
    private val discoverOwned: () -> List<HighContentionProcessRef> = { emptyList() },
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepMillis: (Long) -> Unit = Thread::sleep,
) {

    fun reap(
        rootProcesses: Collection<HighContentionProcessRef>,
        gracefulTimeoutMillis: Long,
        absoluteDeadlineNanos: Long,
        quietPeriodMillis: Long,
        pollIntervalMillis: Long,
    ): Set<Long> {
        if (rootProcesses.isEmpty()) {
            throw IllegalArgumentException("rootProcesses must not be empty")
        }
        if (gracefulTimeoutMillis < 0) {
            throw IllegalArgumentException("gracefulTimeoutMillis must not be negative")
        }
        if (quietPeriodMillis <= 0) {
            throw IllegalArgumentException("quietPeriodMillis must be positive")
        }
        if (pollIntervalMillis <= 0) {
            throw IllegalArgumentException("pollIntervalMillis must be positive")
        }

        val knownProcesses = linkedMapOf<Any, HighContentionProcessRef>()
        rootProcesses.forEach { process -> knownProcesses.putIfAbsent(process.identity, process) }
        val observedPids = rootProcesses.mapTo(mutableSetOf(), HighContentionProcessRef::pid)
        val gracefulRequested = mutableSetOf<Any>()
        val forceRequested = mutableSetOf<Any>()
        val gracefulDeadlineNanos = minOf(
            absoluteDeadlineNanos,
            Math.addExact(nanoTime(), TimeUnit.MILLISECONDS.toNanos(gracefulTimeoutMillis)),
        )
        var quietSinceNanos: Long? = null

        while (nanoTime() < absoluteDeadlineNanos) {
            discoverProcesses(knownProcesses, observedPids)
            val liveProcesses = knownProcesses.values
                .filter(HighContentionProcessRef::isAlive)

            if (liveProcesses.isEmpty()) {
                val quietStart = quietSinceNanos ?: nanoTime().also { quietSinceNanos = it }
                if (nanoTime() - quietStart >= TimeUnit.MILLISECONDS.toNanos(quietPeriodMillis)) {
                    return observedPids
                }
            } else {
                quietSinceNanos = null
                val force = nanoTime() >= gracefulDeadlineNanos
                liveProcesses
                    .asReversed()
                    .forEach { process ->
                        if (force && forceRequested.add(process.identity)) {
                            process.destroyForcibly()
                        } else if (!force && gracefulRequested.add(process.identity)) {
                            process.destroy()
                        }
                    }
            }
            sleepMillis(pollIntervalMillis)
        }

        discoverProcesses(knownProcesses, observedPids)
        val survivors = knownProcesses.values
            .filter(HighContentionProcessRef::isAlive)
            .map(HighContentionProcessRef::pid)
            .sorted()
        if (survivors.isNotEmpty()) {
            throw IllegalStateException("owned process cleanup deadline expired with ${survivors.size} survivor(s)")
        }
        throw IllegalStateException("owned processes did not remain at stable zero before cleanup deadline")
    }

    private fun discoverProcesses(
        knownProcesses: MutableMap<Any, HighContentionProcessRef>,
        observedPids: MutableSet<Long>,
    ) {
        val queue = ArrayDeque<HighContentionProcessRef>()
        discoverOwned().forEach { process ->
            observedPids += process.pid
            knownProcesses.putIfAbsent(process.identity, process)
        }
        knownProcesses.values.forEach(queue::addLast)
        while (queue.isNotEmpty()) {
            val process = queue.removeFirst()
            process.descendants.forEach { descendant ->
                observedPids += descendant.pid
                if (knownProcesses.putIfAbsent(descendant.identity, descendant) == null) {
                    queue.addLast(descendant)
                }
            }
        }
    }
}

internal class JdkHighContentionProcessRef(
    private val delegate: ProcessHandle,
) : HighContentionProcessRef {

    override val identity: Any
        get() = delegate

    override val pid: Long
        get() = delegate.pid()

    override val isAlive: Boolean
        get() = delegate.isAlive

    override val descendants: List<HighContentionProcessRef>
        get() = delegate.descendants()
            .map(::JdkHighContentionProcessRef)
            .toList()

    override fun destroy() {
        delegate.destroy()
    }

    override fun destroyForcibly() {
        delegate.destroyForcibly()
    }
}

internal fun discoverHighContentionOwnedProcesses(
    propertyName: String,
    ownerToken: String,
): List<HighContentionProcessRef> {
    val ownerArgument = "-D$propertyName=$ownerToken"
    return ProcessHandle.allProcesses()
        .filter { process ->
            val info = process.info()
            info.arguments()
                .map { arguments -> arguments.any { it == ownerArgument } }
                .orElse(false) ||
                info.commandLine()
                    .map { commandLine -> commandLine.contains(ownerArgument) }
                    .orElse(false)
        }
        .map(::JdkHighContentionProcessRef)
        .toList()
}

internal fun findHighContentionOwnedProcess(
    pid: Long,
    propertyName: String,
    ownerToken: String,
): HighContentionProcessRef? {
    val process = ProcessHandle.of(pid).orElse(null) ?: return null
    val ownerArgument = "-D$propertyName=$ownerToken"
    val info = process.info()
    val owned = info.arguments()
        .map { arguments -> arguments.any { it == ownerArgument } }
        .orElse(false) ||
        info.commandLine()
            .map { commandLine -> commandLine.contains(ownerArgument) }
            .orElse(false)
    return if (owned) JdkHighContentionProcessRef(process) else null
}

@DisableCachingByDefault(because = "This verification task intentionally creates and reaps a live nested build.")
abstract class HighContentionProcessProbeTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val gradleWrapper: RegularFileProperty

    @get:Input
    abstract val childTaskPath: Property<String>

    @get:Internal
    abstract val probeBaseDirectory: DirectoryProperty

    @get:Input
    abstract val startupTimeoutMillis: Property<Long>

    @get:Input
    abstract val probeDurationMillis: Property<Long>

    @get:Input
    abstract val cleanupTimeoutMillis: Property<Long>

    init {
        startupTimeoutMillis.convention(30_000)
        probeDurationMillis.convention(1_000)
        cleanupTimeoutMillis.convention(15_000)
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun probe() {
        val repository = repositoryRoot.get().asFile.toPath().toRealPath()
        val wrapper = gradleWrapper.get().asFile.toPath().toRealPath()
        val configuredBaseDirectory = probeBaseDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        Files.createDirectories(configuredBaseDirectory)
        if (Files.isSymbolicLink(configuredBaseDirectory)) {
            throw GradleException("process probe base directory must not be a symbolic link")
        }
        val baseDirectory = configuredBaseDirectory.toRealPath(NOFOLLOW_LINKS)
        if (!baseDirectory.startsWith(repository)) {
            throw GradleException("process probe base directory escaped the repository")
        }
        requirePositiveTimeout(startupTimeoutMillis.get(), "startupTimeoutMillis")
        requirePositiveTimeout(probeDurationMillis.get(), "probeDurationMillis")
        requirePositiveTimeout(cleanupTimeoutMillis.get(), "cleanupTimeoutMillis")
        val probeId = UUID.randomUUID().toString().replace("-", "").lowercase(Locale.ROOT)
        val probeRoot = Files.createDirectory(baseDirectory.resolve("probe-$probeId"))
        val descriptor = probeRoot.resolve("probe.descriptor")
        val projectCache = Files.createDirectory(probeRoot.resolve("project-cache"))
        val outputLog = probeRoot.resolve("nested-gradle.log")
        val wrapperPidFile = probeRoot.resolve("wrapper.pid")
        val workerPidFile = probeRoot.resolve("worker.pid")
        val descendantPidFile = probeRoot.resolve("descendant.pid")
        val processJournalFile = probeRoot.resolve("process-journal.log")
        val readyFile = probeRoot.resolve("probe.ready")
        val descriptorDeadlineEpochMillis = Math.addExact(
            System.currentTimeMillis(),
            Math.addExact(startupTimeoutMillis.get(), probeDurationMillis.get()),
        )
        writeCreateNewAndForce(
            descriptor,
            buildString {
                appendLine("schemaVersion=1")
                appendLine("probeId=$probeId")
                appendLine("probeRoot=$probeRoot")
                appendLine("deadlineEpochMillis=$descriptorDeadlineEpochMillis")
            },
        )

        val command = mutableListOf<String>()
        if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) {
            command += listOf("cmd.exe", "/d", "/c")
        }
        command += wrapper.toString()
        command += "-D$PROCESS_OWNER_PROPERTY=$probeId"
        val internalChildTaskPath = childTaskPath.get()
        if (internalChildTaskPath.substringAfterLast(':') == name) {
            throw GradleException("process probe must not recursively invoke its public task")
        }
        command += internalChildTaskPath
        command += "--no-daemon"
        command += "--no-watch-fs"
        command += "--no-configuration-cache"
        command += "--max-workers=1"
        command += "--console=plain"
        command += "--project-cache-dir"
        command += projectCache.toString()
        command += "-PhighContentionProbeDescriptor=$descriptor"

        val nestedBuild = ProcessBuilder(command)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputLog.toFile())
            .start()
        val ownedProcesses = mutableListOf<HighContentionProcessRef>(
            JdkHighContentionProcessRef(nestedBuild.toHandle()),
        )
        var primaryFailure: Throwable? = null
        try {
            writeCreateNewAndForce(wrapperPidFile, "${nestedBuild.pid()}\n")
            awaitReady(
                nestedBuild = nestedBuild,
                readyFile = readyFile,
                absoluteDeadlineNanos = deadlineAfter(startupTimeoutMillis.get()),
            )
            val workerPid = readBoundedPid(workerPidFile)
            val descendantPid = readBoundedPid(descendantPidFile)
            requireExpectedProcessJournal(processJournalFile, workerPid, descendantPid)
            val observedDescendants = nestedBuild.toHandle()
                .descendants()
                .toList()
                .associateBy(ProcessHandle::pid)
            val workerProcess = observedDescendants[workerPid]
            val descendantProcess = observedDescendants[descendantPid]
            if (workerProcess == null || descendantProcess == null) {
                throw GradleException("probe worker or descendant is outside the owned nested-build tree")
            }
            ownedProcesses += JdkHighContentionProcessRef(workerProcess)
            ownedProcesses += JdkHighContentionProcessRef(descendantProcess)

            val probeDeadline = deadlineAfter(probeDurationMillis.get())
            while (System.nanoTime() < probeDeadline) {
                if (!nestedBuild.isAlive) {
                    throw GradleException("nested probe exited before the parent timeout")
                }
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
        } catch (error: Throwable) {
            primaryFailure = error
        } finally {
            try {
                val reaper = HighContentionProcessReaper(
                    discoverOwned = { discoverOwnedProcesses(probeId) },
                )
                reaper.reap(
                    rootProcesses = ownedProcesses,
                    gracefulTimeoutMillis = GRACEFUL_TIMEOUT_MILLIS,
                    absoluteDeadlineNanos = deadlineAfter(cleanupTimeoutMillis.get()),
                    quietPeriodMillis = STABLE_ZERO_QUIET_PERIOD_MILLIS,
                    pollIntervalMillis = POLL_INTERVAL_MILLIS,
                )
            } catch (cleanupError: Throwable) {
                if (primaryFailure == null) {
                    primaryFailure = cleanupError
                } else {
                    primaryFailure.addSuppressed(cleanupError)
                }
            }
        }

        primaryFailure?.let { throw GradleException("high-contention process probe failed", it) }
        if (Files.exists(descriptor)) {
            throw GradleException("internal child task did not consume its one-shot descriptor")
        }
        requireNoHeldLocks(projectCache)
        writeCreateNewAndForce(probeRoot.resolve("probe.success"), "PASS\n")
        logger.lifecycle(
            "High-contention process probe passed: worker and descendant observed, timed out, and reaped.",
        )
    }

    private fun awaitReady(
        nestedBuild: Process,
        readyFile: Path,
        absoluteDeadlineNanos: Long,
    ) {
        while (System.nanoTime() < absoluteDeadlineNanos) {
            if (Files.isRegularFile(readyFile)) {
                return
            }
            if (!nestedBuild.isAlive) {
                throw GradleException("nested probe exited before publishing readiness")
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw GradleException("nested probe did not become ready before the startup deadline")
    }

    private fun readBoundedPid(path: Path): Long {
        if (!Files.isRegularFile(path) || Files.size(path) !in 1..32) {
            throw GradleException("${path.name} is missing or outside its bounded size")
        }
        val value = Files.readString(path).trim()
        if (!PID_REGEX.matches(value)) {
            throw GradleException("${path.name} does not contain a valid PID")
        }
        return value.toLong()
    }

    private fun requireNoHeldLocks(projectCache: Path) {
        if (!Files.exists(projectCache)) {
            return
        }
        Files.walk(projectCache).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".lock") }
                .forEach { lockFile ->
                    try {
                        FileChannel.open(lockFile, READ, WRITE).use { channel ->
                            val lock = channel.tryLock()
                                ?: throw GradleException("nested project-cache lock is still held")
                            lock.release()
                        }
                    } catch (error: OverlappingFileLockException) {
                        throw GradleException("nested project-cache lock is still held", error)
                    }
                }
        }
    }

    private fun requireExpectedProcessJournal(
        journalPath: Path,
        workerPid: Long,
        descendantPid: Long,
    ) {
        if (!Files.isRegularFile(journalPath) || Files.size(journalPath) !in 1..256) {
            throw GradleException("process journal is missing or outside its bounded size")
        }
        val records = Files.readAllLines(journalPath)
        val expected = listOf("WORKER $workerPid", "DESCENDANT $descendantPid")
        if (records != expected) {
            throw GradleException("process journal does not match the observed owned process tree")
        }
    }

    private fun discoverOwnedProcesses(probeId: String): List<HighContentionProcessRef> {
        return discoverHighContentionOwnedProcesses(PROCESS_OWNER_PROPERTY, probeId)
    }

    private fun writeCreateNewAndForce(path: Path, content: String) {
        FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        FileChannel.open(path.parent, READ).use { channel ->
            try {
                channel.force(true)
            } catch (_: UnsupportedOperationException) {
                // Directory fsync is not supported by every file provider.
            }
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long =
        Math.addExact(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMillis))

    private fun requirePositiveTimeout(timeoutMillis: Long, name: String) {
        if (timeoutMillis <= 0) {
            throw GradleException("$name must be positive")
        }
    }

    private companion object {
        val PID_REGEX = Regex("[1-9][0-9]{0,18}")
        const val GRACEFUL_TIMEOUT_MILLIS = 500L
        const val STABLE_ZERO_QUIET_PERIOD_MILLIS = 500L
        const val POLL_INTERVAL_MILLIS = 25L
        const val PROCESS_OWNER_PROPERTY = "highContentionProcessOwner"
    }
}
