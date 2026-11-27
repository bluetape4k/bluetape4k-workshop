# shared runtime 공통 계약과 보안 primitive 설계

_Issue: #1071 | 선행: #1069, #1070 | 상태: 승인됨_

## 1. 결정 요약

기존 PR #1070의 test-fixture 승격과 새 runtime 공통화는 별도 PR train으로
분리한다. `shared/src/main`에는 production 소비자가 직접 사용할 수 있는 순수
계약과 명시적 opt-in runtime adapter를 둔다. Testcontainers, Lettuce, Reactor
Netty처럼 무거운 외부 타입을 사용하는 API는 `compileOnlyApi`로 표면을 고정하고,
실제 소비자가 필요한 runtime 의존성을 직접 선택한다. Spring Test 전용 helper만
`testFixtures`에 남긴다.

새 API는 기존 소비자의 저장 형식·경로 정책·자원 소유권을 바꾸지 않는다. 공통화
대상은 두 구현이 실제로 공유하는 하부 계약이며, domain repository/status/schema,
planner 전체 알고리즘, profile authorization은 소비 모듈에 남긴다.

## 2. 현재 근거와 source ledger

| 근거 | 확인한 계약 | 설계 영향 |
|---|---|---|
| `shared/build.gradle.kts` | Testcontainers/Spring bridge는 현재 `compileOnly`, 신규 Lettuce/Reactor Netty API는 `compileOnlyApi` | production main API를 허용하되 외부 runtime은 consumer가 opt-in하고 dependency graph에서 확인한다. |
| `shared/.../RedisTestSupport.kt` | `RedisServer.Launcher.redis`와 upstream dynamic-property bridge만 위임 | 기존 helper는 호환 facade로 유지하고 신규 runner는 명시적 owner로 만든다. |
| `bluetape4k-projects/testing/testcontainers/.../RedisServer.kt` | lazy singleton, `ShutdownQueue`, Lettuce helper, Redisson warm-up | upstream 컨테이너 구현을 복제하지 않고 명시적 lifecycle wrapper에서 조합한다. |
| `bluetape4k-projects/infra/lettuce/.../LettuceClients.kt` | tuned `ClientResources`, `SocketOptions`, connection cache/shutdown | Lettuce 설정은 `LettuceClients`에 위임하고 shared가 별도 기본값을 만들지 않는다. |
| commerce fingerprint 구현 4종 | HMAC, domain-separated SHA-256, raw/sorted payload가 서로 다름 | 공통 encoder만 제공하고 기존 output을 golden vector로 보존한다. |
| optimization `TravelTimeMatrix` 2종 | lookup 구조는 같지만 empty/limit/exception/digest 계약이 다름 | generic sparse matrix와 deterministic digest만 공통화한다. |
| image `ImageKeyFactory`/`PublicImageUrlResolver` 2쌍 | filename/base URI 검증은 중복, profile path authorization은 고유 | filename/URI policy만 shared로 이동한다. |
| workshop `NettyConfig.kt` 11개 | read/write timeout 골격은 반복, loop/pool/SO_LINGER는 다름 | timeout policy/customizer만 공통화하고 튜닝 값은 보존한다. |

## 3. 목표와 비목표

### 목표

1. fingerprint framing과 digest 계산을 공통 API로 제공하되 기존 wire output을 보존한다.
2. generic sparse travel-time matrix의 검증·lookup·deterministic digest를 공통화한다.
3. image filename 및 public base URI의 보안 검증을 공통 정책으로 제공한다.
4. Netty read/write timeout 설정을 불변 정책으로 제공하고 소비 모듈 adapter가 사용하게 한다.
5. Redis/Testcontainers runner와 Lettuce client 설정의 lifecycle·close 책임을 명시한다.
6. 각 공개 API의 dependency/BOM/ABI 계약과 rollback 경계를 문서화한다.

### 비목표

- Digesters, canonical JSON, Okio, Awaitility를 Workshop `shared`에 복제하지 않는다.
- idempotency repository/status/lease schema와 domain report/event schema를 합치지 않는다.
- Field Service, Last Mile, Shift Coverage, Warehouse Allocation planner 전체를 합치지 않는다.
- Profile image의 private/public/pending authorization을 generic URL policy에 넣지 않는다.
- `RedisServer.Launcher`의 singleton을 새 자원 소유 모델로 확장하지 않는다.

## 4. API 경계

### 4.1 Production core (`shared/src/main`)

- `shared.fingerprint`: `FingerprintEncoder`, domain framing, HMAC-SHA256/hex/base64
  primitive. canonical payload 생성은 consumer가 담당한다.
- `shared.optimization`: generic `CoordinatePair`와 sparse `TravelTimeMatrix`.
  ID 문자열 변환 함수를 받아 deterministic digest를 계산한다.
- `shared.image`: `SafeFilenamePolicy`와 `PublicBaseUriPolicy`.
  storage backend와 profile authorization은 호출자가 결정한다.
- `shared.netty`: `NettyTimeoutPolicy`와 positive duration 검증, 그리고 명시적
  `NettyTimeoutHandlers` Reactor Netty adapter. loop/pool/SO_LINGER 튜닝은 소비자가
  계속 소유한다.

### 4.2 Production runtime adapter와 test-fixture 경계

- `bluetape4k-testcontainers`: `RedisEndpoint`, `RedisContainerHandle`,
  `RedisRuntime`, `OwnedRedisRuntime`, `TestcontainersRedisRuntime`를 공용
  lifecycle 계약으로 제공한다. `start()`는 한 번만 성공하고, partial-start
  실패는 이미 시작한 자원을 역순으로 정리한다. `close()`는 idempotent이며
  interrupt 상태를 복원한다.
- Workshop `shared`에는 이 runtime의 local wrapper를 두지 않는다. Redis
  client/connection은 upstream `LettuceClients`를 직접 사용하고, 기존
  `RedisServer`/`LettuceClients` 계약과 중복되는 factory는 승격하지 않는다.

기존 Spring DynamicPropertyRegistry helper는 `testFixtures`에 유지한다.
production API에서 외부 타입을 반환할 때는 `compileOnlyApi`와 consumer runtime
선언을 함께 검증하고, Testcontainers/Lettuce/Netty가 unrelated consumer에
자동 전이되지 않도록 한다.

## 5. 호환성 규칙

- fingerprint golden vector는 기존 구현별로 유지한다. framing, separator, HMAC
  domain, output encoding을 임의로 단일화하지 않는다.
- 기존 image key path와 base URL 거부 조건을 먼저 테스트하고, 공통 policy로 이동한
  뒤 동일한 예외 조건과 메시지 의미를 유지한다.
- 기존 Netty별 timeout 값(10초/30초), `SO_LINGER`, event-loop worker 수를 보존한다.
- Redis endpoint property key(`testcontainers.redis.host/port/url`)와 기존
  compatibility facade의 동작을 유지한다.
- public class/function은 Kotlin binary compatibility report 또는 `javap -public`로
  확인하고, API signature에 Testcontainers/Spring 타입을 추가할 때 dependency graph를
  함께 검증한다.

## 6. 실패 모드와 안전장치

| 실패 모드 | 방어 계약 |
|---|---|
| Redis start 중 container/network 일부만 생성 | 단계별 rollback과 역순 close |
| close 중 interrupt | cleanup 후 현재 thread interrupt 복원 |
| client/connection ownership 불명확 | upstream `LettuceClients`의 shutdown 계약과 runtime `AutoCloseable` 문서 |
| fingerprint 저장값 변경 | consumer별 golden vector와 migration 없는 교체 금지 |
| URI traversal/encoded dot/userInfo/query/fragment | base URI policy에서 조기 거부 |
| HTTP remote URL이 insecure | loopback 예외 또는 명시적 allow flag 없이는 거부 |
| Netty timeout 값 drift | policy 단위 테스트와 모듈별 expected-value 테스트 |
| shared API가 무거운 전이 의존성 유입 | Gradle dependency report와 compile-consumer smoke |

## 7. 수용 기준

- [x] core API와 adapter API의 source set·dependency 경계가 문서와 build 파일에서 일치한다.
- [x] 각 공통 primitive에 RED → GREEN 회귀 테스트가 있다.
- [x] fingerprint golden vector, image adversarial case, matrix deterministic digest가 통과한다.
- [x] Redis runner의 start failure/close 재호출/interrupt/connection shutdown을 검증한다.
- [x] 영향을 받는 commerce·optimization·image·Netty 모듈이 compile/test smoke를 통과한다.
- [ ] `javap -public`, dependency graph, detekt, `git diff --check`, sequential container test를 실행한다. (javap/dependency/diff/container는 통과, root detekt는 기존 unrelated issue로 실패)
- [ ] 각 PR은 독립 rollback 가능하고, merge 전 exact-head CI·review/thread read-back을 남긴다.

## 8. SPW writer self-review

- [x] SPW-01: 독자, 목적, issue, source ledger, 불확실성을 고정했다.
- [x] SPW-02: 범위·비목표·계약·실패 모드·호환성·DoD를 포함했다.
- [x] SPW-03: 한국어 technical register와 API token 보존을 적용했다.
- [x] SPW-04: 현재 `develop` source와 upstream source를 대조했다.
- [x] SPW-05: 표·목록·코드 token을 read-back했다.
- [x] KO-01~KO-07: 의미·용어·구조·reader-facing surface를 점검했다.
