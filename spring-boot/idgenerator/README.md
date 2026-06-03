# ID Generator Workshop

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **ID Generator Workshop** as a runnable Spring Boot application feature workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![ID Generator Workshop Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-idgenerator-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springboot` as the source of truth when comparing this README with the code.

![ID Generator Workshop architecture diagram](../../docs/images/readme-diagrams/spring-boot-idgenerator-architecture-01.png)

## Flow Diagram

1. Prepare the local runtime required by `spring-boot-idgenerator`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

This integration example exposes four distributed ID generators from `bluetape4k-idgenerators` through a Spring Boot WebFlux REST API.
It covers the characteristics of Snowflake, ULID, KSUID, and Hashids, along with operational cautions for each algorithm.

## Architecture

![idgenerator Architecture diagram](../../docs/images/readme-diagrams/spring-boot-idgenerator-architecture-01.png)

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/ids/snowflake` | Generates a Snowflake Long ID + parsed components |
| `GET` | `/ids/snowflake/parse/{id}` | Reverse-parses an existing Snowflake ID |
| `GET` | `/ids/snowflake/batch?count=N` | Generates N Snowflake IDs in a batch (maximum 1000) |
| `GET` | `/ids/ulid` | Generates a ULID (26-character Crockford Base32) |
| `GET` | `/ids/ulid/batch?count=N` | Generates N ULIDs in a batch |
| `GET` | `/ids/ksuid` | Generates a KSUID (27-character Base62) |
| `GET` | `/ids/ksuid/batch?count=N` | Generates N KSUIDs in a batch |
| `GET` | `/ids/hashids/encode?numbers=1,2,3` | Encodes an array of numbers into Hashids |
| `GET` | `/ids/hashids/decode/{hash}` | Decodes a Hashids string into an array of numbers |

## ID Algorithm Comparison

| Algorithm | Type | Length | Ordering | Characteristics |
|---|---|---|---|---|
| **Snowflake** | `Long` | 64-bit | Chronological | Includes machine ID, reverse-parseable, 4096 IDs/sec/node |
| **ULID** | `String` | 26 characters | Lexicographic = chronological | Millisecond precision, UUID-compatible |
| **KSUID** | `String` | 27 characters | Lexicographic = chronological | Second precision, 128-bit random payload |
| **Hashids** | `String` | Variable | No | Numeric obfuscation, reversible |

## bluetape4k Features Used

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| `Snowflakers.Default` | `bluetape4k-idgenerators` | `IdGeneratorController` | Singleton DefaultSnowflake with automatic machine ID selection |
| `Snowflake.parse(id)` | `bluetape4k-idgenerators` | `parseSnowflakeId()` | Reverse-parses a Long ID into timestamp/machineId/sequence |
| `Snowflake.nextIds(size)` | `bluetape4k-idgenerators` | `snowflakeBatch()` | Sequence-based batch generation |
| `UlidGenerator` | `bluetape4k-idgenerators` | `IdGeneratorController` | Monotonic ULID generation with built-in StatefulMonotonic support |
| `KsuidGenerator` | `bluetape4k-idgenerators` | `IdGeneratorController` | Second-based sortable IDs |
| `Hashids` | `bluetape4k-idgenerators` | `IdGeneratorController` | Adjusts obfuscation strength through salt + minLength settings |
| `KLogging` | `bluetape4k-logging` | companion object | Lazy lambda logging |

## bluetape4k Before / After

### Snowflake ID Generation

```kotlin
// Before — Direct Twitter Snowflake implementation or external library
val snowflake = SnowflakeIdWorker(workerId = 1, datacenterId = 1)
val id: Long = snowflake.nextId()

// After — bluetape4k Snowflakers.Default (automatic machine ID, singleton)
val id: Long = Snowflakers.Default.nextId()
val parsed: SnowflakeId = Snowflakers.Default.parse(id)
// parsed.timestamp, parsed.machineId, parsed.sequence are available
```

### ULID Generation

```kotlin
// Before — External UlidCreator library
val id = UlidCreator.getUlid().toString()

// After — bluetape4k UlidGenerator (built-in StatefulMonotonic)
val generator = UlidGenerator()
val id: String = generator.nextId()   // 26 characters, monotonic within the same millisecond
```

## Operational Cautions

### Snowflake — Machine ID Management

```
WARNING: ID collisions occur if two nodes with the same machine ID run at the same time.

Snowflakers.Default: automatically selected from the network interface MAC address (recommended for single-NIC environments)
Snowflakers.default(machineId = N): explicit assignment, recommended for Pod/Container environments

Redis-based dynamic worker-id allocation can be implemented with Redisson RAtomicLong or SET NX/EXPIRE.
This pattern is covered in follow-up work for issue #62.
```

### Snowflake — Clock Rollback

```
WARNING: Duplicate IDs can occur if the server clock moves backward.

DefaultSnowflake throws IllegalStateException when it detects clock rollback.
Pay special attention to NTP synchronization and cloud instance restarts.
```

### Hashids — Security Caution

```
WARNING: Hashids is not an encryption algorithm. It is only for obfuscation.

Without a salt, it matches the public Hashids reference configuration and is trivially reversible.
Never use it for security tokens, authentication IDs, or sensitive identifiers.
For real usage: Hashids(salt = "project-specific secret", minHashLength = 8)
```

### ULID vs KSUID Selection Criteria

| Situation | Recommendation |
|---|---|
| UUID replacement and DB index efficiency with millisecond precision | **ULID** |
| Second-level precision is enough and a shorter random payload is preferred | **KSUID** |
| Long type, distributed node identity, and reverse parsing are required | **Snowflake** |
| Hide numeric IDs in URLs, such as PKs and sequences | **Hashids** |

## Run

```bash
./gradlew :spring-boot-idgenerator:bootRun

# Generate a Snowflake ID
curl http://localhost:8080/ids/snowflake

# Generate 10 Snowflake IDs in a batch
curl "http://localhost:8080/ids/snowflake/batch?count=10"

# Generate a ULID
curl http://localhost:8080/ids/ulid

# Generate a KSUID
curl http://localhost:8080/ids/ksuid

# Encode with Hashids
curl "http://localhost:8080/ids/hashids/encode?numbers=1,2,3"
```

## Test

```bash
./gradlew :spring-boot-idgenerator:test
```

## References

- [bluetape4k-idgenerators](https://github.com/bluetape4k/bluetape4k-projects)
- [Snowflake ID (Twitter)](https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake)
- [ULID Specification](https://github.com/ulid/spec)
- [KSUID](https://github.com/segmentio/ksuid)
- [Hashids](https://hashids.org/)
