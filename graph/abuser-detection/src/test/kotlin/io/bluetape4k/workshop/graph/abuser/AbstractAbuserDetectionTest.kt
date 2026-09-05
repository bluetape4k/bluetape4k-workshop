package io.bluetape4k.workshop.graph.abuser

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBePositive
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.workshop.graph.abuser.model.IdentifierEdgeLabel
import io.bluetape4k.workshop.graph.abuser.model.SuspiciousUserScore
import io.bluetape4k.workshop.graph.abuser.schema.DeviceLabel
import io.bluetape4k.workshop.graph.abuser.schema.IpAddressLabel
import io.bluetape4k.workshop.graph.abuser.schema.PaymentMethodLabel
import io.bluetape4k.workshop.graph.abuser.schema.PhoneNumberLabel
import io.bluetape4k.workshop.graph.abuser.schema.UserLabel
import io.bluetape4k.workshop.graph.abuser.seed.AbuserDetectionSeed
import io.bluetape4k.workshop.graph.abuser.seed.seedReferralLoop
import io.bluetape4k.workshop.graph.abuser.seed.seedSharedIdentifiers
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [AbuserDetectionService]용 추상 테스트 suite입니다.
 *
 * 구체 하위 클래스는 특정 그래프 백엔드가 뒷받침하는 [ops]와 [service]를 제공합니다.
 * 각 테스트는 깨끗한 그래프에서 실행됩니다. [cleanGraph]가 매 테스트 전에 drop 후 다시 초기화합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractAbuserDetectionTest {

    protected abstract val graphName: String
    protected abstract val ops: GraphOperations
    protected abstract val service: AbuserDetectionService

    @BeforeEach
    fun cleanGraph() {
        // bluetape4k-graph 0.6.0 requires selecting the logical graph before dropping it.
        ops.createGraph(graphName)
        ops.dropGraph(graphName)
        service.initialize()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 정점 변경 메서드 테스트
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addUser creates a new User vertex`() {
        val user = service.addUser("user-1", "KR")

        user.label shouldBeEqualTo UserLabel.label
        user.properties[UserLabel.userId.name] shouldBeEqualTo "user-1"
        user.properties[UserLabel.country.name] shouldBeEqualTo "KR"
    }

    @Test
    fun `service rejects blank graph name`() {
        assertFailsWith<IllegalArgumentException> {
            AbuserDetectionService(ops, " ")
        }
    }

    @Test
    fun `addUser returns existing vertex on second call (idempotent)`() {
        val first = service.addUser("user-1", "KR")
        val second = service.addUser("user-1", "KR")

        first.id shouldBeEqualTo second.id
    }

    @Test
    fun `addDevice creates a new Device vertex`() {
        val device = service.addDevice("device-1", "android")

        device.label shouldBeEqualTo DeviceLabel.label
        device.properties[DeviceLabel.deviceId.name] shouldBeEqualTo "device-1"
        device.properties[DeviceLabel.platform.name] shouldBeEqualTo "android"
    }

    @Test
    fun `addIpAddress creates a new IpAddress vertex`() {
        val ip = service.addIpAddress("10.0.0.1")

        ip.label shouldBeEqualTo IpAddressLabel.label
        ip.properties[IpAddressLabel.ip.name] shouldBeEqualTo "10.0.0.1"
    }

    @Test
    fun `addPhoneNumber creates a PhoneNumber vertex`() {
        val phone = service.addPhoneNumber("sha256-hashed-phone-value")

        phone.label shouldBeEqualTo PhoneNumberLabel.label
        phone.properties[PhoneNumberLabel.phone.name] shouldBeEqualTo "sha256-hashed-phone-value"
    }

    @Test
    fun `addPaymentMethod creates a PaymentMethod vertex`() {
        val payment = service.addPaymentMethod("tok_visa_4242")

        payment.label shouldBeEqualTo PaymentMethodLabel.label
        payment.properties[PaymentMethodLabel.paymentToken.name] shouldBeEqualTo "tok_visa_4242"
    }

    // ─────────────────────────────────────────────────────────────────────
    // findAbuseCluster 테스트
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findAbuseCluster returns cluster users for user sharing device`() {
        val seed: AbuserDetectionSeed = seedSharedIdentifiers(service)

        val cluster = service.findAbuseCluster(seed.user1.id)

        cluster.users.shouldNotBeEmpty()
        val clusterUserIds = cluster.users.map { it.id }
        clusterUserIds shouldContain seed.user2.id
        clusterUserIds shouldContain seed.user3.id
    }

    @Test
    fun `findAbuseCluster cluster users excludes seed user`() {
        val seed = seedSharedIdentifiers(service)

        val cluster = service.findAbuseCluster(seed.user1.id)

        val clusterUserIds = cluster.users.map { it.id }
        clusterUserIds shouldNotContain seed.user1.id
    }

    @Test
    fun `findAbuseCluster sharedIdentifiers contains the shared device`() {
        val seed = seedSharedIdentifiers(service)

        val cluster = service.findAbuseCluster(seed.user1.id)

        cluster.sharedIdentifiers.shouldNotBeEmpty()
        cluster.sharedIdentifiers.map { it.id } shouldContain seed.deviceA.id
    }

    @Test
    fun `findAbuseCluster returns empty cluster for isolated user`() {
        val seed = seedSharedIdentifiers(service)

        val cluster = service.findAbuseCluster(seed.unrelatedUser.id)

        // unrelatedUser는 클러스터 사용자와 식별자를 공유하지 않습니다.
        cluster.users.shouldBeEmpty()
    }

    @Test
    fun `findAbuseCluster returns empty cluster for unknown vertex ID`() {
        val unknownId = GraphElementId("99999999")

        val cluster = service.findAbuseCluster(unknownId)

        cluster.users.shouldBeEmpty()
        cluster.sharedIdentifiers.shouldBeEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────
    // explainSuspicion 테스트
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `explainSuspicion returns AbusePaths for connected user`() {
        val seed = seedSharedIdentifiers(service)

        val paths = service.explainSuspicion(seed.user1.id)

        // user1은 USES_DEVICE(deviceA)와 USES_IP(ipA) 간선을 가집니다.
        paths shouldHaveSize 2
        val identifierIds = paths.map { it.identifierVertexId }
        identifierIds shouldContain seed.deviceA.id
        identifierIds shouldContain seed.ipA.id
    }

    @Test
    fun `explainSuspicion returns only outgoing private identifiers for isolated user`() {
        val seed = seedSharedIdentifiers(service)

        val paths = service.explainSuspicion(seed.unrelatedUser.id)

        // unrelatedUser는 deviceB만 가지며 클러스터 사용자와 공유 식별자가 없습니다.
        // 하지만 explainSuspicion은 모든 outgoing 식별자 간선을 반환하므로 deviceB가 나타납니다.
        // 이 테스트는 null/crash가 없고 클러스터 디바이스가 나타나지 않는지 검증합니다.
        val identifierIds = paths.map { it.identifierVertexId }
        identifierIds shouldNotContain seed.deviceA.id
        identifierIds shouldNotContain seed.ipA.id
    }

    @Test
    fun `linkDevice rejects invalid endpoint labels`() {
        val seed = seedSharedIdentifiers(service)

        assertFailsWith<IllegalArgumentException> {
            service.linkDevice(seed.deviceA.id, seed.user1.id, "2026-01-01T00:00:00Z")
        }
    }

    @Test
    fun `linkDevice rejects missing device endpoint`() {
        val seed = seedSharedIdentifiers(service)

        assertFailsWith<IllegalArgumentException> {
            service.linkDevice(seed.user1.id, GraphElementId("99999998"), "2026-01-01T00:00:00Z")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // detectReferralLoops 테스트
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `detectReferralLoops returns detected cycles in referral graph`() {
        val seed = seedSharedIdentifiers(service)
        seedReferralLoop(service, seed)

        val cycles = service.detectReferralLoops()

        cycles.shouldNotBeEmpty()
    }

    @Test
    fun `detectReferralLoops returns empty list when no cycles exist`() {
        // 추천 링크를 추가하지 않았습니다.
        service.addUser("u-a", "KR")
        service.addUser("u-b", "KR")

        val cycles = service.detectReferralLoops()

        cycles.shouldBeEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────
    // rankSuspiciousUsers 테스트
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `rankSuspiciousUsers returns non-empty list with valid sequential ranks`() {
        val seed = seedSharedIdentifiers(service)
        seedReferralLoop(service, seed)

        val scores = service.rankSuspiciousUsers(limit = 10)

        scores.shouldNotBeEmpty()
        scores.forEachIndexed { index, score ->
            score.rank shouldBeEqualTo (index + 1)
            score.score shouldBeGreaterThan 0.0
            score.user.label shouldBeEqualTo UserLabel.label
        }
    }

    @Test
    fun `rankSuspiciousUsers respects limit`() {
        val seed = seedSharedIdentifiers(service)
        seedReferralLoop(service, seed)

        val scores = service.rankSuspiciousUsers(limit = 2)

        scores shouldHaveSize 2
        scores.first().rank shouldBeEqualTo 1
        scores.last().rank shouldBeEqualTo 2
    }

    @Test
    fun `domain model rejects invalid labels and ranks`() {
        val seed = seedSharedIdentifiers(service)

        assertFailsWith<IllegalArgumentException> {
            IdentifierEdgeLabel(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            SuspiciousUserScore(user = seed.user1, score = 0.1, rank = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SuspiciousUserScore(user = seed.user1, score = 0.0, rank = 1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // initialize 멱등성 테스트
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `initialize is idempotent - calling twice does not throw`() {
        service.initialize()
        service.initialize()
        // 예외가 없으면 성공입니다.
    }
}
