package io.bluetape4k.workshop.graph.abuser.seed

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionSuspendService

private const val SEED_TIMESTAMP = "2026-01-01T00:00:00Z"

/**
 * Snapshot of all vertices created by [seedSharedIdentifiers].
 *
 * @property user1 first cluster member (shares deviceA and ipA with user2)
 * @property user2 second cluster member (shares deviceA and ipA with user1; shares deviceA with user3)
 * @property user3 third cluster member (shares only deviceA with user1 and user2)
 * @property deviceA shared device — connects user1, user2, and user3
 * @property ipA shared IP address — connects user1 and user2 only
 * @property unrelatedUser isolated user with no shared identifiers
 * @property deviceB private device belonging only to unrelatedUser
 */
data class AbuserDetectionSeed(
    val user1: GraphVertex,
    val user2: GraphVertex,
    val user3: GraphVertex,
    val deviceA: GraphVertex,
    val ipA: GraphVertex,
    val unrelatedUser: GraphVertex,
    val deviceB: GraphVertex,
)

// ─────────────────────────────────────────────────────────────────────────
// Blocking helpers
// ─────────────────────────────────────────────────────────────────────────

/**
 * Creates a cluster of users sharing device/IP identifiers plus one isolated user.
 *
 * Graph structure:
 * ```
 * user1 ──USES_DEVICE──► deviceA ◄──USES_DEVICE── user2
 * user1 ──USES_IP──────► ipA     ◄──USES_IP─────  user2
 * user3 ──USES_DEVICE──► deviceA
 * unrelatedUser ──USES_DEVICE──► deviceB   (no shared identifiers)
 * ```
 */
fun seedSharedIdentifiers(service: AbuserDetectionService): AbuserDetectionSeed {
    val user1 = service.addUser("user-1", "KR")
    val user2 = service.addUser("user-2", "KR")
    val user3 = service.addUser("user-3", "US")
    val unrelatedUser = service.addUser("unrelated-user", "JP")

    val deviceA = service.addDevice("device-A", "android")
    val ipA = service.addIpAddress("192.168.1.1")
    val deviceB = service.addDevice("device-B", "ios")

    // Cluster edges
    service.linkDevice(user1.id, deviceA.id, SEED_TIMESTAMP)
    service.linkDevice(user2.id, deviceA.id, SEED_TIMESTAMP)
    service.linkDevice(user3.id, deviceA.id, SEED_TIMESTAMP)
    service.linkIp(user1.id, ipA.id, SEED_TIMESTAMP)
    service.linkIp(user2.id, ipA.id, SEED_TIMESTAMP)

    // Isolated user edge (private device — no sharing)
    service.linkDevice(unrelatedUser.id, deviceB.id, SEED_TIMESTAMP)

    return AbuserDetectionSeed(user1, user2, user3, deviceA, ipA, unrelatedUser, deviceB)
}

/**
 * Adds a REFERRED_BY cycle on top of an existing [seed]: user1 → user2 → user3 → user1.
 */
fun seedReferralLoop(service: AbuserDetectionService, seed: AbuserDetectionSeed) {
    service.linkReferral(seed.user1.id, seed.user2.id, SEED_TIMESTAMP)
    service.linkReferral(seed.user2.id, seed.user3.id, SEED_TIMESTAMP)
    service.linkReferral(seed.user3.id, seed.user1.id, SEED_TIMESTAMP)
}

// ─────────────────────────────────────────────────────────────────────────
// Coroutine helpers
// ─────────────────────────────────────────────────────────────────────────

/**
 * Suspend variant of [seedSharedIdentifiers].
 */
suspend fun seedSharedIdentifiers(service: AbuserDetectionSuspendService): AbuserDetectionSeed {
    val user1 = service.addUser("user-1", "KR")
    val user2 = service.addUser("user-2", "KR")
    val user3 = service.addUser("user-3", "US")
    val unrelatedUser = service.addUser("unrelated-user", "JP")

    val deviceA = service.addDevice("device-A", "android")
    val ipA = service.addIpAddress("192.168.1.1")
    val deviceB = service.addDevice("device-B", "ios")

    // Cluster edges
    service.linkDevice(user1.id, deviceA.id, SEED_TIMESTAMP)
    service.linkDevice(user2.id, deviceA.id, SEED_TIMESTAMP)
    service.linkDevice(user3.id, deviceA.id, SEED_TIMESTAMP)
    service.linkIp(user1.id, ipA.id, SEED_TIMESTAMP)
    service.linkIp(user2.id, ipA.id, SEED_TIMESTAMP)

    // Isolated user edge
    service.linkDevice(unrelatedUser.id, deviceB.id, SEED_TIMESTAMP)

    return AbuserDetectionSeed(user1, user2, user3, deviceA, ipA, unrelatedUser, deviceB)
}

/**
 * Suspend variant of [seedReferralLoop].
 */
suspend fun seedReferralLoop(service: AbuserDetectionSuspendService, seed: AbuserDetectionSeed) {
    service.linkReferral(seed.user1.id, seed.user2.id, SEED_TIMESTAMP)
    service.linkReferral(seed.user2.id, seed.user3.id, SEED_TIMESTAMP)
    service.linkReferral(seed.user3.id, seed.user1.id, SEED_TIMESTAMP)
}
