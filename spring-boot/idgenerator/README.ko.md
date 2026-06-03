# ID Generator Workshop

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **ID Generator Workshop**을 실행 가능한 Spring Boot 애플리케이션 기능 워크숍 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![ID Generator Workshop Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/spring-boot-idgenerator-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지를 기준으로 삼습니다.

## 시퀀스 다이어그램

이 통합 예제는 `bluetape4k-idgenerators`가 제공하는 네 가지 분산 ID 생성기를 Spring Boot WebFlux REST API로 노출합니다.
Snowflake, ULID, KSUID, Hashids의 특성과 각 알고리즘의 운영상 주의점을 함께 다룹니다.

## API 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/ids/snowflake` | Snowflake Long ID와 파싱된 구성 요소를 생성합니다. |
| `GET` | `/ids/snowflake/parse/{id}` | 기존 Snowflake ID를 역파싱합니다. |
| `GET` | `/ids/snowflake/batch?count=N` | Snowflake ID N개를 배치로 생성합니다(최대 1000개). |
| `GET` | `/ids/ulid` | ULID(26자 Crockford Base32)를 생성합니다. |
| `GET` | `/ids/ulid/batch?count=N` | ULID N개를 배치로 생성합니다. |
| `GET` | `/ids/ksuid` | KSUID(27자 Base62)를 생성합니다. |
| `GET` | `/ids/ksuid/batch?count=N` | KSUID N개를 배치로 생성합니다. |
| `GET` | `/ids/hashids/encode?numbers=1,2,3` | 숫자 배열을 Hashids로 인코딩합니다. |
| `GET` | `/ids/hashids/decode/{hash}` | Hashids 문자열을 숫자 배열로 디코딩합니다. |

## ID 알고리즘 비교

| 알고리즘 | 타입 | 길이 | 정렬성 | 특성 |
|---|---|---|---|---|
| **Snowflake** | `Long` | 64-bit | 시간순 | machine ID 포함, 역파싱 가능, 노드당 초당 4096개 ID |
| **ULID** | `String` | 26자 | 사전식 = 시간순 | 밀리초 정밀도, UUID 호환 |
| **KSUID** | `String` | 27자 | 사전식 = 시간순 | 초 단위 정밀도, 128-bit 랜덤 페이로드 |
| **Hashids** | `String` | 가변 | 없음 | 숫자 난독화, 복원 가능 |

## 사용한 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `Snowflakers.Default` | `bluetape4k-idgenerators` | `IdGeneratorController` | machine ID를 자동 선택하는 Singleton DefaultSnowflake |
| `Snowflake.parse(id)` | `bluetape4k-idgenerators` | `parseSnowflakeId()` | Long ID를 timestamp/machineId/sequence로 역파싱 |
| `Snowflake.nextIds(size)` | `bluetape4k-idgenerators` | `snowflakeBatch()` | sequence 기반 배치 생성 |
| `UlidGenerator` | `bluetape4k-idgenerators` | `IdGeneratorController` | 내장 StatefulMonotonic 지원을 통한 단조 ULID 생성 |
| `KsuidGenerator` | `bluetape4k-idgenerators` | `IdGeneratorController` | 초 단위 기반 정렬 가능 ID |
| `Hashids` | `bluetape4k-idgenerators` | `IdGeneratorController` | salt + minLength 설정으로 난독화 강도 조정 |
| `KLogging` | `bluetape4k-logging` | companion object | 지연 lambda 로깅 |

## bluetape4k 적용 전 / 후

### Snowflake ID 생성

```kotlin
// Before — Direct Twitter Snowflake implementation or external library
val snowflake = SnowflakeIdWorker(workerId = 1, datacenterId = 1)
val id: Long = snowflake.nextId()

// After — bluetape4k Snowflakers.Default (automatic machine ID, singleton)
val id: Long = Snowflakers.Default.nextId()
val parsed: SnowflakeId = Snowflakers.Default.parse(id)
// parsed.timestamp, parsed.machineId, parsed.sequence are available
```

### ULID 생성

```kotlin
// Before — External UlidCreator library
val id = UlidCreator.getUlid().toString()

// After — bluetape4k UlidGenerator (built-in StatefulMonotonic)
val generator = UlidGenerator()
val id: String = generator.nextId()   // 26 characters, monotonic within the same millisecond
```

## 운영상 주의점

### Snowflake — Machine ID 관리

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

### Hashids — 보안 주의

```
WARNING: Hashids is not an encryption algorithm. It is only for obfuscation.

Without a salt, it matches the public Hashids reference configuration and is trivially reversible.
Never use it for security tokens, authentication IDs, or sensitive identifiers.
For real usage: Hashids(salt = "project-specific secret", minHashLength = 8)
```

### ULID와 KSUID 선택 기준

| 상황 | 권장 |
|---|---|
| 밀리초 정밀도의 UUID 대체와 DB 인덱스 효율이 필요할 때 | **ULID** |
| 초 단위 정밀도로 충분하고 더 짧은 랜덤 페이로드를 선호할 때 | **KSUID** |
| Long 타입, 분산 노드 식별, 역파싱이 필요할 때 | **Snowflake** |
| PK나 sequence 같은 숫자 ID를 URL에서 숨길 때 | **Hashids** |

## 실행

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

## 테스트

```bash
./gradlew :spring-boot-idgenerator:test
```

## 참고

- [bluetape4k-idgenerators](https://github.com/bluetape4k/bluetape4k-projects)
- [Snowflake ID (Twitter)](https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake)
- [ULID Specification](https://github.com/ulid/spec)
- [KSUID](https://github.com/segmentio/ksuid)
- [Hashids](https://hashids.org/)
