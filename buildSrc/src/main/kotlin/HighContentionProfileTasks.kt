import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val HIGH_CONTENTION_CHILD_DESCRIPTOR_ENV =
    "BLUETAPE_HIGH_CONTENTION_CHILD_DESCRIPTOR"
internal const val HIGH_CONTENTION_CHILD_CAPABILITY_ENV =
    "BLUETAPE_HIGH_CONTENTION_CHILD_CAPABILITY"

internal data class HighContentionProfileSelection(
    val runId: String,
    val profileId: String,
) {
    companion object {
        private val identifierPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        fun validate(
            runId: String,
            profileId: String,
            allowedProfiles: Set<String>,
        ): HighContentionProfileSelection {
            require(runId.isNotBlank()) {
                "-PhighContentionRunId=<value> is required"
            }
            require(identifierPattern.matches(runId) && runId != "." && runId != "..") {
                "highContentionRunId must be a bounded identifier"
            }
            require(profileId in allowedProfiles) {
                "unsupported highContentionProfileId"
            }
            return HighContentionProfileSelection(runId, profileId)
        }
    }
}

internal data class HighContentionTaskIdentity(
    val mode: String,
    val implementation: String,
    val profileClass: String,
) {
    companion object {
        fun derive(
            projectPath: String,
            taskName: String,
        ): HighContentionTaskIdentity {
            val mode = when (taskName) {
                "highContentionCiProfile" -> "ci-correctness"
                "highContentionLocalReferenceProfile" -> "local-reference"
                else -> throw IllegalArgumentException("unsupported high-contention task name")
            }
            val implementation = IMPLEMENTATIONS[projectPath]
                ?: throw IllegalArgumentException("unsupported high-contention project path")
            return HighContentionTaskIdentity(
                mode = mode,
                implementation = implementation.first,
                profileClass = implementation.second,
            )
        }

        private val IMPLEMENTATIONS = mapOf(
            ":operations-job-console-core" to (
                "job-core" to
                    "io.bluetape4k.workshop.operations.jobconsole.highcontention." +
                    "JobConsoleCoreHighContentionProfileTest"
                ),
            ":operations-job-console-spring" to (
                "job-spring" to
                    "io.bluetape4k.workshop.operations.jobconsole.spring." +
                    "SpringJobConsoleHighContentionProfileTest"
                ),
            ":operations-job-console-ktor" to (
                "job-ktor" to
                    "io.bluetape4k.workshop.operations.jobconsole.ktor." +
                    "KtorJobConsoleHighContentionProfileTest"
                ),
            ":commerce-concert-ticket-flash-sale" to (
                "ticket-spring" to
                    "io.bluetape4k.workshop.commerce.ticket.highcontention." +
                    "TicketHighContentionProfileTest"
                ),
        )
    }
}

internal object HighContentionCallerBoundary {
    private val allowedProjectProperties = setOf(
        "highContentionRunId",
        "highContentionProfileId",
    )
    private val reservedChildSystemProperties = setOf(
        "highContentionContractRoot",
        "highContentionOutputRoot",
        "highContentionRunId",
        "highContentionProfileId",
        "highContentionMode",
        "highContentionImplementation",
        "highContentionWorkflowRunAndAttempt",
        "highContentionExpectedJavaVersion",
        "highContentionParentOwnedRun",
        "highContentionProcessOwner",
        "highContentionWorkerPidFile",
    )

    fun validate(
        projectPropertyNames: Set<String>,
        systemPropertyNames: Set<String>,
        environmentNames: Set<String>,
        allowChildChannel: Boolean = false,
    ) {
        val forbiddenProjectProperty = projectPropertyNames.firstOrNull {
            it.startsWith("highContention") && it !in allowedProjectProperties
        }
        if (forbiddenProjectProperty != null) {
            throw GradleException("caller supplied a reserved high-contention project property")
        }

        val forbiddenSystemProperty = systemPropertyNames.firstOrNull {
            it.startsWith("high.contention.") ||
                (
                    it in reservedChildSystemProperties &&
                        !(
                            allowChildChannel &&
                                it in setOf(
                                    "highContentionProcessOwner",
                                    "highContentionWorkerPidFile",
                                )
                            )
                    )
        }
        if (forbiddenSystemProperty != null) {
            throw GradleException("caller supplied a reserved high-contention system property")
        }

        val forbiddenEnvironment = environmentNames.firstOrNull {
            isReservedEnvironment(it) &&
                !(
                    allowChildChannel &&
                        it in setOf(
                            HIGH_CONTENTION_CHILD_DESCRIPTOR_ENV,
                            HIGH_CONTENTION_CHILD_CAPABILITY_ENV,
                        )
                    )
        }
        if (forbiddenEnvironment != null) {
            throw GradleException("caller supplied a reserved high-contention environment channel")
        }
    }

    private fun isReservedEnvironment(name: String): Boolean =
        name.startsWith("HIGH_CONTENTION_") ||
            name.startsWith("BLUETAPE_HIGH_CONTENTION_")
}

internal object HighContentionSourceState {
    fun requireClean(repositoryRoot: Path) {
        val process = ProcessBuilder(
            "git",
            "-C",
            repositoryRoot.toAbsolutePath().normalize().toString(),
            "status",
            "--porcelain",
            "--untracked-files=normal",
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("high-contention source state could not be verified")
        }
        if (output.isNotBlank()) {
            throw GradleException("high-contention source worktree must be clean")
        }
    }
}

fun Project.registerHighContentionProfileTask(
    name: String,
): TaskProvider<Test> {
    val identity = HighContentionTaskIdentity.derive(path, name)
    val contractRoot = rootProject.layout.projectDirectory.dir("profiles/high-contention/v1")
    val outputRoot = rootProject.layout.buildDirectory.dir("reports/high-contention")
    val runId = providers.gradleProperty("highContentionRunId").orElse("")
    val profileId = providers.gradleProperty("highContentionProfileId").orElse("burst")
    val descriptorPath = providers.environmentVariable(HIGH_CONTENTION_CHILD_DESCRIPTOR_ENV)
    val childCapability = providers.environmentVariable(HIGH_CONTENTION_CHILD_CAPABILITY_ENV)
    val javaToolchains = extensions.getByType<JavaToolchainService>()
    val prefixedProjectProperties =
        providers.gradlePropertiesPrefixedBy("highContention")
    val prefixedDottedSystemProperties =
        providers.systemPropertiesPrefixedBy("high.contention.")
    val prefixedCamelSystemProperties =
        providers.systemPropertiesPrefixedBy("highContention")
    val prefixedPublicEnvironment =
        providers.environmentVariablesPrefixedBy("HIGH_CONTENTION_")
    val prefixedInternalEnvironment =
        providers.environmentVariablesPrefixedBy("BLUETAPE_HIGH_CONTENTION_")
    val repositoryRoot = rootProject.projectDir.toPath().toAbsolutePath().normalize()
    val workflowOwnedCi =
        providers.environmentVariable("CI").orNull == "true" &&
            providers.environmentVariable("GITHUB_ACTIONS").orNull == "true"

    return tasks.register<Test>(name) {
        description =
            "Runs the isolated ${identity.mode} high-contention profile for ${identity.implementation}."
        group = "verification"
        inputs.dir(contractRoot)
        inputs.property("highContentionRunId", runId)
        inputs.property("highContentionProfileId", profileId)
        outputs.file(
            runId.zip(profileId) { selectedRunId, selectedProfileId ->
                val selectedRunRoot = outputRoot.get().asFile.resolve(selectedRunId)
                if (identity.implementation == "ticket-spring") {
                    selectedRunRoot.resolve(
                        "${identity.implementation}-$selectedProfileId-report.json",
                    )
                } else {
                    selectedRunRoot.resolve(
                        "reports/${identity.implementation}/$selectedProfileId.json",
                    )
                }
            },
        )
        outputs.upToDateWhen { false }
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        })
        maxParallelForks = 1
        forkEvery = 1
        useJUnitPlatform {
            includeTags("high-contention")
        }
        filter {
            includeTestsMatching(identity.profileClass)
            isFailOnNoMatchingTests = true
        }
        systemProperty("junit.jupiter.execution.parallel.enabled", "false")
        rootProject.gradle.sharedServices.registrations.findByName("test-mutex")
            ?.let { usesService(it.service) }

        doFirst {
            val internalDescriptor = descriptorPath.orNull
            val internalCapability = childCapability.orNull
            val internalProcessOwner =
                providers.systemProperty("highContentionProcessOwner").orNull
            val childChannelPresent = internalDescriptor != null || internalCapability != null
            HighContentionCallerBoundary.validate(
                projectPropertyNames = prefixedProjectProperties.get().keys,
                systemPropertyNames =
                    prefixedDottedSystemProperties.get().keys +
                        prefixedCamelSystemProperties.get().keys,
                environmentNames =
                    prefixedPublicEnvironment.get().keys +
                        prefixedInternalEnvironment.get().keys,
                allowChildChannel = internalDescriptor != null && internalCapability != null,
            )
            if (childChannelPresent && (internalDescriptor == null || internalCapability == null)) {
                throw GradleException("high-contention child capability channel is incomplete")
            }
            val selection = HighContentionProfileSelection.validate(
                runId = runId.get(),
                profileId = profileId.get(),
                allowedProfiles = loadAllowedProfiles(contractRoot.asFile.toPath(), identity.mode),
            )
            if (javaLauncher.get().metadata.languageVersion.asInt() != 25) {
                throw GradleException("high-contention profiles require a Java 25 launcher")
            }

            val trustedOutputRoot = outputRoot.get().asFile.toPath().toAbsolutePath().normalize()
            val selectedRunRoot = trustedOutputRoot.resolve(selection.runId)
            val parentOwnedRun = if (internalDescriptor == null) {
                requireAbsentRunRoot(selectedRunRoot)
                false
            } else {
                HighContentionChildDescriptorValidator.validate(
                    descriptorValue = internalDescriptor,
                    outputRoot = trustedOutputRoot,
                    runRoot = selectedRunRoot,
                    selection = selection,
                    identity = identity,
                    capability = requireNotNull(internalCapability),
                )
                if (internalProcessOwner != hmacSha256(
                        key = internalCapability,
                        bytes = Files.readAllBytes(Path.of(internalDescriptor)),
                    )
                ) {
                    throw GradleException(
                        "high-contention process owner does not match its child descriptor",
                    )
                }
                Files.delete(Path.of(internalDescriptor))
                forceDirectory(Path.of(internalDescriptor).parent)
                true
            }

            if (
                identity.mode == "local-reference" ||
                (identity.mode == "ci-correctness" && workflowOwnedCi)
            ) {
                HighContentionSourceState.requireClean(repositoryRoot)
            }

            systemProperty("highContentionContractRoot", contractRoot.asFile.absolutePath)
            systemProperty("highContentionOutputRoot", trustedOutputRoot.toString())
            systemProperty("highContentionRunId", selection.runId)
            systemProperty("highContentionProfileId", selection.profileId)
            systemProperty("highContentionMode", identity.mode)
            systemProperty("highContentionImplementation", identity.implementation)
            systemProperty(
                "highContentionWorkflowRunAndAttempt",
                HighContentionWorkflowIdentity.fromRunId(selection.runId),
            )
            systemProperty("highContentionExpectedJavaVersion", "25")
            systemProperty(
                "highContentionParentOwnedRun",
                parentOwnedRun.toString(),
            )
            internalProcessOwner?.let {
                systemProperty("highContentionProcessOwner", it)
                val workerPidFile = trustedOutputRoot.resolve(
                    "${selection.runId}/children/${identity.implementation}/" +
                        "${selection.profileId}/worker.pid",
                )
                requireAbsentContainedTarget(selectedRunRoot.toRealPath(NOFOLLOW_LINKS), workerPidFile)
                systemProperty("highContentionWorkerPidFile", workerPidFile.toString())
            }
        }

        doLast {
            if (systemProperties["highContentionParentOwnedRun"] == "false") {
                val directRunRoot = outputRoot.get().asFile.toPath().resolve(runId.get())
                writeDirectChildManifest(
                    runRoot = directRunRoot,
                    selection = HighContentionProfileSelection.validate(
                        runId = runId.get(),
                        profileId = profileId.get(),
                        allowedProfiles = loadAllowedProfiles(
                            contractRoot.asFile.toPath(),
                            identity.mode,
                        ),
                    ),
                    identity = identity,
                )
            }
        }
    }
}

private fun loadAllowedProfiles(
    contractRoot: Path,
    mode: String,
): Set<String> {
    val manifest = contractRoot.resolve("suite-manifest.json")
    if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS) || Files.isSymbolicLink(manifest)) {
        throw GradleException("high-contention suite manifest is missing")
    }
    val root = JsonSlurper().parse(manifest.toFile()) as? Map<*, *>
        ?: throw GradleException("high-contention suite manifest is invalid")
    val entries = root["entries"] as? List<*>
        ?: throw GradleException("high-contention suite manifest entries are invalid")
    return entries.mapNotNull { entry ->
        val fields = entry as? Map<*, *> ?: return@mapNotNull null
        if (fields["mode"] == mode) fields["profileId"] as? String else null
    }.toSet().also {
        if (it.isEmpty()) {
            throw GradleException("high-contention suite manifest has no profiles for the task mode")
        }
    }
}

private fun requireAbsentRunRoot(runRoot: Path) {
    if (Files.exists(runRoot, NOFOLLOW_LINKS)) {
        throw GradleException("high-contention run directory already exists")
    }
}

private fun writeDirectChildManifest(
    runRoot: Path,
    selection: HighContentionProfileSelection,
    identity: HighContentionTaskIdentity,
) {
    if (!Files.isDirectory(runRoot, NOFOLLOW_LINKS) || Files.isSymbolicLink(runRoot)) {
        throw GradleException("high-contention profile did not create its run directory")
    }
    val report = if (identity.implementation == "ticket-spring") {
        runRoot.resolve("${identity.implementation}-${selection.profileId}-report.json")
    } else {
        runRoot.resolve("reports/${identity.implementation}/${selection.profileId}.json")
    }
    if (!Files.isRegularFile(report, NOFOLLOW_LINKS) || Files.isSymbolicLink(report)) {
        throw GradleException("high-contention profile did not create its terminal report")
    }

    val manifest = runRoot.resolve("single-child-manifest.json")
    val bytes = """
        {
          "schemaVersion": 1,
          "runId": "${selection.runId}",
          "profileId": "${selection.profileId}",
          "mode": "${identity.mode}",
          "implementation": "${identity.implementation}",
          "report": "${runRoot.relativize(report)}"
        }
    """.trimIndent().plus("\n").toByteArray()
    try {
        FileChannel.open(manifest, CREATE_NEW, WRITE).use { channel ->
            var buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        forceDirectory(runRoot)
    } catch (error: FileAlreadyExistsException) {
        throw GradleException("high-contention child manifest must not replace an existing file", error)
    }
}

internal object HighContentionChildDescriptorValidator {
    private val allowedFields = setOf(
        "childDescriptorSchemaVersion",
        "runId",
        "profileId",
        "mode",
        "implementation",
        "parentManifestDigest",
        "resourceLabels",
        "parentPid",
        "parentStartEpochMillis",
        "capabilityDigest",
    )
    private val requiredLabelKeys = setOf(
        "io.bluetape4k.high-contention.run-id",
        "io.bluetape4k.high-contention.profile-id",
        "io.bluetape4k.high-contention.resource-key",
        "io.bluetape4k.high-contention.resource-type",
    )
    private val expectedResources = mapOf(
        "network" to "network",
        "redis" to "container",
        "toxiproxy" to "container",
    )

    fun validate(
        descriptorValue: String,
        outputRoot: Path,
        runRoot: Path,
        selection: HighContentionProfileSelection,
        identity: HighContentionTaskIdentity,
        capability: String,
    ) {
        val trustedOutputRoot = requireTrustedDirectory(outputRoot)
        val trustedRunRoot = requireExistingChildDirectory(trustedOutputRoot, runRoot)
        val descriptor = Path.of(descriptorValue).toAbsolutePath().normalize()
        val descriptorParent = trustedRunRoot.resolve("internal/children").normalize()
        requireContainedRegularFile(
            root = trustedRunRoot,
            expectedParent = descriptorParent,
            target = descriptor,
            maximumSize = 65_536,
            description = "high-contention child descriptor",
        )
        val fields = JsonSlurper().parse(descriptor.toFile()) as? Map<*, *>
            ?: throw GradleException("high-contention child descriptor is invalid")
        if (fields.keys != allowedFields) {
            throw GradleException("high-contention child descriptor fields are invalid")
        }
        requireDescriptorTuple(fields, selection, identity)
        requireParentIdentity(fields)
        requireCapabilityDigest(fields, capability)
        requireParentManifestDigest(fields, trustedRunRoot)
        requireResourceLabels(fields, selection)

        val childJournal = trustedRunRoot.resolve(
            "children/${identity.implementation}/${selection.profileId}/child-journal.jsonl",
        )
        val report = if (identity.implementation == "ticket-spring") {
            trustedRunRoot.resolve(
                "${identity.implementation}-${selection.profileId}-report.json",
            )
        } else {
            trustedRunRoot.resolve(
                "reports/${identity.implementation}/${selection.profileId}.json",
            )
        }
        val workerPidFile = trustedRunRoot.resolve(
            "children/${identity.implementation}/${selection.profileId}/worker.pid",
        )
        requireAbsentContainedTarget(trustedRunRoot, workerPidFile)
        requireAbsentContainedTarget(trustedRunRoot, childJournal)
        requireAbsentContainedTarget(trustedRunRoot, report)
    }

    private fun requireDescriptorTuple(
        fields: Map<*, *>,
        selection: HighContentionProfileSelection,
        identity: HighContentionTaskIdentity,
    ) {
        if (
            (fields["childDescriptorSchemaVersion"] as? Number)?.toInt() != 1 ||
            fields["runId"] != selection.runId ||
            fields["profileId"] != selection.profileId ||
            fields["mode"] != identity.mode ||
            fields["implementation"] != identity.implementation
        ) {
            throw GradleException("high-contention child descriptor tuple does not match the task")
        }
    }

    private fun requireParentIdentity(fields: Map<*, *>) {
        val expectedPid = (fields["parentPid"] as? Number)?.toLong()
            ?: throw GradleException("high-contention child parent pid is missing")
        val expectedStart = (fields["parentStartEpochMillis"] as? Number)?.toLong()
            ?: throw GradleException("high-contention child parent start time is missing")
        val coordinator = generateSequence(ProcessHandle.current().parent().orElse(null)) { process ->
            process.parent().orElse(null)
        }
            .take(MAX_COORDINATOR_ANCESTOR_DEPTH)
            .firstOrNull { process ->
                process.isAlive &&
                    process.pid() == expectedPid &&
                    process.info().startInstant().orElse(null)?.toEpochMilli() == expectedStart
            }
        if (coordinator == null) {
            throw GradleException("high-contention child capability is not bound to its live parent")
        }
    }

    private fun requireCapabilityDigest(
        fields: Map<*, *>,
        capability: String,
    ) {
        if (capability.length !in 32..256) {
            throw GradleException("high-contention child capability is invalid")
        }
        val supplied = fields["capabilityDigest"] as? String
            ?: throw GradleException("high-contention child capability digest is missing")
        if (supplied != capabilityDigest(fields, capability)) {
            throw GradleException("high-contention child capability digest does not match")
        }
    }

    fun capabilityDigest(
        fields: Map<*, *>,
        capability: String,
    ): String {
        val canonical = buildString {
            allowedFields.filterNot { it == "capabilityDigest" }.sorted().forEach { key ->
                append(key).append('=').append(canonicalValue(fields[key])).append('\n')
            }
        }
        return hmacSha256(capability, canonical.toByteArray())
    }

    private fun requireParentManifestDigest(
        fields: Map<*, *>,
        runRoot: Path,
    ) {
        val supplied = fields["parentManifestDigest"] as? String
            ?: throw GradleException("high-contention parent manifest digest is missing")
        val parentManifest = runRoot.resolve("run-manifest.json")
        if (
            !Files.isRegularFile(parentManifest, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(parentManifest) ||
            Files.size(parentManifest) !in 1..1_048_576 ||
            supplied != sha256(Files.readAllBytes(parentManifest))
        ) {
            throw GradleException("high-contention parent manifest digest does not match")
        }
    }

    private fun requireResourceLabels(
        fields: Map<*, *>,
        selection: HighContentionProfileSelection,
    ) {
        val resources = fields["resourceLabels"] as? List<*>
            ?: throw GradleException("high-contention child resource labels are invalid")
        val actual = resources.associate { entry ->
            val resource = entry as? Map<*, *>
                ?: throw GradleException("high-contention child resource labels are invalid")
            if (resource.keys != setOf("resourceKey", "resourceType", "labels")) {
                throw GradleException("high-contention child resource label fields are invalid")
            }
            val key = resource["resourceKey"] as? String
                ?: throw GradleException("high-contention child resource key is invalid")
            val type = resource["resourceType"] as? String
                ?: throw GradleException("high-contention child resource type is invalid")
            val labels = resource["labels"] as? Map<*, *>
                ?: throw GradleException("high-contention child labels are invalid")
            if (
                labels.keys != requiredLabelKeys ||
                labels["io.bluetape4k.high-contention.run-id"] != selection.runId ||
                labels["io.bluetape4k.high-contention.profile-id"] != selection.profileId ||
                labels["io.bluetape4k.high-contention.resource-key"] != key ||
                labels["io.bluetape4k.high-contention.resource-type"] != type
            ) {
                throw GradleException("high-contention child labels do not match the task")
            }
            key to type
        }
        if (actual.size != resources.size || actual != expectedResources) {
            throw GradleException("high-contention child label allocation is incomplete")
        }
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}") {
                "${it.key}:${canonicalValue(it.value)}"
            }

        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalValue(it) }
        else -> value.toString()
    }

    private const val MAX_COORDINATOR_ANCESTOR_DEPTH = 4
}

private fun requireTrustedDirectory(path: Path): Path {
    if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
        throw GradleException("high-contention output root is not a trusted directory")
    }
    return path.toRealPath(NOFOLLOW_LINKS)
}

private fun requireExistingChildDirectory(
    outputRoot: Path,
    runRoot: Path,
): Path {
    if (!Files.isDirectory(runRoot, NOFOLLOW_LINKS) || Files.isSymbolicLink(runRoot)) {
        throw GradleException("high-contention parent run directory is missing")
    }
    val real = runRoot.toRealPath(NOFOLLOW_LINKS)
    if (real.parent != outputRoot) {
        throw GradleException("high-contention parent run directory escaped its output root")
    }
    return real
}

private fun requireContainedRegularFile(
    root: Path,
    expectedParent: Path,
    target: Path,
    maximumSize: Long,
    description: String,
) {
    if (!target.startsWith(expectedParent) || !expectedParent.startsWith(root)) {
        throw GradleException("$description is outside its trusted channel")
    }
    var current = root
    root.relativize(target).forEachIndexed { index, component ->
        current = current.resolve(component)
        if (Files.isSymbolicLink(current)) {
            throw GradleException("$description must not traverse symbolic links")
        }
        if (index < root.relativize(target).nameCount - 1) {
            if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                throw GradleException("$description parent is not a trusted directory")
            }
        }
    }
    if (
        !Files.isRegularFile(target, NOFOLLOW_LINKS) ||
        Files.size(target) !in 1..maximumSize ||
        target.toRealPath(NOFOLLOW_LINKS).parent != expectedParent.toRealPath(NOFOLLOW_LINKS)
    ) {
        throw GradleException("$description is outside its trusted channel")
    }
}

private fun requireAbsentContainedTarget(
    root: Path,
    target: Path,
) {
    if (!target.normalize().startsWith(root)) {
        throw GradleException("high-contention child artifact target escaped its run root")
    }
    var current = root
    root.relativize(target).forEachIndexed { index, component ->
        current = current.resolve(component)
        if (Files.isSymbolicLink(current)) {
            throw GradleException("high-contention child artifact target must not traverse symbolic links")
        }
        if (index < root.relativize(target).nameCount - 1 && Files.exists(current, NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                throw GradleException("high-contention child artifact parent must be a directory")
            }
        }
    }
    if (Files.exists(target, NOFOLLOW_LINKS)) {
        throw GradleException("high-contention child artifact target already exists")
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

private fun forceDirectory(directory: Path) {
    try {
        FileChannel.open(directory, READ).use { it.force(true) }
    } catch (_: UnsupportedOperationException) {
        // Directory fsync is not supported by every file provider.
    }
}
