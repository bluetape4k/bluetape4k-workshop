package io.bluetape4k.workshop.graph.abuser

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBePositive
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.graph.abuser.model.SuspiciousUserScore
import io.bluetape4k.workshop.graph.abuser.schema.DeviceLabel
import io.bluetape4k.workshop.graph.abuser.schema.IpAddressLabel
import io.bluetape4k.workshop.graph.abuser.schema.PaymentMethodLabel
import io.bluetape4k.workshop.graph.abuser.schema.PhoneNumberLabel
import io.bluetape4k.workshop.graph.abuser.schema.UserLabel
import io.bluetape4k.workshop.graph.abuser.seed.AbuserDetectionSeed
import io.bluetape4k.workshop.graph.abuser.seed.seedReferralLoop
import io.bluetape4k.workshop.graph.abuser.seed.seedSharedIdentifiers
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionSuspendService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Abstract test suite for [AbuserDetectionSuspendService].
 *
 * Concrete subclasses supply [ops] and [service] backed by a specific graph backend.
 * Each test runs against a clean graph — [cleanGraph] drops and re-initializes before every test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractAbuserDetectionSuspendTest {

    protected abstract val graphName: String
    protected abstract val ops: GraphSuspendOperations
    protected abstract val service: AbuserDetectionSuspendService

    @BeforeEach
    fun cleanGraph() = runSuspendIO {
        ops.dropGraph(graphName)
        service.initialize()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Vertex mutator tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addUser creates a new User vertex`() = runSuspendIO {
        val user = service.addUser("user-1", "KR")

        user.label shouldBeEqualTo UserLabel.label
        user.properties[UserLabel.userId.name] shouldBeEqualTo "user-1"
        user.properties[UserLabel.country.name] shouldBeEqualTo "KR"
    }

    @Test
    fun `addUser returns existing vertex on second call (idempotent)`() = runSuspendIO {
        val first = service.addUser("user-1", "KR")
        val second = service.addUser("user-1", "KR")

        first.id shouldBeEqualTo second.id
    }

    @Test
    fun `addDevice creates a new Device vertex`() = runSuspendIO {
        val device = service.addDevice("device-1", "android")

        device.label shouldBeEqualTo DeviceLabel.label
        device.properties[DeviceLabel.deviceId.name] shouldBeEqualTo "device-1"
        device.properties[DeviceLabel.platform.name] shouldBeEqualTo "android"
    }

    @Test
    fun `addIpAddress creates a new IpAddress vertex`() = runSuspendIO {
        val ip = service.addIpAddress("10.0.0.1")

        ip.label shouldBeEqualTo IpAddressLabel.label
        ip.properties[IpAddressLabel.ip.name] shouldBeEqualTo "10.0.0.1"
    }

    @Test
    fun `addPhoneNumber creates a PhoneNumber vertex`() = runSuspendIO {
        val phone = service.addPhoneNumber("sha256-hashed-phone-value")

        phone.label shouldBeEqualTo PhoneNumberLabel.label
        phone.properties[PhoneNumberLabel.phone.name] shouldBeEqualTo "sha256-hashed-phone-value"
    }

    @Test
    fun `addPaymentMethod creates a PaymentMethod vertex`() = runSuspendIO {
        val payment = service.addPaymentMethod("tok_visa_4242")

        payment.label shouldBeEqualTo PaymentMethodLabel.label
        payment.properties[PaymentMethodLabel.paymentToken.name] shouldBeEqualTo "tok_visa_4242"
    }

    // ─────────────────────────────────────────────────────────────────────
    // findAbuseCluster tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findAbuseCluster returns cluster users for user sharing device`() = runSuspendIO {
        val seed: AbuserDetectionSeed = seedSharedIdentifiers(service)

        val cluster = service.findAbuseCluster(seed.user1.id)

        cluster.users.shouldNotBeEmpty()
        val clusterUserIds = cluster.users.map { it.id }
        clusterUserIds shouldContain seed.user2.id
        clusterUserIds shouldContain seed.user3.id
    }

    @Test
    fun `findAbuseCluster cluster users excludes seed user`() = runSuspendIO {
        val seed = seedSharedIdentifiers(service)

        val cluster = service.findAbuseCluster(seed.user1.id)

        val clusterUserIds = cluster.users.map { it.id }
        clusterUserIds shouldNotContain seed.user1.id
    }

    @Test
    fun `findAbuseCluster sharedIdentifiers contains the shared device`() = runSuspendIO {
        val seed = seedSharedIdentifiers(service)

        val cluster = service.findAbuseCluster(seed.user1.id)

        cluster.sharedIdentifiers.shouldNotBeEmpty()
        cluster.sharedIdentifiers.map { it.id } shouldContain seed.deviceA.id
    }

    @Test
    fun `findAbuseCluster returns empty cluster for isolated user`() = runSuspendIO {
        val seed = seedSharedIdentifiers(service)

        val cluster = service.findAbuseCluster(seed.unrelatedUser.id)

        cluster.users.shouldBeEmpty()
    }

    @Test
    fun `findAbuseCluster returns empty cluster for unknown vertex ID`() = runSuspendIO {
        val unknownId = GraphElementId("non-existent-vertex-id-9999")

        val cluster = service.findAbuseCluster(unknownId)

        cluster.users.shouldBeEmpty()
        cluster.sharedIdentifiers.shouldBeEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────
    // explainSuspicion tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `explainSuspicion flow emits AbusePaths for connected user`() = runSuspendIO {
        val seed = seedSharedIdentifiers(service)

        val paths = service.explainSuspicion(seed.user1.id).toList()

        // user1 has USES_DEVICE (deviceA) and USES_IP (ipA) edges
        paths shouldHaveSize 2
        val identifierIds = paths.map { it.identifierVertexId }
        identifierIds shouldContain seed.deviceA.id
        identifierIds shouldContain seed.ipA.id
    }

    @Test
    fun `explainSuspicion flow does not emit cluster device for isolated user`() = runSuspendIO {
        val seed = seedSharedIdentifiers(service)

        val paths = service.explainSuspicion(seed.unrelatedUser.id).toList()

        val identifierIds = paths.map { it.identifierVertexId }
        identifierIds shouldNotContain seed.deviceA.id
        identifierIds shouldNotContain seed.ipA.id
    }

    // ─────────────────────────────────────────────────────────────────────
    // detectReferralLoops tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `detectReferralLoops flow emits detected cycles`() = runSuspendIO {
        val seed = seedSharedIdentifiers(service)
        seedReferralLoop(service, seed)

        val cycles = service.detectReferralLoops().toList()

        cycles.shouldNotBeEmpty()
    }

    @Test
    fun `detectReferralLoops flow is empty when no cycles exist`() = runSuspendIO {
        service.addUser("u-a", "KR")
        service.addUser("u-b", "KR")

        val cycles = service.detectReferralLoops().toList()

        cycles.shouldBeEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────
    // rankSuspiciousUsers tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `rankSuspiciousUsers flow emits non-empty ordered scores`() = runSuspendIO {
        val seed = seedSharedIdentifiers(service)
        seedReferralLoop(service, seed)

        val scores: List<SuspiciousUserScore> = service.rankSuspiciousUsers(limit = 10).toList()

        scores.shouldNotBeEmpty()
        scores.forEachIndexed { index, score ->
            score.rank shouldBeEqualTo (index + 1)
            score.score shouldBeGreaterThan 0.0
            score.user.label shouldBeEqualTo UserLabel.label
        }
    }

    @Test
    fun `rankSuspiciousUsers respects limit`() = runSuspendIO {
        val seed = seedSharedIdentifiers(service)
        seedReferralLoop(service, seed)

        val scores = service.rankSuspiciousUsers(limit = 2).toList()

        scores shouldHaveSize 2
        scores.first().rank shouldBeEqualTo 1
        scores.last().rank shouldBeEqualTo 2
    }

    // ─────────────────────────────────────────────────────────────────────
    // Flow cancellation test (suspend-only)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `rankSuspiciousUsers flow honours take(1) cancellation`() = runSuspendIO {
        val seed = seedSharedIdentifiers(service)
        seedReferralLoop(service, seed)

        // onEach delay makes the flow slow so cancellation is meaningful;
        // take(1) cancels the flow after the first emission
        val first: List<SuspiciousUserScore> = service.rankSuspiciousUsers(limit = 10)
            .onEach { delay(10) }
            .take(1)
            .toList()

        first shouldHaveSize 1
        first.first().rank shouldBeEqualTo 1
    }

    // ─────────────────────────────────────────────────────────────────────
    // initialize idempotency test
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `initialize is idempotent - calling twice does not throw`() = runSuspendIO {
        service.initialize()
        service.initialize()
        // no exception = success
    }
}
