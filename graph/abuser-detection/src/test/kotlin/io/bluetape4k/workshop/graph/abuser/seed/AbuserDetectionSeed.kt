package io.bluetape4k.workshop.graph.abuser.seed

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionSuspendService
import java.io.Serializable

private const val SEED_TIMESTAMP = "2026-01-01T00:00:00Z"

/**
 * [seedSharedIdentifiers]가 생성한 모든 정점의 스냅샷입니다.
 *
 * @property user1 첫 번째 클러스터 구성원입니다(user2와 deviceA 및 ipA 공유).
 * @property user2 두 번째 클러스터 구성원입니다(user1과 deviceA 및 ipA 공유, user3과 deviceA 공유).
 * @property user3 세 번째 클러스터 구성원입니다(user1 및 user2와 deviceA만 공유).
 * @property deviceA 공유 디바이스입니다. user1, user2, user3을 연결합니다.
 * @property ipA 공유 IP 주소입니다. user1과 user2만 연결합니다.
 * @property unrelatedUser 공유 식별자가 없는 격리 사용자입니다.
 * @property deviceB unrelatedUser에만 속한 비공개 디바이스입니다.
 */
data class AbuserDetectionSeed(
    val user1: GraphVertex,
    val user2: GraphVertex,
    val user3: GraphVertex,
    val deviceA: GraphVertex,
    val ipA: GraphVertex,
    val unrelatedUser: GraphVertex,
    val deviceB: GraphVertex,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

// ─────────────────────────────────────────────────────────────────────────
// 블로킹 헬퍼
// ─────────────────────────────────────────────────────────────────────────

/**
 * device/IP 식별자를 공유하는 사용자 클러스터와 격리 사용자 하나를 생성합니다.
 *
 * 그래프 구조:
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

    // 클러스터 edge
    service.linkDevice(user1.id, deviceA.id, SEED_TIMESTAMP)
    service.linkDevice(user2.id, deviceA.id, SEED_TIMESTAMP)
    service.linkDevice(user3.id, deviceA.id, SEED_TIMESTAMP)
    service.linkIp(user1.id, ipA.id, SEED_TIMESTAMP)
    service.linkIp(user2.id, ipA.id, SEED_TIMESTAMP)

    // 격리 사용자 edge(비공개 디바이스 — 공유 없음)
    service.linkDevice(unrelatedUser.id, deviceB.id, SEED_TIMESTAMP)

    return AbuserDetectionSeed(user1, user2, user3, deviceA, ipA, unrelatedUser, deviceB)
}

/**
 * 기존 [seed] 위에 REFERRED_BY cycle을 추가합니다: user1 → user2 → user3 → user1.
 */
fun seedReferralLoop(service: AbuserDetectionService, seed: AbuserDetectionSeed) {
    service.linkReferral(seed.user1.id, seed.user2.id, SEED_TIMESTAMP)
    service.linkReferral(seed.user2.id, seed.user3.id, SEED_TIMESTAMP)
    service.linkReferral(seed.user3.id, seed.user1.id, SEED_TIMESTAMP)
}

// ─────────────────────────────────────────────────────────────────────────
// 코루틴 헬퍼
// ─────────────────────────────────────────────────────────────────────────

/**
 * [seedSharedIdentifiers]의 suspend 변형입니다.
 */
suspend fun seedSharedIdentifiers(service: AbuserDetectionSuspendService): AbuserDetectionSeed {
    val user1 = service.addUser("user-1", "KR")
    val user2 = service.addUser("user-2", "KR")
    val user3 = service.addUser("user-3", "US")
    val unrelatedUser = service.addUser("unrelated-user", "JP")

    val deviceA = service.addDevice("device-A", "android")
    val ipA = service.addIpAddress("192.168.1.1")
    val deviceB = service.addDevice("device-B", "ios")

    // 클러스터 edge
    service.linkDevice(user1.id, deviceA.id, SEED_TIMESTAMP)
    service.linkDevice(user2.id, deviceA.id, SEED_TIMESTAMP)
    service.linkDevice(user3.id, deviceA.id, SEED_TIMESTAMP)
    service.linkIp(user1.id, ipA.id, SEED_TIMESTAMP)
    service.linkIp(user2.id, ipA.id, SEED_TIMESTAMP)

    // 격리 사용자 edge
    service.linkDevice(unrelatedUser.id, deviceB.id, SEED_TIMESTAMP)

    return AbuserDetectionSeed(user1, user2, user3, deviceA, ipA, unrelatedUser, deviceB)
}

/**
 * [seedReferralLoop]의 suspend 변형입니다.
 */
suspend fun seedReferralLoop(service: AbuserDetectionSuspendService, seed: AbuserDetectionSeed) {
    service.linkReferral(seed.user1.id, seed.user2.id, SEED_TIMESTAMP)
    service.linkReferral(seed.user2.id, seed.user3.id, SEED_TIMESTAMP)
    service.linkReferral(seed.user3.id, seed.user1.id, SEED_TIMESTAMP)
}
