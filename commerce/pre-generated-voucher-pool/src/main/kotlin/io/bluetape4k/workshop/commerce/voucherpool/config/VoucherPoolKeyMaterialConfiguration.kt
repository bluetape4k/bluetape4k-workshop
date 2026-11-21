@file:Suppress(
    "MagicNumber",
    "SwallowedException", // Original exceptions can contain secret-file paths or JSON fragments.
    "ThrowsCount", // Each fail-closed file boundary maps to one stable redacted code.
    "TooGenericExceptionCaught",
    "TooManyFunctions", // Parsing stays encapsulated in the mounted-secret provider boundary.
)

package io.bluetape4k.workshop.commerce.voucherpool.config

import com.sun.security.auth.module.UnixSystem
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKek
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.*

/**
 * VoucherPoolKeyMaterialProvider은 사전 생성 바우처 풀 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface VoucherPoolKeyMaterialProvider {
    /**
     * load은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun load(): VoucherPoolRuntimeKeys
}

/**
 * VoucherPoolKeyFileLocator은 사전 생성 바우처 풀 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface VoucherPoolKeyFileLocator {
    /**
     * locate은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun locate(): String?
}

/**
 * `VoucherPoolKeyProvenance`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class VoucherPoolKeyProvenance {
    /**
     * `VoucherPoolKeyProvenance.MOUNTED_SECRET`은 `mounted secret` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    MOUNTED_SECRET,
    /**
     * `VoucherPoolKeyProvenance.TEST_FIXTURE`은 `test fixture` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된
     * 전이를 확인한 뒤 사용합니다.
     */
    TEST_FIXTURE,
}

/**
 * 완전히 구성된 runtime key입니다. raw key byte는 이 boundary를 넘지 않습니다.
 * @property digests 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property kekRing 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property provenance 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class VoucherPoolRuntimeKeys(
    /**
     * `digests`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val digests: VoucherDigestService,
    /**
     * `kekRing`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val kekRing: VoucherKekRing,
    /**
     * `provenance`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val provenance: VoucherPoolKeyProvenance,
) {
    /**
     * toString은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String = "VoucherPoolRuntimeKeys(provenance=$provenance, material=[REDACTED])"
}

/**
 * `VoucherPoolKeyMaterialFailureCode`은 업무 코드입니다.
 * 호출자가 재시도·거절·대사·운영자 조치를 분기하는 오류 계약입니다.
 */
internal enum class VoucherPoolKeyMaterialFailureCode {
    /**
     * `VoucherPoolKeyMaterialFailureCode.MISSING_LOCATION`은 `missing location` 업무 상황을 나타냅니다. 호출자는
     * 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    MISSING_LOCATION,
    /**
     * `VoucherPoolKeyMaterialFailureCode.INVALID_PATH`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_PATH,
    /**
     * `VoucherPoolKeyMaterialFailureCode.FILE_UNAVAILABLE`은 필수 의존성이 준비되지 않아 요청을 수용할 수 없는 값입니다.
     */
    FILE_UNAVAILABLE,
    /**
     * `VoucherPoolKeyMaterialFailureCode.FILE_NOT_REGULAR`은 `file not regular` 업무 상황을 나타냅니다. 호출자는
     * 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    FILE_NOT_REGULAR,
    /**
     * `VoucherPoolKeyMaterialFailureCode.FILE_NOT_CANONICAL`은 `file not canonical` 업무 상황을 나타냅니다.
     * 호출자는 이
     * 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    FILE_NOT_CANONICAL,
    /**
     * `VoucherPoolKeyMaterialFailureCode.INSECURE_PERMISSIONS`은 `insecure permissions` 업무 상황을
     * 나타냅니다. 호출자는
     * 이 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    INSECURE_PERMISSIONS,
    /**
     * `VoucherPoolKeyMaterialFailureCode.FILE_TOO_LARGE`은 `file too large` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    FILE_TOO_LARGE,
    /**
     * `VoucherPoolKeyMaterialFailureCode.INVALID_JSON`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_JSON,
    /**
     * `VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_SCHEMA,
    /**
     * `VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은
     * 값입니다.
     */
    INVALID_KEY_MATERIAL,
    /**
     * `VoucherPoolKeyMaterialFailureCode.FILE_CHANGED`은 `file changed` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    FILE_CHANGED,
}
/**
 * VoucherPoolKeyMaterialException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property code 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


internal class VoucherPoolKeyMaterialException(
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: VoucherPoolKeyMaterialFailureCode,
): IllegalStateException("VOUCHER_POOL_KEY_MATERIAL_${code.name}")

/**
 * environment indirection으로만 선택한 permission-restricted mounted secret 하나를 load합니다.
 * @property locator 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property mapper 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property expectedOwnerId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property beforeRead 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class MountedSecretVoucherPoolKeyMaterialProvider(
    /**
     * `locator`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val locator: VoucherPoolKeyFileLocator,
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper,
    /**
     * `expectedOwnerId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    private val expectedOwnerId: () -> Long = ::currentProcessOwnerId,
    /**
     * `beforeRead`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val beforeRead: (Path) -> Unit = {},
): VoucherPoolKeyMaterialProvider {

    private companion object: KLogging() {
        /**
         * `MAX_FILE_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val MAX_FILE_BYTES = 64 * 1024
        /**
         * `MIN_DIGEST_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val MIN_DIGEST_BYTES = 32
        /**
         * `KEK_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val KEK_BYTES = 32
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        const val MAX_RETAINED_KEYS = 16
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        val ROOT_FIELDS = setOf("stableDedup", "commandTombstone", "rotating", "kek")
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        val KEY_FIELDS = setOf("version", "material")
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val RING_FIELDS = setOf("current", "retained")
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val ROTATING_PURPOSES = setOf(
            DigestPurpose.VERIFICATION,
            DigestPurpose.USER_IDENTITY,
            DigestPurpose.REDIS_SIGNAL,
            DigestPurpose.AUDIT,
        )
        /**
         * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
         */
        val VERSION_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val FORBIDDEN_LABEL_PARTS = setOf("test", "fixture", "sample", "demo", "default", "local", "example")
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val HEX: HexFormat = HexFormat.of()
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */

        val KNOWN_TEST_FIXTURE_FINGERPRINTS = setOf(
            "ae216c2ef5247a3782c135efa279a3e4cdc61094270f5d2be58c6204b7a612c9",
            "6f103f3d9ba4c7e4d49642fb221098b83bcf07ac955063cb28f093eab38c5444",
            "00c1599d38973fee5a680ea93eba60736b1b1522a1e3488cfcd48f86ab87f4a9",
            "73e5ce5d058c73856400433b9f45c63b9370100b7b22c58167eec5cd1e571ae3",
            "07f23239ee9e10a17942824b76d1919234dfaa97525ed8741e016a2a11c2981c",
            "4caaad9b331e92ac5ccb3adf8fb1d023a3f5d66d895d9362c4fc184dae4e649f",
            "c591c90fef0f1746bcdbfd3504a7fbf02c63c312ffaf8b93368137278afc81b1",
        )
    }

    /**
     * load은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun load(): VoucherPoolRuntimeKeys =
        try {
            /**
             * `trustedFile`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
             *
             * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
             */
            val trustedFile = locateCanonicalFile()
            beforeRead(trustedFile.path)
            /**
             * `bytes`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
             *
             * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
             */
            val bytes = readBounded(trustedFile.path)
            try {
                if (trustedFile.snapshot != trustedSnapshot(trustedFile.path)) {
                    fail(VoucherPoolKeyMaterialFailureCode.FILE_CHANGED)
                }
                parse(mapper.readTree(bytes))
            } catch (failure: VoucherPoolKeyMaterialException) {
                throw failure
            } catch (failure: Exception) {
                throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INVALID_JSON)
            } finally {
                bytes.fill(0)
            }
        } catch (failure: VoucherPoolKeyMaterialException) {
            throw failure
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.FILE_UNAVAILABLE)
        }

    /**
     * locateCanonicalFile은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를
     * 업무 오류로
     * 분류합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun locateCanonicalFile(): TrustedFile {
        val raw = locator.locate()?.takeIf(String::isNotBlank)
            ?: throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.MISSING_LOCATION)
        val path = try {
            Path.of(raw)
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INVALID_PATH)
        }

        if (!path.isAbsolute) fail(VoucherPoolKeyMaterialFailureCode.INVALID_PATH)
        if (Files.isSymbolicLink(path)) fail(VoucherPoolKeyMaterialFailureCode.FILE_NOT_REGULAR)
        val normalized = path.normalize()
        if (normalized != path) fail(VoucherPoolKeyMaterialFailureCode.FILE_NOT_CANONICAL)

        val real = try {
            path.toRealPath()
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.FILE_UNAVAILABLE)
        }
        if (real != path) fail(VoucherPoolKeyMaterialFailureCode.FILE_NOT_CANONICAL)

        return TrustedFile(path, trustedSnapshot(path))
    }

    /**
     * trustedSnapshot은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param path 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun trustedSnapshot(path: Path): TrustedFileSnapshot {
        val attributes = try {
            Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INSECURE_PERMISSIONS)
        }
        if (!attributes.isRegularFile) fail(VoucherPoolKeyMaterialFailureCode.FILE_NOT_REGULAR)
        if (attributes.size() > MAX_FILE_BYTES) fail(VoucherPoolKeyMaterialFailureCode.FILE_TOO_LARGE)
        val fileKey = attributes.fileKey() ?: fail(VoucherPoolKeyMaterialFailureCode.FILE_UNAVAILABLE)
        val permissions = attributes.permissions()

        val forbidden = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        val ownerId = try {
            (Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong()
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INSECURE_PERMISSIONS)
        }
        val expectedOwner = try {
            expectedOwnerId()
        } catch (failure: Exception) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INSECURE_PERMISSIONS)
        }
        if (
            PosixFilePermission.OWNER_READ !in permissions ||
            permissions.any(forbidden::contains) ||
            ownerId != expectedOwner
        ) {
            fail(VoucherPoolKeyMaterialFailureCode.INSECURE_PERMISSIONS)
        }
        return TrustedFileSnapshot(
            fileKey = fileKey,
            size = attributes.size(),
            ownerId = ownerId,
            ownerName = attributes.owner().name,
            permissions = permissions.toSet()
        )
    }

    /**
     * readBounded은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param path 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun readBounded(path: Path): ByteArray {
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            input.readNBytes(MAX_FILE_BYTES + 1)
        }
        if (bytes.size <= MAX_FILE_BYTES) return bytes

        bytes.fill(0)
        fail(VoucherPoolKeyMaterialFailureCode.FILE_TOO_LARGE)
    }

    /**
     * parse은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param root 업무 처리에 필요한 root 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun parse(root: JsonNode): VoucherPoolRuntimeKeys {
        root.requireObject(ROOT_FIELDS)
        val stable = root.required("stableDedup").digestKey()
        val tombstone = root.required("commandTombstone").digestKey()
        val rotatingNode = root.required("rotating").requireObject(ROTATING_PURPOSES.map(DigestPurpose::name).toSet())
        val rotating = ROTATING_PURPOSES.associateWith { purpose -> rotatingNode.required(purpose.name).digestRing() }
        val kekRing = root.required("kek").kekRing()

        return VoucherPoolRuntimeKeys(
            digests = VoucherDigestService(stable, tombstone, rotating),
            kekRing = kekRing,
            provenance = VoucherPoolKeyProvenance.MOUNTED_SECRET,
        )
    }

    /**
     * digestKey은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun JsonNode.digestKey(): DigestKey {
        requireObject(KEY_FIELDS)
        val versionNode = required("version")
        if (!versionNode.isInt || versionNode.intValue() <= 0) fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        val material = decodedMaterial(required("material"), MIN_DIGEST_BYTES)

        return try {
            DigestKey.of(versionNode.intValue(), material)
        } finally {
            material.fill(0)
        }
    }

    /**
     * digestRing은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun JsonNode.digestRing(): DigestKeyRing {
        requireObject(RING_FIELDS)
        val current = required("current").digestKey()
        val retained = required("retained").digestKeys()

        if ((listOf(current) + retained).map(DigestKey::version).distinct().size != retained.size + 1) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        return DigestKeyRing.of(current, retained)
    }

    /**
     * digestKeys은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun JsonNode.digestKeys(): List<DigestKey> {
        if (!isArray || size() > MAX_RETAINED_KEYS) fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        return (0 until size()).map { index -> get(index).digestKey() }
    }

    /**
     * kekRing은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun JsonNode.kekRing(): VoucherKekRing {
        requireObject(RING_FIELDS)
        val current = required("current").kek()
        val retainedNode = required("retained")
        if (!retainedNode.isArray || retainedNode.size() > MAX_RETAINED_KEYS) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        }
        val retained = List(retainedNode.size()) { index -> retainedNode.get(index).kek() }
        if ((listOf(current) + retained).map(VoucherKek::version).distinct().size != retained.size + 1) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        return VoucherKekRing.of(current, retained)
    }

    /**
     * kek은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun JsonNode.kek(): VoucherKek {
        requireObject(KEY_FIELDS)
        val versionNode = required("version")
        if (!versionNode.isString) fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        val version = versionNode.asString()

        if (!VERSION_LABEL.matches(version) || FORBIDDEN_LABEL_PARTS.any { version.contains(it, ignoreCase = true) }) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        val material = decodedMaterial(required("material"), KEK_BYTES, exact = true)

        return try {
            VoucherKek.of(version, material)
        } finally {
            material.fill(0)
        }
    }

    /**
     * decodedMaterial은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무
     * 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param node 업무 처리에 필요한 node 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param minimumBytes 업무 처리에 필요한 minimumBytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param exact 업무 처리에 필요한 exact 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun decodedMaterial(node: JsonNode, minimumBytes: Int, exact: Boolean = false): ByteArray {
        if (!node.isString) fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        val material = try {
            Base64.getDecoder().decode(node.asString())
        } catch (failure: IllegalArgumentException) {
            throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        val invalidSize = if (exact) material.size != minimumBytes else material.size < minimumBytes
        val repeating = material.isEmpty() || material.all { it == material[0] }
        val fixtureMaterial = material.isKnownTestFixture()

        if (invalidSize || repeating || fixtureMaterial) {
            material.fill(0)
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_KEY_MATERIAL)
        }
        return material
    }

    /**
     * isKnownTestFixture은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun ByteArray.isKnownTestFixture(): Boolean {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(this)

        return try {
            HEX.formatHex(fingerprint) in KNOWN_TEST_FIXTURE_FINGERPRINTS
        } finally {
            fingerprint.fill(0)
        }
    }

    /**
     * required은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.required(name: String): JsonNode =
        get(name) ?: throw VoucherPoolKeyMaterialException(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)

    /**
     * requireObject은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param expectedFields 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun JsonNode.requireObject(expectedFields: Set<String>): JsonNode {
        if (!isObject || propertyNames().toSet() != expectedFields) {
            fail(VoucherPoolKeyMaterialFailureCode.INVALID_SCHEMA)
        }
        return this
    }

    /**
     * fail은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun fail(code: VoucherPoolKeyMaterialFailureCode): Nothing =
        throw VoucherPoolKeyMaterialException(code)

}
/**
 * TrustedFile은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property path 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property snapshot 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */


private data class TrustedFile(
    /**
     * `path`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val path: Path,
    /**
     * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val snapshot: TrustedFileSnapshot,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * TrustedFileSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property fileKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property size 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property ownerId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property ownerName 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property permissions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


private data class TrustedFileSnapshot(
    /**
     * `fileKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fileKey: Any,
    /**
     * `size`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val size: Long,
    /**
     * `ownerId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val ownerId: Long,
    /**
     * `ownerName`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerName: String,
    /**
     * `permissions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val permissions: Set<PosixFilePermission>,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * currentProcessOwnerId은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
 * 처리합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun currentProcessOwnerId(): Long = UnixSystem().uid

@Configuration(proxyBeanMethods = false)
@Profile("!voucher-pool-test")
/**
 * VoucherPoolKeyMaterialConfiguration은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class VoucherPoolKeyMaterialConfiguration {

    private companion object: KLogging() {
        /**
         * `VOUCHER_POOL_KEY_FILE`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        const val VOUCHER_POOL_KEY_FILE = "VOUCHER_POOL_KEY_FILE"
    }

    /**
     * voucherPoolKeyFileLocator은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    @ConditionalOnMissingBean(VoucherPoolKeyFileLocator::class)
    fun voucherPoolKeyFileLocator(): VoucherPoolKeyFileLocator =
        VoucherPoolKeyFileLocator {
            System.getenv(VOUCHER_POOL_KEY_FILE)
        }

    /**
     * voucherPoolKeyMaterialProvider은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param locator 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param mapper 업무 처리에 필요한 mapper 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherPoolKeyMaterialProvider(
        locator: VoucherPoolKeyFileLocator,
        mapper: ObjectMapper,
    ): VoucherPoolKeyMaterialProvider =
        MountedSecretVoucherPoolKeyMaterialProvider(locator, mapper)
}
