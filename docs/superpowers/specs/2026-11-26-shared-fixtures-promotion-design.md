# 교차 주제 테스트 인프라의 단계적 승격 설계

## 문서 상태

- 관련 이슈: [#1069](https://github.com/bluetape4k/bluetape4k-workshop/issues/1069)
- 대상 브랜치: `feat/shared-fixtures-promotion`
- 기준 커밋: `develop`의 `3ede3b5b0c2e8f51f22e137a075ec6f9156f2939`
- 문서 언어: 한국어
- 현재 실행 범위: Phase A~D의 교차 주제 공통 test fixture 추출
- 포함 후보: `OwnedRedisNamespace`, `MutableClock`, `WorkerPid`, artifact/journal 하부 primitive
- 후속 범위: 안정화된 공통 계약의 별도 `bluetape4k-*` 라이브러리 승격 검토

이 문서는 Workshop의 여러 주제에서 반복되는 코드를 단순히 하나의 `shared` 디렉터리로 모으는 계획이 아니다. 두 개 이상의 독립 소비자가 같은 안정 계약을 실제로 공유하는지 확인하고, 예제 도메인의 의미를 침범하지 않는 테스트 인프라만 root `shared` test fixture로 추출한다. 외부 소비자에게 안정 API가 필요한 경우에는 별도의 `bluetape4k-*` 라이브러리 승격 후보로 기록하되, 이번 변경에서 소비자 라이브러리의 릴리스나 BOM 변경까지 확장하지 않는다.

## 문제와 근거

현재 Workshop에는 `shared` 의존성을 선언한 Gradle 모듈이 많지만, 실제 소스 소비는 소수 모듈에 집중되어 있다. 모듈별 조사에서 다음 사실을 확인했다.

1. `operations/job-console-core`의 `OwnedRedisNamespace`와 `commerce/concert-ticket-flash-sale`의 `TicketOwnedRedisNamespace`는 namespace 파싱, 소유 키 판정, bounded `SCAN`, foreign key 거부, `UNLINK` 결과 검증, post-delete 수렴 검증이 동일하다.
2. 두 구현의 차이는 도메인 이름과 허용 implementation 정책(`job-core`/`job-spring`/`job-ktor` 대 `ticket-spring`), 그리고 Lettuce adapter의 패키지뿐이다.
3. 이 계약은 Redis client나 PostgreSQL schema를 요구하지 않는다. 따라서 명령 adapter와 writer barrier를 주입하면 domain-independent test fixture로 분리할 수 있다.
4. `MutableClock`은 AWS Kinesis, voucher/metering, operations, optimization의 독립 테스트에서 초기 `Instant`, `ZoneId`, `advance(Duration)`, `withZone`이라는 동일한 wall-clock 계약을 사용한다.
5. `WorkerPid` publisher는 commerce ticket과 operations job console에서 owner 검증, parent symlink 방어, `CREATE_NEW`, PID UTF-8 bytes, file/directory fsync라는 동일한 handoff 계약을 사용한다.
6. `HighContentionArtifactStore`와 `TicketHighContentionArtifactStore`는 report schema가 다르지만, trusted path 검증, no-replace atomic write, redaction guard, hash-chain JSONL의 line/digest/sequence 처리만 공통이다. 전체 report/journal 모델을 합치지 않는다.
7. `KafkaRecoveryConformanceFixture`는 이미 두 독립 소비자와 자체 계약을 갖춘 올바른 shared fixture다. 반대로 `RedisTestSupport`, Okio helper, `RedirectWebFilter`, `NettyConfig` 등은 소비자 수·프레임워크·정책 차이 때문에 이번 root shared 추출 대상이 아니다.
8. Digesters는 이미 `bluetape4k-io`에 있고, canonical JSON은 `bluetape4k-jackson3`에 있다. Okio는 별도 `io/okio-examples` 주제이며 Awaitility는 consumer별 timing contract다. 이 네 가지는 Workshop `shared`에 중복 승격하지 않는다.

## 목표

- 네 교차 주제 후보의 domain-independent 계약을 `shared/src/testFixtures`에서 한 번만 소유한다.
- Redis client, report schema, journal event enum/string, serializer 의존성을 공통 fixture에서 제거하고, 작은 adapter/strategy 경계로 고정한다.
- 모든 소비 모듈이 기존 테스트 이름과 domain-specific 정책을 유지하면서 공통 구현을 사용하게 한다.
- foreign key, 삭제 상한, scan 비수렴, unlink count 불일치, writer barrier 순서를 기존과 동일하게 fail-closed로 검증한다.
- 공통 fixture의 이름·계약·소유 정책을 향후 `bluetape4k-*` test library로 승격할 수 있도록 문서화한다.

## 비목표

- 공통 fixture를 production main source나 public runtime API로 노출하지 않는다.
- artifact/journal의 report schema, event vocabulary, serializer, Redis client, database lifecycle을 합치지 않는다.
- Redis 전체 lifecycle, Lettuce client 설정, Testcontainers 실행기를 shared fixture에 넣지 않는다.
- commerce의 idempotency/fingerprint, optimization planner, image URL 보안 정책, Netty timeout 설정을 공통화하지 않는다.
- `bluetape4k-io`, `bluetape4k-jackson3`, `bluetape4k-junit5`, `bluetape4k-spring-boot-core`에 대한 릴리스·태그·BOM 변경을 하지 않는다.

## 제안 설계

### 공통 계약

새 파일은 `shared/src/testFixtures/kotlin/io/bluetape4k/workshop/shared/redis/OwnedRedisNamespace.kt`에 둔다. 공통 타입은 다음 네 가지다.

- `OwnedRedisScanPage(nextCursor: String, keys: List<String>)`
- `OwnedRedisKeyCommands.scan(cursor, pattern, count)` 및 `unlink(keys)`
- `OwnedRedisWriterBarrier.pause(): AutoCloseable`와 no-op `NONE`
- `OwnedRedisDeletionResult(deletedKeys: List<String>)`

`OwnedRedisNamespace.parse`는 다음 값을 받는다.

- terminal `:`을 포함하는 `namespace`
- 양수 `deleteUpperBound`
- `OwnedRedisKeyCommands`
- 양수 `maximumScanIterations`(기본값 `1_024`)
- `implementationPolicy: (String) -> Boolean`

namespace의 공통 형식은 `hc:v1:<runId>:<implementation>:redis-key-loss:`다. `<runId>`와 key suffix의 두 owner component는 기존 정규식 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`을 유지한다. 허용 implementation은 호출 모듈이 policy로 지정한다. 공통 알고리즘은 다음 순서를 보장한다.

1. writer barrier를 pause한다.
2. cursor가 `0`으로 돌아올 때까지 bounded `SCAN`을 수행하고 key를 정렬해 중복을 제거한다.
3. 모든 후보가 `owns`를 만족하는지, 개수가 상한 이하인지 검증한다.
4. 후보가 있을 때만 `UNLINK`하고 반환 개수가 후보 수와 같은지 검증한다.
5. 같은 bounded scan으로 namespace가 수렴했는지 확인한다.
6. `OwnedRedisDeletionResult`를 반환하고 `AutoCloseable`을 닫아 writer를 재개한다.

공통 타입은 Lettuce를 import하지 않는다. 각 모듈의 `Lettuce*OwnedRedisKeyCommands` adapter만 해당 모듈에 남긴다.

artifact/journal 하부 primitive는 다음 계약으로 제한한다.

- `TrustedArtifactPaths`: bounded identifier, trusted directory, normalized relative target, descendant symlink rejection, parent fsync
- `AtomicArtifactWriter`: temporary file을 `CREATE_NEW` 대상에 hard-link하여 no-replace로 publish하고 file/directory를 force한다.
- `ArtifactRedactionGuard`: 공통 sentinel과 소비자 추가 sentinel을 case-insensitive로 검사한다.
- `HashChainCursor`와 `HashChainJsonl`: payload/record SHA-256, genesis/sequence/previous-link, complete JSONL line split, torn final suffix 허용, blank complete line 거부를 소유한다.

각 도메인은 payload/event/report 타입, strict Jackson parser, fallback journal 의미를 계속 소유한다. shared primitive가 JSON schema나 이벤트 vocabulary를 정의하지 않으므로 두 consumer의 저장 형식 호환성을 새로 주장하지 않는다.

### 소비자 호환성

기존 테스트와 fixture를 사용하는 호출부의 변경 폭을 줄이기 위해 두 모듈에 얇은 typealias 또는 wrapper를 둔다. wrapper는 다음 정책만 고정한다.

- operations: `job-core`, `job-spring`, `job-ktor`
- commerce ticket: `ticket-spring`

공통 알고리즘의 반환 타입과 barrier 계약은 alias를 통해 그대로 노출한다. wrapper가 공통 구현을 다시 복사하거나 검증을 우회해서는 안 된다. 두 모듈의 Lettuce adapter는 공통 `OwnedRedisKeyCommands`에 맞춰 `OwnedRedisScanPage`를 반환한다.

### 테스트 계약

기존 두 테스트 묶음을 공통 타입을 호출하도록 수정한다. 다음 계약을 각각 독립적으로 확인한다.

- golden vector digest와 모든 ownership case
- 빈 component, 알 수 없는 implementation/profile, non-terminal namespace의 fail-closed 파싱
- 모든 후보 검증 이후 bounded unlink이 호출되는지와 writer pause/resume 순서
- foreign key 또는 delete upper bound 초과 시 unlink 이전에 실패하는지
- scan cursor 비수렴과 post-unlink 비수렴이 상한에서 실패하는지
- 실제 Redis에서는 run namespace만 삭제되고 foreign key가 남는지

공통 fixture는 `shared`의 test fixture output으로 컴파일하고, 소비자 테스트가 직접 공통 구현을 실행한다. `shared`에 Lettuce를 추가하거나 production `main`으로 승격하지 않는다. 별도 `testFixturesTest` source set을 추가하는 것은 첫 단계의 계약을 불필요하게 확장하므로 이번 변경에서 하지 않는다.

## 대안과 선택

| 대안 | 장점 | 문제 | 결정 |
|---|---|---|---|
| 각 모듈에 복사 유지 | 변경이 작음 | 알고리즘 drift와 보안 경계 drift가 계속됨 | 기각 |
| `shared/src/main`에 production API 추가 | 단일 구현 | 테스트 전용 cleanup 정책이 runtime ABI가 됨 | 기각 |
| `shared/src/testFixtures`에 adapter 기반 공통 구현 | 두 소비자의 계약을 그대로 유지하고 dependency를 작게 유지 | consumer test classpath 조정 필요 | 채택 |
| 즉시 `bluetape4k-*`로 외부 승격 | 장기 재사용 가능 | 릴리스/BOM/ABI 검증이 별도 필요하고 현재 Workshop 계약이 먼저 안정되어야 함 | 후속 후보 |

## 실패 경계와 복구

- namespace 파싱 실패는 `IllegalArgumentException`으로 조기에 거부한다.
- `SCAN`이 `0`으로 돌아오지 않으면 `maximumScanIterations` 초과 시 `IllegalStateException`으로 중단한다.
- foreign key가 섞이거나 상한을 초과하면 `UNLINK`을 호출하지 않는다.
- `UNLINK` 반환 수가 후보 수와 다르면 성공으로 취급하지 않는다.
- 삭제 후 scan이 비어 있지 않으면 비수렴 오류로 실패한다. writer barrier는 `use` 블록으로 항상 닫는다.
- migration 중 컴파일 또는 targeted test가 실패하면 consumer wrapper를 되돌리고 기존 두 파일을 복구할 수 있다. 공통 fixture 파일을 삭제해도 기존 구현을 복원할 수 있도록 첫 커밋은 공통 타입과 consumer wiring을 함께 묶되, 각 변경을 한 커밋에서 추적 가능하게 유지한다.

## 수용 기준과 DoD

- [ ] `shared` test fixture에 공통 `OwnedRedisNamespace` 계약이 있고 Lettuce import가 없다.
- [ ] operations와 commerce ticket의 기존 namespace 테스트가 공통 구현을 호출한다.
- [ ] 두 모듈의 implementation policy와 public test-facing 이름이 보존된다.
- [ ] targeted unit/contract tests가 모두 통과한다.
- [ ] Docker image `bluetape4k/mock-web-server:2.2.0`을 요구하는 기존 shared baseline 실패를 새 변경의 성공으로 오인하지 않고 별도 gap으로 기록한다.
- [ ] `git diff --check`, 관련 compile/test, workflow evidence와 이슈 read-back이 완료된다.
- [ ] 이번 단계에서 `MutableClock`, `WorkerPid`, artifact/journal 하부 primitive의 shared 승격 상태를 검증하고, `bluetape4k-*` upstream release는 별도 승인 범위로 남긴다.

## 승격 상태와 후속 경계

1. `MutableClock`: 완료. `Instant`, `ZoneId`, `advance(Duration)`, `withZone` wall-clock contract를 shared fixture와 consumer tests로 고정했다.
2. `WorkerPid`: 완료. no-overwrite(`CREATE_NEW`), owner 검증, parent symlink 거부, bytes/fsync를 직접 테스트하고 공통 fixture로 추출했다.
3. artifact/journal primitive: 이번 단계에서 완료한다. 전체 도메인 schema가 아닌 path/atomic write/redaction/hash-chain JSONL 저수준 계약만 공통화한다.
4. 공통 계약이 두 repository 또는 두 독립 소비자에서 release artifact로 필요해졌을 때만 해당 `bluetape4k-*` 라이브러리의 API/ABI, BOM, publication 검증을 별도 실행한다.

## Writer gate 기록

- **SPW-01**: 대상은 한국어 Superpowers 설계 문서이며, 근거는 issue #1069, 두 consumer source/test, `shared` fixture, upstream promotion inventory이다. 확인하지 않은 release/ABI 주장은 후속으로 명시했다.
- **SPW-02**: 문제, 목표/비목표, 계약, 대안, 실패 경계, 호환성, 수용 기준, DoD와 후속 순서를 포함했다.
- **SPW-03**: 기술 토큰은 원문을 보존하고, 독자에게 필요한 설명은 한국어 기술 문체로 작성했다.
- **SPW-04**: source 조사 결과와 issue의 단계 구분을 대조했으며, Digesters와 canonical JSON의 기존 upstream 구현, Okio/Awaitility의 주제·timing 경계를 중복 후보에서 제외했다. artifact/journal은 schema가 아니라 하부 primitive만 포함하도록 제한했다.
- **SPW-05**: 최종 Markdown을 다시 읽고 heading/table/code token의 구조와 경계를 확인한다. 이 결과는 workflow `spec` evidence에 기록한다.
