# graph-abuser-detection

[English](README.md) | [한국어](README.ko.md)

A bluetape4k workshop module demonstrating graph-based abuser detection. The module builds an
**identity graph** that links user accounts to shared identifiers — devices, IP addresses, hashed
phone numbers, and payment tokens — and then applies graph algorithms to surface fraud clusters,
suspicious connection paths, referral loops, and PageRank-based risk scores.

Both a blocking service (`AbuserDetectionService`) and a coroutine-friendly service
(`AbuserDetectionSuspendService`) are provided. Tests run against TinkerGraph (in-process,
no Docker) by default, with optional Neo4j and Memgraph integration tests.

## Graph Schema

### Vertices

| Label | Key property | Other properties | Notes |
|---|---|---|---|
| `User` | `userId` (opaque string / UUID) | `country` (ISO-3166-1 alpha-2) | The primary entity |
| `Device` | `deviceId` (device fingerprint) | `platform` (`"android"`, `"ios"`, `"web"`) | |
| `IpAddress` | `ip` (IPv4 or IPv6) | — | |
| `PhoneNumber` | `phone` (E.164 SHA-256 hex hash) | — | Raw phone numbers must NOT be stored |
| `PaymentMethod` | `paymentToken` (processor token) | — | Raw PAN / CVV must NOT be stored |

### Edges

| Label | From | To | Property | Notes |
|---|---|---|---|---|
| `USES_DEVICE` | `User` | `Device` | `occurredAt` (ISO-8601) | First login from this device |
| `USES_IP` | `User` | `IpAddress` | `occurredAt` (ISO-8601) | First observed connection |
| `HAS_PHONE` | `User` | `PhoneNumber` | `occurredAt` (ISO-8601) | First association |
| `USES_PAYMENT` | `User` | `PaymentMethod` | `occurredAt` (ISO-8601) | First payment attempt |
| `REFERRED_BY` | `User` | `User` | `occurredAt` (ISO-8601) | Referral link (referrer → referred); excluded from cluster BFS |

The four identifier edge types (`USES_DEVICE`, `USES_IP`, `HAS_PHONE`, `USES_PAYMENT`) are
enumerated as typed values in `IdentifierEdgeLabel`. `REFERRED_BY` is intentionally absent
from `IdentifierEdgeLabel.all` — referral alone does not imply shared identity.

## Core Algorithms

| Method | Description |
|---|---|
| `findAbuseCluster(seedUserId)` | BFS over outgoing identifier edges from the seed user, then reverse-traverses back to co-connected users. Returns `AbuseCluster` containing the other users and the shared identifier vertices that link them. The seed user is excluded from `AbuseCluster.users`. |
| `explainSuspicion(userId)` | Returns all outgoing identifier paths from a user as a list (blocking) or cold `Flow` (coroutine). Each `AbusePath` names the target identifier vertex and the edge type. |
| `detectReferralLoops(maxDepth, maxCycles)` | Detects directed cycles in the `REFERRED_BY` subgraph among User vertices. Returns `List<GraphCycle>` (blocking) or cold `Flow<GraphCycle>` (coroutine). |
| `rankSuspiciousUsers(limit)` | Runs PageRank over User vertices and returns results sorted descending by score. Each `SuspiciousUserScore` carries a 1-based rank. Higher PageRank correlates with more shared-identifier connections, which is a proxy for abuse risk. |

### Result types

```kotlin
// A cluster of users connected through shared identifiers
data class AbuseCluster(
    val seedUserId: GraphElementId,
    val users: List<GraphVertex>,            // excludes seed user
    val sharedIdentifiers: List<GraphVertex> // Device/IpAddress/PhoneNumber/PaymentMethod vertices
)

// One link from a user to a shared identifier vertex
data class AbusePath(
    val identifierVertexId: GraphElementId,
    val edgeLabel: IdentifierEdgeLabel       // USES_DEVICE | USES_IP | HAS_PHONE | USES_PAYMENT
)

// PageRank-based suspicion ranking entry
data class SuspiciousUserScore(
    val user: GraphVertex,
    val score: Double,   // raw PageRank value; higher = more suspicious
    val rank: Int        // 1-based position
)
```

## Usage

### Blocking service

```kotlin
val service = AbuserDetectionService(ops, graphName = "fraud_graph")
service.initialize()

// Add vertices (find-or-create by domain key)
val userV   = service.addUser("u-alice", "KR")
val deviceV = service.addDevice("fp-aabbcc", "android")
val ipV     = service.addIpAddress("203.0.113.42")

// Link vertices
service.linkDevice(userV.id, deviceV.id, Instant.now().toString())
service.linkIp(userV.id, ipV.id, Instant.now().toString())

// Detect clusters
val cluster = service.findAbuseCluster(userV.id)
if (cluster.users.isNotEmpty()) {
    println("Cluster size: ${cluster.users.size}")
}

// Explain why a user is suspicious
val paths = service.explainSuspicion(userV.id)
paths.forEach { println("${it.edgeLabel.value} -> ${it.identifierVertexId}") }

// Detect referral fraud rings (default: maxDepth=6, maxCycles=100)
val loops = service.detectReferralLoops()

// PageRank-based risk ranking
val top10 = service.rankSuspiciousUsers(limit = 10)
top10.forEach { println("#${it.rank} ${it.user.id} score=${it.score}") }
```

### Coroutine service

```kotlin
val service = AbuserDetectionSuspendService(ops, graphName = "fraud_graph")
service.initialize()

val userV   = service.addUser("u-bob", "US")
val phoneV  = service.addPhoneNumber(sha256hex("+11234567890"))  // hash before storing
val payV    = service.addPaymentMethod("tok_stripe_xxxx")       // processor token only

service.linkPhone(userV.id, phoneV.id, Instant.now().toString())
service.linkPayment(userV.id, payV.id, Instant.now().toString())

val cluster = service.findAbuseCluster(userV.id)

// explainSuspicion returns a cold Flow
service.explainSuspicion(userV.id).collect { path ->
    println("${path.edgeLabel.value} -> ${path.identifierVertexId}")
}

// detectReferralLoops returns a cold Flow
service.detectReferralLoops(maxDepth = 4).collect { cycle ->
    println("Loop: ${cycle.vertices.map { it.id }}")
}

// rankSuspiciousUsers returns a cold Flow
service.rankSuspiciousUsers(limit = 5).collect { score ->
    println("#${score.rank} score=${score.score}")
}
```

## Security Notes

- **Phone numbers** — store only an E.164-format SHA-256 hex hash via `addPhoneNumber`. The caller
  is responsible for hashing before the call. Raw digits must never enter the graph.
- **Payment tokens** — store only a PCI-safe processor token (e.g. Stripe/Braintree token) via
  `addPaymentMethod`. Raw PAN, expiry, or CVV must never be stored.

## Test Backends

| Backend | Scope | Requirement |
|---|---|---|
| TinkerGraph | `test` (default) | None — runs in-process with no external dependencies |
| Neo4j | `integrationTest` | Docker (uses `bluetape4k-testcontainers` Neo4j launcher) |
| Memgraph | `integrationTest` | Docker (uses `bluetape4k-testcontainers` Memgraph launcher) |

The `test` task excludes tests tagged `@Tag("integration")`. The `integrationTest` task includes
only those tagged tests and requires Docker to be running.

## Running Tests

```bash
# Default tests — TinkerGraph only, no Docker required
./gradlew :graph-abuser-detection:test

# Integration tests — Neo4j + Memgraph via Docker
./gradlew :graph-abuser-detection:integrationTest

# Both
./gradlew :graph-abuser-detection:test :graph-abuser-detection:integrationTest

# Single test class
./gradlew :graph-abuser-detection:test \
  --tests "io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionServiceTest"
```

## Package Structure

```
io.bluetape4k.workshop.graph.abuser
├── model
│   ├── AbuseCluster.kt          — cluster result holding co-connected users + shared identifiers
│   ├── AbusePath.kt             — single user-to-identifier edge path
│   ├── IdentifierEdgeLabel.kt   — typed enum of the four identifier edge types
│   └── SuspiciousUserScore.kt   — PageRank result for one user
├── schema
│   └── AbuserDetectionSchema.kt — vertex labels (User, Device, IpAddress, PhoneNumber, PaymentMethod)
│                                   and edge labels (USES_DEVICE, USES_IP, HAS_PHONE, USES_PAYMENT, REFERRED_BY)
└── service
    ├── AbuserDetectionService.kt        — blocking implementation (GraphOperations)
    └── AbuserDetectionSuspendService.kt — coroutine implementation (GraphSuspendOperations + Flow)
```
