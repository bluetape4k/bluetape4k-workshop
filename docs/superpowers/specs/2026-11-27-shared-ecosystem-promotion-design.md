# Workshop shared 공용 계약의 bluetape4k ecosystem 승격 설계

_기준 브랜치: `bluetape4k-workshop/develop` (`f1b0a15d6`) | 범위: C(테스트 fixture와 production API 전체 후보) | 상태: 설계 완료, 구현 계획 검토 대기_

## 1. 결정 요약

`shared`의 모든 파일을 하나의 공용 모듈로 복사하지 않는다. 각 계약의 실제
소유 upstream 모듈을 정하고, upstream API를 먼저 공개한 뒤 Workshop을
소비자로 전환한다. 후보별 변경은 독립 PR train으로 나누며, 한 train의
릴리스 지연이 다른 train의 이관을 막지 않게 한다.

현재 `develop`에는 Workshop 내부 승격이 이미 반영되어 있다.

- PR #1070은 `MutableClock`, `WorkerPidPublisher`, artifact primitive,
  `OwnedRedisNamespace` 등을 `shared` root test fixture로 이동했다.
- PR #1072는 fingerprint, sparse travel-time matrix, 이미지 보안 정책,
  Netty timeout, Redis runtime adapter를 `shared` production API로 추가했다.
- 두 PR 모두 `bluetape4k-*` artifact로의 외부 승격은 별도 검증 이후로 남겼다.

이번 설계는 그 외부 승격을 다룬다. upstream artifact가 실제로 해석되기
전에는 Workshop의 현재 구현을 삭제하지 않는다.

## 2. 목표와 비목표

### 목표

1. 중복 구현이 확인된 범용 계약을 책임 모듈의 공개 API 또는 test fixture로
   이관한다.
2. 기존 Workshop의 저장 형식, fingerprint 결과, 파일 거부 조건, timeout 값,
   recovery 판정 의미를 유지한다.
3. upstream API/ABI, publication POM, Gradle dependency graph, catalog/BOM,
   Workshop 소비자 전환을 하나의 검증 흐름으로 묶는다.
4. Kafka 3/4처럼 구현 버전이 다른 소비자가 client 타입에 결합되지 않은
   conformance fixture를 함께 사용할 수 있게 한다.

### 비목표

- `shared` 패키지를 새 umbrella common 모듈로 만들지 않는다.
- Workshop의 planner 알고리즘, Kafka broker topology, domain report/journal
  schema를 upstream 공용 API로 만들지 않는다.
- 이미 존재하는 `Digesters`, `RedisServer`, `LettuceClients`를 thin wrapper로
  재배포하지 않는다.
- test-only 계약을 production runtime dependency로 끌어오지 않는다.

## 3. 후보와 소유 모듈

| 후보 | 목표 artifact/모듈 | 범위 결정 | 승격 조건 |
|---|---|---|---|
| `MutableClock` | `bluetape4k-junit5` (`testing/junit5`) | 즉시 승격 | wall-clock 테스트 계약과 기존 중복 clock의 동작을 일치시킨다. |
| `WorkerPidPublisher` | `bluetape4k-junit5` test utility | 승격 후보 | Workshop 전용 property 이름을 제거하고 경로·소유자·게시 정책을 중립화한다. |
| `ArtifactPrimitives` | `bluetape4k-io` 검토 | 부분 승격 | trusted path와 atomic writer처럼 범용인 부분만 공개한다. report/journal/hash-chain은 보류한다. |
| `FingerprintEncoder` | `bluetape4k-io` | 승격 후보 | 기존 `Digesters`를 재사용하고 domain framing/HMAC 결과를 golden vector로 고정한다. |
| `NettyTimeoutPolicy`, `NettyTimeoutHandlers` | `bluetape4k-netty` (`projects/infra/netty`) | 승격 후보 | 순수 policy와 Reactor Netty adapter를 분리한다. |
| `SafeFilenamePolicy`, `PublicBaseUriPolicy` | `bluetape4k-image` | 승격 후보 | 이미지 저장·다운로드 소유 계층과 기존 거부 조건을 대조한다. |
| `KafkaRecoveryConformanceFixture` | `bluetape4k-kafka` (`projects/infra/kafka`의 test fixtures) | 승격 후보 추가 | Kafka client, Spring, Testcontainers, broker topology를 fixture API에서 제외한다. |
| `OwnedRedisRuntime`, `TestcontainersRedisRuntime` | `bluetape4k-testcontainers`/`bluetape4k-lettuce` 재평가 | 조건부 승격 | 기존 `RedisServer`·`LettuceClients`와 다른 lifecycle/ownership 계약이어야 한다. |
| `OwnedRedisNamespace` | 별도 검토 | 보류 | Workshop 외부의 독립 소비자와 namespace 정책 근거가 더 필요하다. |
| `TravelTimeMatrix` | 없음 | 보류 | 현재 두 planner만 사용하므로 독립 upstream 소유 모듈이 생길 때까지 Workshop에 둔다. |
| `RedisTestSupport`, 단순 façade | 없음 | 제거/대체 | 소비자가 upstream의 기존 API를 직접 사용한다. |

`KafkaRecoveryConformanceFixture`는 별도 Kafka repository가 아니라
`bluetape4k-projects/infra/kafka` 모듈의 발행 artifact인
`bluetape4k-kafka`에 둔다. 현재 fixture는 `java.time`과 Kotlin 표준 타입만
사용하므로 Kafka 3 client API에 결합하지 않는다. Kafka 4 Workshop 소비자에서
test fixture dependency가 Kafka 3 runtime을 끌어오지 않는지 별도 gate로
확인한다.

## 4. 공개 API 계약

### 4.1 `MutableClock`

목표 패키지는 `io.bluetape4k.junit5.time`이다.

- `MutableClock(initial: Instant, zone: ZoneId = UTC)`를 제공한다.
- `advance(Duration): Instant`와 `advanceSeconds(Long): Instant`를 제공한다.
- `withZone`은 동일한 `AtomicReference<Instant>`를 공유한다.
- 생성·읽기·증분 갱신은 thread-safe해야 한다.
- wall-clock timeline만 모델링하며 monotonic clock, scheduler, nano-time
  대체 계약을 제공하지 않는다.
- 아직 외부 release에 노출되지 않은 Workshop 내부 alias는 만들지 않는다.
  Projects와 Workshop의 private clock은 위 API로 직접 마이그레이션한다.

### 4.2 `KafkaRecoveryConformanceFixture`

목표 패키지는 `io.bluetape4k.kafka.test.conformance`이며
`bluetape4k-kafka`의 test fixture artifact에 둔다.

- `KafkaRecoveryPath`, `KafkaRecoveryObservation`,
  `KafkaRecoveryConformanceFixture`의 역할과 필드 의미를 유지한다.
- transport interruption, broker leader failover, broker coordinator failover를
  별도 경로로 검증한다.
- `recoveryDeadline`, logical ID 집합, delivery 목록, 고유 적용 집합,
  conflict count를 검증한다.
- delivery는 at-least-once 경계를 허용하고, dedup 검증은 logical identity의
  유일한 적용 효과만 주장한다.
- Workshop의 18-field redacted JSONL validator, broker identity, topology,
  Testcontainers 설정은 upstream fixture에 포함하지 않는다.
- Kafka 3/4 client 타입이나 Spring Kafka 타입을 public signature와 fixture
  dependency에 추가하지 않는다.

### 4.3 나머지 후보의 경계

- `FingerprintEncoder`는 canonical payload 생성자가 아니라 framing·HMAC·출력
  encoding primitive만 제공한다. 기존 `Digesters`를 호출하고 기존 golden
  vector를 보존한다.
- `ArtifactPrimitives`는 trusted path와 atomic write처럼 독립적인 I/O 계약을
  우선 검토한다. Workshop report/journal 명칭과 hash-chain schema는 공개하지
  않는다.
- `NettyTimeoutPolicy`는 불변 duration policy로 두고,
  `NettyTimeoutHandlers`는 Reactor Netty 의존성을 선택적 adapter 경계 뒤에 둔다.
  event loop, pool, `SO_LINGER` 튜닝은 소비자가 소유한다.
- 이미지 정책은 filename traversal/encoded dot/userinfo/query/fragment와
  insecure remote URL 거부 조건을 보존한다. profile authorization과 storage
  lifecycle은 `bluetape4k-image` 소비자에 남긴다.
- Redis adapter가 승격되면 resource owner, partial-start rollback,
  idempotent close, interrupt 복원을 API 문서와 테스트로 고정한다.

## 5. source set과 의존성 경계

| 계약 | upstream source set | Workshop 소비 방식 |
|---|---|---|
| `MutableClock` | `bluetape4k-junit5` main | `testImplementation(libs.bluetape4k.junit5)` |
| Kafka recovery fixture | `bluetape4k-kafka` test fixtures | 외부 `testFixtures` dependency |
| Fingerprint/I/O primitive | `bluetape4k-io` main | production 또는 test 소비자의 명시적 dependency |
| Netty timeout | `bluetape4k-netty` main + 선택 adapter | 필요한 Netty/Reactor 소비자만 선택 |
| 이미지 정책 | `bluetape4k-image` main | 이미지 모듈의 기존 artifact dependency |
| Worker PID | `bluetape4k-junit5` test utility | 테스트 전용 dependency |

upstream의 무거운 외부 타입은 unrelated consumer에 전이되지 않아야 한다.
외부 `testFixtures` 의존성은 Gradle dependency report로 runtime leakage를
검증하고, 필요한 경우 fixture API와 runtime configuration을 분리한다.

## 6. PR train과 전환 순서

각 train은 다음 순서를 따른다.

1. upstream 모듈에 API, KDoc, RED → GREEN 계약 테스트를 추가한다.
2. `javap -public` 또는 binary API 검사, publication POM 검사를 통과시킨다.
3. 개발 버전 또는 release artifact를 실제 resolver에서 확인한다.
4. dependencies catalog/BOM 또는 해당 repository의 catalog ref를 갱신한다.
5. Workshop import와 Gradle dependency를 upstream artifact로 전환한다.
6. 해당 소비자가 더 이상 사용하지 않는 `shared` 파일과 test fixture dependency만
   삭제한다. 다른 shared 계약을 쓰는 모듈의 dependency는 유지한다.
7. targeted test, dependency report, 전체 build와 관련 sync 검사를 실행한다.
8. upstream과 Workshop에서 exact head, CI, publication POM, 변경 diff를 다시
   읽고 다음 train으로 이동한다.

권장 실행 순서는 다음과 같다.

1. Train A: `MutableClock`
2. Train B: `KafkaRecoveryConformanceFixture`
3. Train C: `FingerprintEncoder`와 범용 artifact I/O
4. Train D: Netty timeout
5. Train E: 이미지 정책
6. Train F: Redis lifecycle adapter와 `WorkerPidPublisher`의 조건부 계약

순서는 의존성 위험과 Workshop 소비자 수를 기준으로 한 기본값이며, 특정
upstream release가 지연되어도 이미 검증된 train은 독립적으로 진행한다.

## 7. 검증 matrix와 완료 기준

### upstream

- API/ABI: `javap -public` 또는 binary compatibility report
- 계약: 정상·경계·실패·재호출·interrupt 테스트
- publication: 모든 dependency-management entry에 version 존재, POM effective
  model 검증
- 보안: path traversal, encoded dot, URL userinfo/query/fragment, PID parent
  symlink, partial-start cleanup
- Kafka fixture: 세 recovery path, deadline, replay/dedup, conflict 0

### Workshop

- import/package migration과 `shared` 중복 검색
- Kafka 4 소비자의 Kafka 3 runtime leakage 확인
- fingerprint golden vector와 이미지 adversarial case 보존
- Netty 모듈별 timeout expected-value compile/test smoke
- Redis container lifecycle은 managed Colima context에서 순차 실행
- `./gradlew build`, 관련 catalog/dependabot/POM sync, `git diff --check`

### 문서

- 각 public API KDoc과 README 사용 예
- Workshop migration note와 보류 후보 기록
- 필요할 때 `CHANGELOG.md`의 한국어 `추가`/`변경` 항목

최종 완료 조건은 후보를 발견한 상태가 아니라, 각 승격 후보에 대해
upstream artifact가 해석되고 Workshop 소비자가 그 artifact를 사용하며,
기존 shared 구현의 중복이 제거되고, 위 검증 결과가 기록된 상태다.

## 8. 실패 모드와 rollback

| 실패 | rollback |
|---|---|
| upstream artifact resolver 실패 | Workshop의 기존 `shared` 구현을 유지하고 catalog ref를 되돌린다. |
| Kafka fixture가 Kafka 3 runtime을 전이 | fixture publication/configuration을 분리한 뒤 재검증한다. |
| fingerprint 또는 이미지 결과 변경 | golden/adversarial test에서 중단하고 consumer migration을 되돌린다. |
| Netty adapter가 무거운 의존성을 전이 | policy core와 adapter를 분리하고 consumer가 명시적으로 선택하게 한다. |
| Redis partial start/close 실패 | upstream promotion을 보류하고 Workshop adapter를 유지한다. |
| public API 소유권 불명확 | 해당 후보를 보류하고 thin wrapper를 공개하지 않는다. |

## 9. 설계 자체 검토

- [x] 접근 1(module-native staged extraction)을 선택하고 C 범위의 후보를
      모두 매트릭스에 기록했다.
- [x] `MutableClock`과 `KafkaRecoveryConformanceFixture`의 source set,
      package, API 경계를 명시했다.
- [x] Kafka 3/4 호환성과 dependency leakage를 별도 검증 항목으로 분리했다.
- [x] production API, test fixture, Workshop 전용 도메인 계약을 구분했다.
- [x] upstream release 전 Workshop 파일 삭제를 금지하는 rollback 경계를
      고정했다.
- [x] 한국어 기술 문장, 식별자 보존, 표·목록·코드 토큰을 검토했다.
- [x] 후보별 PR train과 fresh verification evidence를 완료 기준에 포함했다.
