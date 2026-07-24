package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class HighContentionContractException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun interface HighContentionFileReadObserver {
    fun afterRead(path: Path)
}

class HighContentionContractLoader(
    private val fileReadObserver: HighContentionFileReadObserver = HighContentionFileReadObserver {},
) {

    private val mapper = Jackson.createDefaultJsonMapper()
    private val strictJsonFactory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxDocumentLength(MAX_DOCUMENT_BYTES)
                .maxNestingDepth(MAX_DOCUMENT_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNameLength(MAX_NAME_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .maxTokenCount(MAX_TOKEN_COUNT)
                .build(),
        )
        .build()

    fun load(
        contractRoot: Path,
        mode: HighContentionMode,
        profileId: String? = null,
        implementation: String? = null,
    ): LoadedHighContentionContract =
        try {
            loadValidated(contractRoot, mode, profileId, implementation)
        } catch (error: HighContentionContractException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw HighContentionContractException("invalid high-contention contract or selection", error)
        } catch (error: ArithmeticException) {
            throw HighContentionContractException("high-contention contract arithmetic overflow", error)
        }

    private fun loadValidated(
        contractRoot: Path,
        mode: HighContentionMode,
        profileId: String?,
        implementation: String?,
    ): LoadedHighContentionContract {
        val validProfileId = profileId?.requireNotBlank("profileId")
        val validImplementation = implementation?.requireNotBlank("implementation")
        val root = validateContractRoot(contractRoot)

        val profileContract = read(root, "profile-contract.json", HighContentionProfileContract::class.java)
        val reportContract = read(root, "report-contract.json", HighContentionReportContract::class.java)
        val descriptorContract = read(
            root,
            "child-descriptor-contract.json",
            HighContentionChildDescriptorContract::class.java,
        )
        val scheduleVectors = read(root, "schedule-vectors.json", ScheduleVectorDocument::class.java)
        val manifest = read(root, "suite-manifest.json", HighContentionSuiteManifest::class.java)

        validateDocumentVersions(profileContract, reportContract, descriptorContract, scheduleVectors, manifest)
        validateManifest(manifest)
        validateScheduleVectors(scheduleVectors)

        if (validImplementation != null && validImplementation !in manifest.implementations) {
            throw IllegalArgumentException("implementation must be one of ${manifest.implementations.joinToString()}")
        }
        if (validProfileId != null && manifest.entries.none { it.mode == mode && it.profileId == validProfileId }) {
            throw IllegalArgumentException("unknown profileId for ${mode.wireValue}: $validProfileId")
        }

        val selections = manifest.entries
            .asSequence()
            .filter { it.mode == mode }
            .filter { validProfileId == null || it.profileId == validProfileId }
            .flatMap { entry ->
                val profile = read(root, entry.profileFile, HighContentionProfile::class.java)
                validateProfile(profile, entry, profileContract)
                entry.implementations
                    .asSequence()
                    .filter { validImplementation == null || it == validImplementation }
                    .map { HighContentionSelection(profile, it) }
            }
            .toList()

        if (selections.isEmpty()) {
            throw IllegalArgumentException("the requested high-contention selection is empty")
        }
        return LoadedHighContentionContract(
            suite = manifest,
            profileContract = profileContract,
            reportContract = reportContract,
            childDescriptorContract = descriptorContract,
            scheduleVectors = scheduleVectors,
            selections = selections,
        )
    }

    private fun validateContractRoot(contractRoot: Path): Path {
        val normalized = contractRoot.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, NOFOLLOW_LINKS)) {
            throw HighContentionContractException("contractRoot must be a non-symbolic-link directory")
        }
        return normalized.toRealPath(NOFOLLOW_LINKS)
    }

    private fun <T> read(root: Path, relativePath: String, type: Class<T>): T {
        val path = resolveTrustedFile(root, relativePath)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val bytes = readBounded(path, before)
        fileReadObserver.afterRead(path)
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!sameFileIdentity(before, after)) {
            throw HighContentionContractException("$relativePath changed identity while it was read")
        }

        return try {
            strictJsonFactory.createParser(ObjectReadContext.empty(), bytes).use { parser ->
                mapper.readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(parser)
            }
        } catch (error: HighContentionContractException) {
            throw error
        } catch (error: Exception) {
            throw HighContentionContractException("invalid high-contention JSON in $relativePath", error)
        }
    }

    private fun resolveTrustedFile(root: Path, relativePath: String): Path {
        val validRelativePath = relativePath.requireNotBlank("relativePath")
        val relative = Path.of(validRelativePath)
        if (
            relative.isAbsolute ||
            relative.normalize() != relative ||
            relative.nameCount == 0 ||
            relative.startsWith("..")
        ) {
            throw HighContentionContractException("$relativePath must be a normalized descendant path")
        }

        var current = root
        for (component in relative) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                throw HighContentionContractException("$relativePath crosses a symbolic link")
            }
        }
        val normalized = current.toAbsolutePath().normalize()
        if (!normalized.startsWith(root) || !Files.isRegularFile(normalized, NOFOLLOW_LINKS)) {
            throw HighContentionContractException("$relativePath must resolve to a regular descendant file")
        }
        val real = normalized.toRealPath(NOFOLLOW_LINKS)
        if (!real.startsWith(root)) {
            throw HighContentionContractException("$relativePath escapes the trusted contract root")
        }
        return normalized
    }

    private fun readBounded(path: Path, before: BasicFileAttributes): ByteArray {
        if (before.size() !in 1..MAX_DOCUMENT_BYTES) {
            throw HighContentionContractException("$path exceeds the bounded document size")
        }
        val options = setOf<OpenOption>(READ, NOFOLLOW_LINKS)
        return FileChannel.open(path, options).use { channel ->
            if (channel.size() != before.size()) {
                throw HighContentionContractException("$path changed before its stable handle was read")
            }
            val buffer = ByteBuffer.allocate(before.size().toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw HighContentionContractException("$path ended before its declared size")
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw HighContentionContractException("$path grew while its stable handle was read")
            }
            buffer.array()
        }
    }

    private fun sameFileIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        before.isRegularFile &&
            after.isRegularFile &&
            before.fileKey() != null &&
            before.fileKey() == after.fileKey() &&
            before.size() == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime()

    private fun validateDocumentVersions(
        profileContract: HighContentionProfileContract,
        reportContract: HighContentionReportContract,
        descriptorContract: HighContentionChildDescriptorContract,
        scheduleVectors: ScheduleVectorDocument,
        manifest: HighContentionSuiteManifest,
    ) {
        profileContract.contractSchemaVersion.requireEquals(1, "profile contract schema version")
        profileContract.profileSchemaVersion.requireEquals(1, "profile schema version")
        reportContract.contractSchemaVersion.requireEquals(1, "report contract schema version")
        reportContract.reportSchemaVersion.requireEquals(1, "report schema version")
        descriptorContract.contractSchemaVersion.requireEquals(1, "descriptor contract schema version")
        descriptorContract.childDescriptorSchemaVersion.requireEquals(1, "child descriptor schema version")
        scheduleVectors.schemaVersion.requireEquals(1, "schedule vector schema version")
        scheduleVectors.algorithm.requireEquals(SCHEDULE_ALGORITHM, "schedule vector algorithm")
        manifest.suiteSchemaVersion.requireEquals(1, "suite schema version")
        manifest.profileSchemaVersion.requireEquals(profileContract.profileSchemaVersion, "manifest profile schema version")
        manifest.reportSchemaVersion.requireEquals(reportContract.reportSchemaVersion, "manifest report schema version")
        manifest.childDescriptorSchemaVersion.requireEquals(
            descriptorContract.childDescriptorSchemaVersion,
            "manifest child descriptor schema version",
        )
    }

    private fun validateManifest(manifest: HighContentionSuiteManifest) {
        manifest.implementations.requireEquals(IMPLEMENTATIONS, "implementations")
        manifest.runDeadlineMs.requirePositiveNumber("runDeadlineMs")
        manifest.runJournalFinalizeReserveMs.requirePositiveNumber("runJournalFinalizeReserveMs")
        manifest.runCleanupReserveMs.requirePositiveNumber("runCleanupReserveMs")
        manifest.dockerCleanupPollIntervalMs.requirePositiveNumber("dockerCleanupPollIntervalMs")
        manifest.dockerCleanupQuietPeriodMs.requirePositiveNumber("dockerCleanupQuietPeriodMs")
        val cleanupTotal = listOf(
            manifest.runCleanupActionBudgetsMs.childProcesses,
            manifest.runCleanupActionBudgetsMs.dockerDiscovery,
            manifest.runCleanupActionBudgetsMs.artifactFinalization,
        ).onEach { it.requirePositiveNumber("run cleanup action budget") }
            .fold(0L, Math::addExact)
        if (manifest.runCleanupReserveMs < Math.addExact(cleanupTotal, manifest.runJournalFinalizeReserveMs)) {
            throw HighContentionContractException("runCleanupReserveMs does not cover cleanup and journal finalization")
        }
        if (manifest.runCleanupReserveMs >= manifest.runDeadlineMs) {
            throw HighContentionContractException("runCleanupReserveMs must be less than runDeadlineMs")
        }
        if (manifest.dockerCleanupQuietPeriodMs < Math.multiplyExact(manifest.dockerCleanupPollIntervalMs, 2L)) {
            throw HighContentionContractException("docker cleanup quiet period must cover at least two polls")
        }

        val expectedEntries = HighContentionMode.entries.flatMap { mode ->
            PROFILE_IDS.map { profileId -> mode to profileId }
        }
        manifest.entries.size.requireEquals(expectedEntries.size, "suite entry count")
        val actualEntries = manifest.entries.map { it.mode to it.profileId }
        actualEntries.requireEquals(expectedEntries, "ordered suite entries")

        val matrix = mutableSetOf<Triple<HighContentionMode, String, String>>()
        manifest.entries.forEach { entry ->
            entry.profileId.requireNotBlank("profileId")
            entry.implementations.requireEquals(IMPLEMENTATIONS, "entry implementations")
            val expectedPath = "profiles/${entry.mode.wireValue}/${entry.profileId}.json"
            entry.profileFile.requireEquals(expectedPath, "profileFile")
            entry.implementations.forEach { adapter ->
                if (!matrix.add(Triple(entry.mode, entry.profileId, adapter))) {
                    throw HighContentionContractException("duplicate matrix tuple for ${entry.mode.wireValue}/${entry.profileId}/$adapter")
                }
            }
        }
        matrix.size.requireEquals(expectedEntries.size * IMPLEMENTATIONS.size, "matrix tuple count")
    }

    private fun validateProfile(
        profile: HighContentionProfile,
        entry: HighContentionSuiteEntry,
        contract: HighContentionProfileContract,
    ) {
        profile.profileSchemaVersion.requireEquals(contract.profileSchemaVersion, "profileSchemaVersion")
        profile.profileId.requireEquals(entry.profileId, "profileId")
        profile.mode.requireEquals(entry.mode, "profile mode")
        profile.seed.requireNotBlank("seed")
        val limits = contract.limits[profile.mode.wireValue]
            ?: throw HighContentionContractException("missing limits for ${profile.mode.wireValue}")

        profile.operationCount.requireInRange(1, limits.maxOperationCount, "operationCount")
        profile.concurrency.requireInRange(1, limits.maxConcurrency, "concurrency")
        profile.dispatcherBacklogCapacity.requireInRange(1, limits.maxOperationCount, "dispatcherBacklogCapacity")
        profile.maxScheduleDelayMs.requirePositiveNumber("maxScheduleDelayMs")
        profile.warmupOperationCount.requireInRange(0, limits.maxWarmupOperationCount, "warmupOperationCount")
        profile.workloadDurationMs.requirePositiveNumber("workloadDurationMs")
        profile.profileDeadlineMs.requireInRange(1, limits.maxProfileDeadlineMs, "profileDeadlineMs")
        listOf(
            profile.operationTimeoutMs,
            profile.injectionDeadlineMs,
            profile.failureDetectionDeadlineMs,
            profile.workloadJoinDeadlineMs,
            profile.recoveryDeadlineMs,
            profile.reportFinalizeReserveMs,
            profile.cleanupReserveMs,
        ).forEach { it.requirePositiveNumber("profile deadline") }

        when (profile.arrivalCurve) {
            ArrivalCurve.BURST -> {
                if (profile.epochs.isNotEmpty() || profile.retryShape != null) {
                    throw HighContentionContractException("burst profiles cannot declare epochs or retryShape")
                }
            }

            ArrivalCurve.STEP -> {
                profile.epochs.requireNotEmpty("epochs")
                if (profile.retryShape != null) {
                    throw HighContentionContractException("step profiles cannot declare retryShape")
                }
                val operationTotal = profile.epochs.fold(0) { total, epoch ->
                    epoch.durationMs.requirePositiveNumber("epoch.durationMs")
                    epoch.operationCount.requirePositiveNumber("epoch.operationCount")
                    Math.addExact(total, epoch.operationCount)
                }
                val durationTotal = profile.epochs.fold(0L) { total, epoch -> Math.addExact(total, epoch.durationMs) }
                operationTotal.requireEquals(profile.operationCount, "step operation count")
                durationTotal.requireEquals(profile.workloadDurationMs, "step workload duration")
            }

            ArrivalCurve.RETRY_STORM -> {
                if (profile.epochs.isNotEmpty()) {
                    throw HighContentionContractException("retry-storm profiles cannot declare epochs")
                }
                val retry = profile.retryShape
                    ?: throw HighContentionContractException("retry-storm profiles require retryShape")
                retry.identityCount.requirePositiveNumber("retry identity count")
                retry.attemptsPerIdentity.requirePositiveNumber("attempts per identity")
                retry.epochDurationMs.requireEquals(profile.workloadDurationMs, "retry epoch duration")
                Math.multiplyExact(retry.identityCount, retry.attemptsPerIdentity)
                    .requireEquals(profile.operationCount, "retry operation count")
            }
        }

        profile.contentionShape.authorityCount.requireInRange(1, profile.operationCount, "authorityCount")
        profile.contentionShape.hotAuthorityCount.requireInRange(
            1,
            profile.contentionShape.authorityCount,
            "hotAuthorityCount",
        )
        profile.contentionShape.identityCount.requireInRange(1, profile.operationCount, "identityCount")
        profile.contentionShape.sameIdentityRatioPermille.requireInRange(0, 1000, "sameIdentityRatioPermille")
        profile.expectedSubmissionOutcomes.minimumDispatched.requireInRange(
            0,
            profile.operationCount,
            "minimumDispatched",
        )
        profile.expectedSubmissionOutcomes.minimumCompleted.requireInRange(
            0,
            profile.expectedSubmissionOutcomes.minimumDispatched,
            "minimumCompleted",
        )
        profile.expectedSubmissionOutcomes.maximumLocalRejected.requireInRange(
            0,
            profile.operationCount,
            "maximumLocalRejected",
        )
        profile.expectedSubmissionOutcomes.maximumMissedDeadline.requireInRange(
            0,
            profile.operationCount,
            "maximumMissedDeadline",
        )
        profile.failure.triggerAcceptedCount.requireInRange(0, profile.operationCount, "triggerAcceptedCount")
        profile.failure.steps.forEach { it.requireNotBlank("failure step") }
        profile.expectedInvariants.job.requireNotEmpty("job invariants")
        profile.expectedInvariants.ticket.requireNotEmpty("ticket invariants")
        profile.observationFields.requireNotEmpty("observationFields")
        profile.knownLimitations.requireNotEmpty("knownLimitations")

        val requiredCleanupReserve = Math.addExact(profile.cleanupActionBudgetsMs.total(), profile.reportFinalizeReserveMs)
        if (profile.cleanupReserveMs < requiredCleanupReserve) {
            throw HighContentionContractException("cleanupReserveMs does not cover cleanup actions and report finalization")
        }
        if (profile.cleanupReserveMs >= profile.profileDeadlineMs) {
            throw HighContentionContractException("cleanupReserveMs must be less than profileDeadlineMs")
        }
    }

    private fun validateScheduleVectors(document: ScheduleVectorDocument) {
        document.vectors.requireNotEmpty("schedule vectors")
        val canonical = document.vectors.joinToString(separator = ",", prefix = "[", postfix = "]") {
            canonicalVector(it)
        }
        sha256(canonical).requireEquals(document.vectorsSha256, "schedule vectors digest")
        document.vectors.forEach { vector ->
            vector.name.requireNotBlank("schedule vector name")
            vector.profileSchemaVersion.requireEquals(1, "schedule vector profile schema version")
            vector.operationCount.requirePositiveNumber("schedule vector operation count")
            vector.durationNanos.requirePositiveNumber("schedule vector duration")
            vector.authorityWeights.requireNotEmpty("schedule vector authority weights")
            vector.authorityWeights.forEach { it.requirePositiveNumber("authority weight") }
            vector.expectedTokens.size.requireEquals(vector.operationCount, "expected token count")
        }
    }

    private fun canonicalVector(vector: ScheduleVector): String =
        buildString {
            append("{\"authorityWeights\":")
            append(vector.authorityWeights.joinToString(separator = ",", prefix = "[", postfix = "]"))
            append(",\"curve\":")
            append(quoted(vector.curve.wireValue))
            append(",\"durationNanos\":")
            append(vector.durationNanos)
            append(",\"epochs\":")
            append(
                vector.epochs.joinToString(separator = ",", prefix = "[", postfix = "]") {
                    "{\"durationNanos\":${it.durationNanos},\"operationCount\":${it.operationCount}}"
                },
            )
            append(",\"expectedTokens\":")
            append(
                vector.expectedTokens.joinToString(separator = ",", prefix = "[", postfix = "]") {
                    "{\"attemptOrdinal\":${it.attemptOrdinal},\"authorityOrdinal\":${it.authorityOrdinal}," +
                        "\"identityOrdinal\":${it.identityOrdinal},\"offsetNanos\":${it.offsetNanos}," +
                        "\"stableOrdinal\":${it.stableOrdinal}}"
                },
            )
            append(",\"name\":")
            append(quoted(vector.name))
            append(",\"operationCount\":")
            append(vector.operationCount)
            append(",\"profileSchemaVersion\":")
            append(vector.profileSchemaVersion)
            append(",\"retryShape\":")
            append(
                vector.retryShape?.let {
                    "{\"attemptsPerIdentity\":${it.attemptsPerIdentity},\"identityCount\":${it.identityCount}}"
                } ?: "null",
            )
            append(",\"seed\":")
            append(quoted(vector.seed))
            append("}")
        }

    private fun quoted(value: String): String = mapper.writeValueAsString(value)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 1_048_576L
        private const val MAX_DOCUMENT_DEPTH = 32
        private const val MAX_STRING_LENGTH = 65_536
        private const val MAX_NAME_LENGTH = 128
        private const val MAX_NUMBER_LENGTH = 32
        private const val MAX_TOKEN_COUNT = 100_000L
        private const val SCHEDULE_ALGORITHM = "hc-v1-sha256-unsigned-rank"

        private val IMPLEMENTATIONS = listOf("job-core", "job-spring", "job-ktor", "ticket-spring")
        private val PROFILE_IDS = listOf(
            "burst",
            "duplicate-storm",
            "redis-path-outage",
            "redis-key-loss",
            "slow-provider",
            "worker-restart",
            "duplicate-delivery",
        )
    }
}
