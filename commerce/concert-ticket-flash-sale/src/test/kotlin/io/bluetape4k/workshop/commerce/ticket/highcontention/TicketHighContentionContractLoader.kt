package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
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

/**
 * TicketHighContentionContractException 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHighContentionContractException(
    message: String,
    cause: Throwable? = null,
): IllegalArgumentException(message, cause)

/**
 * TicketContractReadObserver 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal fun interface TicketContractReadObserver {
/**
 * afterRead은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun afterRead(path: Path)
}

/**
 * TicketHighContentionContractLoader 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHighContentionContractLoader(
/**
 * observer은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val observer: TicketContractReadObserver = TicketContractReadObserver {},
) {

    private companion object: KLogging() {
/**
 * TICKET_IMPLEMENTATION은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TICKET_IMPLEMENTATION = "ticket-spring"
/**
 * MAX_DOCUMENT_BYTES은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val MAX_DOCUMENT_BYTES = 1_048_576L
/**
 * SCHEDULE_ALGORITHM은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val SCHEDULE_ALGORITHM = "hc-v1-sha256-unsigned-rank"

/**
 * IMPLEMENTATIONS은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val IMPLEMENTATIONS = listOf(
            "job-core",
            "job-spring",
            "job-ktor",
            TICKET_IMPLEMENTATION
        )

/**
 * PROFILE_IDS은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val PROFILE_IDS = listOf(
            "burst",
            "duplicate-storm",
            "redis-path-outage",
            "redis-key-loss",
            "slow-provider",
            "worker-restart",
            "duplicate-delivery",
        )
    }

/**
 * mapper은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val mapper = Jackson.defaultJsonMapper
/**
 * strictFactory은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val strictFactory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxDocumentLength(MAX_DOCUMENT_BYTES)
                .maxNestingDepth(32)
                .maxStringLength(65_536)
                .maxNameLength(128)
                .maxNumberLength(32)
                .maxTokenCount(100_000)
                .build(),
        )
        .build()

/**
 * load은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun load(
        contractRoot: Path,
        mode: TicketHighContentionMode,
        profileId: String? = null,
        implementation: String = TICKET_IMPLEMENTATION,
    ): LoadedTicketHighContentionContract =
        try {
            loadValidated(
                contractRoot = contractRoot,
                mode = mode,
                profileId = profileId?.requireNotBlank("profileId"),
                implementation = implementation.requireNotBlank("implementation"),
            )
        } catch (error: TicketHighContentionContractException) {
            throw error
        } catch (error: Exception) {
            throw TicketHighContentionContractException("invalid Ticket high-contention contract", error)
        }

/**
 * loadValidated은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun loadValidated(
        contractRoot: Path,
        mode: TicketHighContentionMode,
        profileId: String?,
        implementation: String,
    ): LoadedTicketHighContentionContract {
/**
 * root은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val root = validateRoot(contractRoot)
/**
 * profileContract은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val profileContract = read(root, "profile-contract.json", TicketProfileContract::class.java)
/**
 * reportContract은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val reportContract = read(root, "report-contract.json", TicketReportContract::class.java)
/**
 * descriptorContract은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val descriptorContract = read(root, "child-descriptor-contract.json", TicketChildDescriptorContract::class.java)
/**
 * vectors은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val vectors = read(root, "schedule-vectors.json", TicketScheduleVectorDocument::class.java)
/**
 * suite은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val suite = read(root, "suite-manifest.json", TicketHighContentionSuite::class.java)

        validateVersions(suite, profileContract, reportContract, descriptorContract, vectors)
        validateSuite(suite)
        validateVectors(vectors)
        require(implementation in suite.implementations) { "unsupported implementation: $implementation" }

/**
 * selections은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val selections = suite.entries.asSequence()
            .filter { it.mode == mode }
            .filter { profileId == null || it.profileId == profileId }
            .filter { implementation in it.implementations }
            .map { entry ->
/**
 * profile은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val profile = read(root, entry.profileFile, TicketHighContentionProfile::class.java)
                validateProfile(profile, entry, profileContract)
                TicketHighContentionSelection(profile, implementation)
            }
            .toList()
            .also { it.requireNotEmpty("Ticket high-contention selections") }

        return LoadedTicketHighContentionContract(
            suite = suite,
            scheduleVectors = vectors,
            requiredReportFields = reportContract.requiredTopLevelFields.toSet(),
            forbiddenEvidencePatterns = reportContract.forbiddenEvidencePatterns,
            selections = selections,
        )
    }

/**
 * validateRoot은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validateRoot(contractRoot: Path): Path {
        val normalized = contractRoot.toAbsolutePath().normalize()
        rejectSymlinkComponents(normalized)
        if (!Files.isDirectory(normalized, NOFOLLOW_LINKS)) {
            throw TicketHighContentionContractException("contractRoot must be a directory")
        }
        return normalized.toRealPath(NOFOLLOW_LINKS)
    }

/**
 * rejectSymlinkComponents은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun rejectSymlinkComponents(path: Path) {
        var current = path.root
        path.forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                throw TicketHighContentionContractException("$path crosses a symbolic link")
            }
        }
    }

/**
 * read은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T> read(root: Path, relativePath: String, type: Class<T>): T {
        val path = resolveTrustedFile(root, relativePath)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val bytes = readBounded(path, before)
        observer.afterRead(path)
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!sameIdentity(before, after)) {
            throw TicketHighContentionContractException("$relativePath changed while it was read")
        }
        return try {
            strictFactory.createParser(ObjectReadContext.empty(), bytes).use { parser ->
                mapper.readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(parser)
            }
        } catch (error: Exception) {
            throw TicketHighContentionContractException("invalid JSON in $relativePath", error)
        }
    }

/**
 * resolveTrustedFile은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun resolveTrustedFile(root: Path, relativePath: String): Path {
        val relative = Path.of(relativePath.requireNotBlank("relativePath"))
        if (relative.isAbsolute || relative.normalize() != relative || relative.nameCount == 0 || relative.startsWith("..")) {
            throw TicketHighContentionContractException("$relativePath must be a normalized descendant")
        }
        var current = root
        relative.forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                throw TicketHighContentionContractException("$relativePath crosses a symbolic link")
            }
        }
        val normalized = current.toAbsolutePath().normalize()
        if (!normalized.startsWith(root) || !Files.isRegularFile(normalized, NOFOLLOW_LINKS)) {
            throw TicketHighContentionContractException("$relativePath is not a regular descendant")
        }
        if (!normalized.toRealPath(NOFOLLOW_LINKS).startsWith(root)) {
            throw TicketHighContentionContractException("$relativePath escapes the contract root")
        }
        return normalized
    }

/**
 * readBounded은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun readBounded(path: Path, before: BasicFileAttributes): ByteArray {
        if (before.size() !in 1..MAX_DOCUMENT_BYTES) {
            throw TicketHighContentionContractException("$path exceeds the document bound")
        }
        return FileChannel.open(path, setOf<OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() != before.size()) {
                throw TicketHighContentionContractException("$path changed before stable-handle read")
            }
            val buffer = ByteBuffer.allocate(before.size().toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw TicketHighContentionContractException("$path ended before its declared size")
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw TicketHighContentionContractException("$path grew during stable-handle read")
            }
            buffer.array()
        }
    }

/**
 * sameIdentity은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sameIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        before.isRegularFile &&
                after.isRegularFile &&
                before.fileKey() != null &&
                before.fileKey() == after.fileKey() &&
                before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime()

/**
 * validateVersions은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validateVersions(
        suite: TicketHighContentionSuite,
        profileContract: TicketProfileContract,
        reportContract: TicketReportContract,
        descriptorContract: TicketChildDescriptorContract,
        vectors: TicketScheduleVectorDocument,
    ) {
        suite.suiteSchemaVersion.requireEquals(1, "suiteSchemaVersion")
        profileContract.contractSchemaVersion.requireEquals(1, "profile contract version")
        suite.profileSchemaVersion.requireEquals(profileContract.profileSchemaVersion, "profileSchemaVersion")
        reportContract.contractSchemaVersion.requireEquals(1, "report contract version")
        suite.reportSchemaVersion.requireEquals(reportContract.reportSchemaVersion, "reportSchemaVersion")
        descriptorContract.contractSchemaVersion.requireEquals(1, "descriptor contract version")
        suite.childDescriptorSchemaVersion.requireEquals(
            descriptorContract.childDescriptorSchemaVersion,
            "childDescriptorSchemaVersion",
        )
        vectors.schemaVersion.requireEquals(1, "schedule vector schema version")
        vectors.algorithm.requireEquals(SCHEDULE_ALGORITHM, "schedule algorithm")
    }

/**
 * validateSuite은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validateSuite(suite: TicketHighContentionSuite) {
        suite.implementations.requireEquals(IMPLEMENTATIONS, "implementations")
        suite.runDeadlineMs.requirePositiveNumber("runDeadlineMs")
        suite.runJournalFinalizeReserveMs.requirePositiveNumber("runJournalFinalizeReserveMs")
        suite.runCleanupReserveMs.requirePositiveNumber("runCleanupReserveMs")
        suite.dockerCleanupPollIntervalMs.requirePositiveNumber("dockerCleanupPollIntervalMs")
        suite.dockerCleanupQuietPeriodMs.requirePositiveNumber("dockerCleanupQuietPeriodMs")
        val expected = TicketHighContentionMode.entries.flatMap { mode -> PROFILE_IDS.map { mode to it } }
        suite.entries.map { it.mode to it.profileId }.requireEquals(expected, "ordered suite entries")
        suite.entries.forEach { entry ->
            entry.implementations.requireEquals(IMPLEMENTATIONS, "entry implementations")
            entry.profileFile.requireEquals(
                "profiles/${entry.mode.wireValue}/${entry.profileId}.json",
                "profileFile",
            )
        }
    }

/**
 * validateVectors은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validateVectors(document: TicketScheduleVectorDocument) {
        document.vectors.requireNotEmpty("schedule vectors")
        val canonical = document.vectors.joinToString(",", "[", "]", transform = ::canonicalVector)
        sha256(canonical).requireEquals(document.vectorsSha256, "schedule vectors digest")
        document.vectors.forEach { vector ->
            TicketDeterministicSchedule.generate(vector).requireEquals(vector.expectedTokens, vector.name)
        }
    }

/**
 * validateProfile은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validateProfile(
        profile: TicketHighContentionProfile,
        entry: TicketHighContentionSuiteEntry,
        contract: TicketProfileContract,
    ) {
        profile.profileSchemaVersion.requireEquals(contract.profileSchemaVersion, "profileSchemaVersion")
        profile.profileId.requireEquals(entry.profileId, "profileId")
        profile.mode.requireEquals(entry.mode, "mode")
        profile.seed.requireNotBlank("seed")

/**
 * limits은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val limits = contract.limits[profile.mode.wireValue].requireNotNull { "missing mode limits" }
        profile.operationCount.requireInRange(1, limits.maxOperationCount, "operationCount")
        profile.concurrency.requireInRange(1, limits.maxConcurrency, "concurrency")
        profile.warmupOperationCount.requireInRange(0, limits.maxWarmupOperationCount, "warmupOperationCount")
        profile.profileDeadlineMs.requireInRange(1L, limits.maxProfileDeadlineMs, "profileDeadlineMs")
        profile.contentionShape.authorityCount.requireInRange(1, profile.operationCount, "authorityCount")
        profile.expectedInvariants.ticket.requireNotEmpty("ticket invariants")
        profile.observationFields.requireNotEmpty("observationFields")
        profile.knownLimitations.requireNotEmpty("knownLimitations")
        require(
            profile.cleanupReserveMs >= Math.addExact(
                profile.cleanupActionBudgetsMs.total(),
                profile.reportFinalizeReserveMs
            )
        ) {
            "cleanup reserve does not cover cleanup and report finalization"
        }
        require(profile.cleanupReserveMs < profile.profileDeadlineMs) {
            "cleanup reserve must be below profile deadline"
        }
    }

/**
 * canonicalVector은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun canonicalVector(vector: TicketScheduleVector): String =
        buildString {
            append("{\"authorityWeights\":")
            append(vector.authorityWeights.joinToString(",", "[", "]"))
            append(",\"curve\":\"")
            append(vector.curve.name.lowercase().replace('_', '-'))
            append("\",\"durationNanos\":${vector.durationNanos},\"epochs\":")
            append(vector.epochs.joinToString(",", "[", "]") {
                "{\"durationNanos\":${it.durationNanos},\"operationCount\":${it.operationCount}}"
            })
            append(",\"expectedTokens\":")
            append(vector.expectedTokens.joinToString(",", "[", "]") {
                "{\"attemptOrdinal\":${it.attemptOrdinal},\"authorityOrdinal\":${it.authorityOrdinal}," +
                        "\"identityOrdinal\":${it.identityOrdinal},\"offsetNanos\":${it.offsetNanos}," +
                        "\"stableOrdinal\":${it.stableOrdinal}}"
            })
            append(",\"name\":${mapper.writeValueAsString(vector.name)},\"operationCount\":${vector.operationCount}")
            append(",\"profileSchemaVersion\":${vector.profileSchemaVersion},\"retryShape\":")
            append(
                vector.retryShape?.let {
                    "{\"attemptsPerIdentity\":${it.attemptsPerIdentity},\"identityCount\":${it.identityCount}}"
                } ?: "null",
            )
            append(",\"seed\":${mapper.writeValueAsString(vector.seed)}}")
        }

/**
 * sha256은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toUtf8Bytes())
            .joinToString("") { "%02x".format(it) }
}
