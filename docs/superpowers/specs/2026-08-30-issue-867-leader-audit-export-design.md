# Issue #867 leader audit export 경계 설계

## 문서 상태

- 대상 이슈: [#867](https://github.com/bluetape4k/bluetape4k-workshop/issues/867)
- 대상 모듈: `leader/job-safety-lab`
- 기준 브랜치: `develop`
- 기준 의존성: `bluetape4k-dependencies:2.0.0-SNAPSHOT`
- 범위: 기존 leader lifecycle report에 audit export, bounded queue와 observability를 연결
- 언어 범위: 한국어 설계·KDoc·lesson, 동등한 `README.md`/`README.ko.md`

이 문서는 Issue #867 본문과 2026-08-30 현재 upstream source를 구현 기준으로
삼는다. 설계·구현·검증은 이 worktree의 feature branch에서 수행하고, PR 생성
이후에는 exact head의 live GitHub 상태를 다시 확인한다.

## 문제와 근거

`leader/job-safety-lab`은 `ObservationRegistry`와
`MicrometerObservationLeaderAopMetricsRecorder`를 사용해 leader acquire와
fenced execution을 관찰한다. 그러나 `LettuceLeaderElector`의 history recorder
경계를 사용하지 않아 다음 상태를 실행 가능한 예제로 보여주지 못한다.

- leader history event가 외부 audit 경계로 넘어가는 시점;
- bounded queue가 가득 찼을 때 caller를 막지 않고 drop하는 결과;
- delivery retry, terminal failure, cancellation과 close 이후 snapshot;
- HTTP payload의 1 MiB hard bound, HTTPS trust와 allow-listed header;
- token, lock 원문, tenant/customer 식별자, raw exception message의 redaction.

현재 upstream `bluetape4k-leader`의 근거는 다음과 같다.

- upstream [Issue #535](https://github.com/bluetape4k/bluetape4k-leader/issues/535)는
  leader event용 pluggable audit export adapter를 정의한다.
- upstream [PR #792](https://github.com/bluetape4k/bluetape4k-leader/pull/792)는
  `LeaderAuditExporter`, `ExportingLeaderHistorySink`,
  `MicrometerLeaderAuditExporter`, `HttpLeaderAuditExporter`,
  `LeaderAuditTrustedHttpsEndpoint`를 merged develop에 추가했다.
- local upstream source의 `LeaderAuditExportOptions`는 queue/max-in-flight/
  retry/timeout/backoff를 검증하고, `LeaderAuditExportSnapshot`은 queue gauge와
  admission/drop/retry/failure/cancellation/rejection counter를 제공한다.
- `LettuceLeaderElector`의 세 번째 생성자 인자는
  `SafeLeaderHistoryRecorder?`이며 acquisition/completion/failure lifecycle에
  history record를 전달한다.

## 목표와 제외 범위

### 목표

1. `leader/job-safety-lab`에 2.0.0 audit export API를 실제 caller 흐름으로 연결한다.
2. 기본 실행은 endpoint, credential, DNS, socket 없이 bounded memory transport로 동작한다.
3. 명시적인 `transport=HTTPS`와 신뢰한 `https://` endpoint가 있을 때만 JDK
   `HttpClient`를 사용한다.
4. `MicrometerLeaderAuditExporter`의 low-cardinality meter와 snapshot을
   `/api/job-safety/audit`에서 안전하게 확인할 수 있게 한다.
5. history event와 HTTP payload를 모두 bounded·token-free·redacted로 고정한다.
6. queue admission/backpressure/drop, retry/status, close/cancellation,
   endpoint/header validation을 테스트와 README 실행 절차로 고정한다.
7. root dependencies BOM의 versionless alias 규칙과 matrix/workflow/stale-check/
   lesson 등록을 완료한다.

### 제외 범위

- PostgreSQL `JobSafety*` 테이블을 leader audit history 저장소로 교체하는 작업;
- exactly-once 외부 audit 전송 또는 delivery receipt 보장;
- 운영 audit system, credential provider, DNS/SSRF allow-list를 자동 설치하는 작업;
- 기본 profile에서 Redis/PostgreSQL 외의 네트워크 연결;
- upstream core의 internal `BoundedLeaderAuditExporter`를 복제하거나 직접 의존하는 작업;
- 기존 scenario, fencing, outbox semantics의 변경.

## 선택한 구조

### 구성 요소와 책임

다음 구성은 모두 `JobSafetyConfiguration`에서 명시적인 bean dependency로
연결한다.

1. **`JobSafetyAuditProperties`**
   - prefix: `workshop.job-safety.audit`;
   - `transport`: `MEMORY`(기본) 또는 `HTTPS`;
   - 다음 기본값을 immutable constructor-bound 값으로 고정한다: `queueCapacity=32`,
     `maxInFlight=4`, `maxAttempts=3`, `attemptTimeout=2s`,
     `initialBackoff=100ms`, `maxBackoff=1s`, `recentHistoryLimit=32`,
     `shutdownTimeout=2s`;
   - `maxPayloadBytes`(기본 64 KiB, 1 MiB hard cap), `recentHistoryByteBudget`(기본
     512 KiB, 1 MiB hard cap), `maxBufferedBytes`(기본 72 MiB, 128 MiB hard cap)와
     `shutdownTimeout`(기본 2s, 30s hard cap)을
     함께 보관한다. aggregate 검증은 queue/in-flight payload의 defensive copy
     amplification 2배, recent history, upstream pending context metadata의
     보수적 `4,096 * 16 KiB` reservation을 포함한다. 모든 중간 계산은 `Long`으로 수행하고
     `Math.addExact`/`Math.multiplyExact`로 checked arithmetic를 적용한다:
     `((queueCapacity + maxInFlight) * maxPayloadBytes * 2) +
     recentHistoryByteBudget + (4096 * 16 KiB)`가 `maxBufferedBytes`를 넘거나
     checked arithmetic가 overflow하는 조합은 executor/queue/client를 만들기
     전에 startup에서 fail closed한다. 이는 JVM 객체 헤더를 포함한 heap 보장이
     아니라, 알려진 retained byte와 copy amplification에 대한 보수적 reservation
     budget이다. upstream pending store 자체도 4,096 entry, 15분 TTL과 metadata
     상한으로 bounded 된다. 단, upstream pending context가 보유하는
     `lockName`/`nodeId`/`slotId` 문자열은 workshop이 주입할 수 없는 upstream
     내부 raw identity이며 aggregate byte budget에 포함되지 않는다. 이 값은
     workshop audit adapter의 report/payload/metric/local log로 전달하지 않고
     upstream의 4,096 entry·15분 TTL 경계만 적용한다. upstream
     `SafeLeaderHistoryRecorder`가 sink 오류 시 남길 수 있는 core warning log는
     이 예제의 소유 범위 밖이며 raw-log 부재 보장에 포함하지 않는다. 매우 긴 identity를 넣어도 외부 산출물에 누출되지 않는
     회귀 테스트를 두며, raw identity heap 보장을 이 예제의 DoD로 주장하지
     않는다. upstream 저장 형식 변경 없이 이를 byte-bound로 바꾸는 작업은
     별도 upstream 범위다. upstream maximum 조합과 overflow 경계값을 별도
     properties 회귀 테스트로 검증한다;
   - `HTTPS`일 때만 endpoint를 필수로 하고, `MEMORY`에 endpoint가 지정되면
     startup을 fail closed한다;
   - HTTPS host는 설정된 exact `allowedHosts` allow-list에 포함되어야 하며,
     endpoint와 allow-list 항목을 lower-case·trailing-dot 제거한 DNS canonical
     form으로 비교한다. `localhost`, loopback/private/link-local/ULA/CGNAT,
     IPv4 대체 표기와 IPv6 literal은 거부한다. upstream URI 문법 검증 외에
     unknown host도 거부한다.
     DNS rebinding과 허용 host의 실제 주소 정책은 egress proxy 운영자가
     소유한다;
   - raw `authorization`은 전용 `AuditHeaders` value type의 private field로
     보관하고 UTF-8 8 KiB 상한을 적용하며 `toString`/`equals`/`hashCode`/report/
     metric에서 제외한다. `equals`/`hashCode`는 secret 원문이 아닌 authorization
     존재 여부만 비교한다. Actuator
     `configprops`와 `env`는 `show-values=never`로 고정하고 bind/log/configprops
     회귀 테스트에서 bearer 값이 나타나지 않음을 증명한다. HTTP headers는
     `content-type`/`authorization` allow-list만 사용한다.

2. **`AdmissionOnlyLeaderHistorySink`**
   - `LeaderHistorySink.recordAcquired`에서 내부 `LeaderHistoryKey`만 만들고,
     completion/failure 저장은 하지 않는 workshop adapter다.
   - `ExportingLeaderHistorySink`가 acquired record를 pending context로 보관하고
     완료/실패 event를 생성하므로, 이 sink는 authoritative history가 아니다.
   - key의 token은 upstream 내부 lifecycle에서만 사용하고 report/payload에는
     전달하지 않는다.

3. **`JobSafetyAuditPayloadEncoder`**
   - `LeaderAuditPayloadEncoder`를 구현하고 `bluetape4k-jackson3`의
     `Jackson.defaultJsonMapper`로 deterministic JSON을 만든다. 문자열 경유
     `writeValueAsString(...).toByteArray()` 대신 `writeValueAsBytes(...)`를
     사용하고, configured `maxPayloadBytes`를 넘으면 `LeaderAuditHttpPayload`
     생성 전에 명시적 terminal encoding failure로 거부한다.
   - `History`에는 occurredAt/kind/status/duration/error type과 정제된
     attributes만, `Lifecycle`에는 occurredAt/outcome/lease expiry와 정제된
     attributes만 포함한다. `token`, raw lock name, raw node/leader id,
     customer/tenant 원문은 DTO에 존재하지 않는다.
   - 결과는 `LeaderAuditHttpPayload.of("application/json", bytes)`로 생성해
     upstream의 1 MiB hard bound와 defensive copy를 사용한다.

4. **`RecordingLeaderAuditPayloadEncoder`와 `InMemoryAuditHttpClient`**
   - `RecordingLeaderAuditPayloadEncoder`는 실제 transport 앞에서
     `JobSafetyAuditPayloadEncoder`가 만든 immutable serialized bytes를
     `BoundedAuditPayloadStore`에 복사한 뒤 같은 `LeaderAuditHttpPayload`를 반환한다.
     따라서 MEMORY fake와 HTTPS client가 동일한 capture 경계를 사용하며, queue full,
     retry, transport 교체와 무관하게 최근 시도 payload가 같은 report에 보인다.
     store는 전송 성공/실패의 authoritative 기록이 아니고 프로세스 내 volatile
     observation이며 재시작 시 비고정·재전송하지 않는다.
   - 기본 `MEMORY` transport에서만 사용하는 loopback-free fake `HttpClient`다.
   - `https://audit.invalid/in-memory`라는 내부 sentinel URI에 대해 204를
     반환하고 실제 DNS/socket을 열지 않는다.
   - test fake는 gated `CompletableFuture`와 status script를 제공한다. 따라서
     즉시 완료 경로뿐 아니라 `submit` 즉시 반환, queue full drop, close 시
     underlying future cancellation을 deterministic하게 증명한다. body는
     `BodyPublisher.contentLength()`와 status script로 확인한다.
     `InMemoryAuditHttpClient`는 `shutdown`, `shutdownNow`,
     `awaitTermination(Duration)`, `isTerminated`, `close` lifecycle을 명시적으로
     구현하며 `shutdownNow`가 gated in-flight future를 취소하고 bounded await가
     반환되도록 한다. `close`는 idempotent이며 fake 자체도 daemon 작업을 남기지
     않는다.
     MEMORY fake는 body 길이와 status/cancellation만 검증하고 payload bytes를
     보관하지 않는다. raw `HttpRequest`/headers, sentinel URI, status, event view는
     보관하지 않고, `/api/job-safety/audit`가 필요할 때 capture store의 payload를
     Jackson tree로 일시 decode한다. `recentHistoryByteBudget`의
     단위는 retained payload bytes의 합계로 고정한다. MEMORY transport에서
     authorization header가 들어오면 fail closed한다. capture store는 예산을
     초과하면 오래된 payload를 제거하고, 하나의 payload가 예산보다 크면 저장하지
     않는다. decode 중 생성되는 임시 객체는 retained budget에 포함하지 않으며,
     이 store는 PostgreSQL history의 대체물이 아니다.

5. **`HttpLeaderAuditExporter` + `MicrometerLeaderAuditExporter`**
   - `MEMORY`에서는 fake client와 sentinel endpoint를 사용하고,
     `HTTPS`에서는 `HttpClient.newBuilder().followRedirects(NEVER)`와
     `LeaderAuditTrustedHttpsEndpoint.trusted(URI)`를 사용한다.
   - 두 경로 모두 동일한 `LeaderAuditExportOptions`와 encoder를 사용해 queue,
     retry, timeout, cancellation, snapshot semantics를 upstream public API에
     위임한다.
   - `MicrometerLeaderAuditExporter`는 delegate를 소유하고 먼저 닫는다. caller-
     owned virtual-thread executor/scheduler는 exporter close 이후 Spring이
     종료하도록 bean dependency를 구성한다. HTTPS `HttpClient`도 caller가
     소유하며 `JobSafetyAuditShutdownCoordinator`가 context close 시작 시 하나의
     monotonic `shutdownDeadline`을 계산한다. close 순서는 `subscription.close()` →
     `exporter.close()` → `client.shutdownNow()` →
     `client.awaitTermination(remaining(deadline))` → scheduler/executor의
     `shutdownNow()` 및 남은 시간만 사용하는 bounded await다. Java 25의
     `HttpClient.close()`가 긴 await를 수행할 수 있으므로 raw `close()`를 Spring
     destroy method로 직접 호출하지 않고 bounded lifecycle wrapper가
     `shutdownNow`/`awaitTermination`만 수행한다. 동일 executor를
     `HttpClient.Builder.executor(...)`로 주입하고, scheduler에는
     `removeOnCancelPolicy=true`를 설정하며 context close 후 모든
     `isTerminated`/남은 task 검증을 수행한다. bean 이름과 `@Qualifier`는
     `jobSafetyAuditExecutor`, `jobSafetyAuditScheduler`,
     `jobSafetyAuditHttpClient`, `jobSafetyAuditExportOptions`로 고정한다.

6. **`SafeLeaderHistoryRecorder`와 report API**
   - `ListeningLeaderElector(LettuceLeaderElector(...))`를 반환하는
     `jobSafetyLeaderElector` bean을 사용해 기존 `LeaderElector` 주입 계약을
     유지하면서 `LeaderElectionEventPublisher`를 노출한다. 생성된 publisher에
     `LeaderElectionEventExportSubscription`과 application-owned
     `CoroutineScope`를 연결해 `Elected`/`Revoked`/`Skipped` lifecycle을
     `LeaderAuditExportEvent.Lifecycle`로 제출한다. subscription은 close 시
     exporter보다 먼저 닫는다.
   - `SafeLeaderHistoryRecorder(
     ExportingLeaderHistorySink(AdmissionOnlyLeaderHistorySink(), exporter,
     LeaderAuditValueSanitizer.Default))`를 `LettuceLeaderElector`에 주입한다.
   - `JobSafetyAuditReportService`는 transport, enabled state, bounded recent
     event JSON tree view, `LeaderAuditExportSnapshot` DTO와 fixed meter names만
     반환한다. upstream `MicrometerNames`는 `internal`이므로 consumer에서
     import하지 않고, upstream public README/source에 정의된 12개 unique audit
     meter name과 `outcome` 변형을 `JobSafetyAuditMeterCatalog` private 상수로
     복사해 정렬된 순서로 노출한다. tree decode는 요청 시점의 임시 객체이며
     store retained bytes에는 포함되지 않는다.
   - lifecycle subscription이 사용할 `jobSafetyAuditScope`는
     `SupervisorJob + Dispatchers.Default`를 가진 application-owned
     `CloseableCoroutineScope` bean이며 `close()`에서 job을 취소한다. scope bean은
     subscription보다 늦게 닫히도록 dependency를 구성하고 잔여 collector가
     없는지 context close 테스트로 확인한다.
   - endpoint는 기존 stateless Basic auth 정책을 따르되 exporter 상태와 error
     type을 포함하므로 `JOB_SAFETY_OPERATOR` role에만 허용한다.

### 데이터 흐름

```text
LettuceLeaderElector
  -> SafeLeaderHistoryRecorder
  -> ExportingLeaderHistorySink
  -> LeaderAuditExportEvent.History (Default sanitizer)
  -> RecordingLeaderAuditPayloadEncoder -> bounded serialized payload store
  -> MicrometerLeaderAuditExporter
  -> HttpLeaderAuditExporter (bounded core)
  -> MEMORY fake client or explicitly trusted HTTPS client
  -> snapshot/metrics

ListeningLeaderElector (lifecycle publisher)
  -> LeaderElectionEventExportSubscription(jobSafetyAuditScope)
  -> LeaderAuditExportEvent.Lifecycle
  -> RecordingLeaderAuditPayloadEncoder (same store/exporter path)
```

`ListeningLeaderElector`는 기존 `RedisLeaderElectionAdapter`가 사용하는
`LeaderElector` 타입의 대체 구현이며 delegate state/lease/diagnostics를 그대로
위임한다. `LeaderElectionEventExportSubscription`은 이 publisher의 hot event를
application-owned scope로 구독하므로 coordinator의 실제 acquire/release 경로도
별도 수동 event 호출 없이 lifecycle report에 반영된다.

`ExportingLeaderHistorySink`가 delegate를 먼저 호출한다. delegate가 반환한
key가 있을 때만 acquired event를 제출하고, completion/failure에서는 pending
context를 `finally`로 제거한다. `LeaderAuditExporter.submit` admission만
non-blocking 계약이며, recorder의 pending-context fingerprint/metadata
sanitization은 leader 호출 thread의 유한 동기 비용이다. drop은 leader 작업의
성공/실패 결과를 바꾸지 않는다.

### 기본값과 외부 전송 경계

기본 `MEMORY` 경로는 외부 endpoint, credential, DNS, socket을 사용하지 않는다.
sentinel URI와 fake response는 report에 노출하지 않고 transport 이름만
`MEMORY`로 표시한다. 실제 HTTPS 전송은 다음을 모두 만족해야 한다.

- `transport=HTTPS`;
- endpoint가 absolute hierarchical `https` URI이고 user-info/query/fragment/
  control character가 없으며 exact `allowedHosts`에 포함됨;
- headers가 upstream allow-list를 통과함;
- timeout/queue/retry 값이 upstream 옵션 상한 안에 있음.

DNS rebinding, private/link-local/ULA/CGNAT 차단은
`LeaderAuditTrustedHttpsEndpoint`가 보장하지 않으므로 운영 caller 또는
egress proxy의 책임으로 문서화한다.

## 실패 모드와 복구

| 상황 | 기대 동작 | 관찰 증거 |
| --- | --- | --- |
| queue capacity 소진 | caller를 막지 않고 `DROPPED_QUEUE_FULL` 반환 | `droppedQueueFull`, queue gauge, report snapshot |
| 2xx delivery | event가 terminal success로 종료 | `accepted`, local event count |
| 408/429/5xx 또는 I/O | bounded backoff로 retry, attempts 초과 시 terminal failure | `retries`, `terminalFailures` |
| client/worker cancellation | in-flight future를 취소하고 원래 cancellation을 보존 | `cancellations`, zero in-flight after close |
| context close 중 submit | `DROPPED_CLOSED`, queued/retry/in-flight 정리 | `closed=true`, zero gauges |
| non-HTTPS/user-info/query/header 오류 | startup 또는 exporter construction fail closed | `IllegalArgumentException`, no request |
| encoder/redaction 오류 | delivery terminal failure, audit adapter의 raw payload/log 미출력 | terminal counter, absence assertions. upstream `SafeLeaderHistoryRecorder`의 일반 sink 오류 로그는 별도 core 계약이며 이 예제의 global raw-log 부재 주장은 하지 않는다. |

일반 예외는 exporter가 delivery 결과로 정규화하지만 `CancellationException`과
`InterruptedException`은 삼키지 않는다. coordinator가 context close 시작 시 하나의
monotonic `shutdownDeadline`을 계산하고 subscription → exporter → HTTP client bounded
shutdown/await → scheduler/executor bounded shutdown/await 순서의 각 단계에 남은
시간만 전달한다. 따라서 resource별 timeout 합산으로 aggregate deadline을 초과하지
않는다. shutdown 중 예외는 log에 resource 종류만 남긴다. scheduler의 cancelled
retry task는 `removeOnCancelPolicy`로 즉시 제거한다.

## 테스트 계약

### 단위 테스트

- `JobSafetyAuditPropertiesTest`: 기본 MEMORY 값, queue/in-flight/attempt/timeout
  bound, aggregate byte budget, HTTPS endpoint/allowed host 필수, HTTP endpoint/
  endpoint+MEMORY/header 오류를 `ApplicationContextRunner`로 fail closed 검증하고
  서로 다른 authorization secret의 `AuditHeaders` equality/hash가 같은지 확인;
- `JobSafetyAuditPayloadEncoderTest`: History acquired/completed/failed와
  lifecycle payload의 필드 집합, UTF-8/1 MiB bound, lock/token/tenant/raw
  exception 부재를 `Jackson` tree와 byte assertion으로 검증하고,
  `writeValueAsBytes` 경로와 configured payload bound를 검증;
- `AdmissionOnlyLeaderHistorySinkTest`: key 생성, completion/failure pending
  context 제거, default sanitizer redaction, delegate persistence 비사용 검증;
- `JobSafetyAuditExporterTest`: 첫 번째 gated request를 release하기 전에 두 번째
  submit을 수행해 queue full을 결정적으로 만들고, thread-safe status script의
  204/429/500, retry/terminal failure, close와 cancel 후 snapshot/gauge를 검증하고,
  concurrent submit의 non-blocking admission latency upper bound와 queue/store byte
  budget을 확인한다. fake의 request-count helper도 bounded timeout을 자체 적용한다.
  upstream public exporter를 직접 사용하며 ad-hoc queue
  구현을 테스트하지 않는다;
- `JobSafetyControllerTest`: operator만 `/api/job-safety/audit`에 접근하고,
  response가 bounded event, snapshot counter와 fixed meter name만 포함하며
  sentinel endpoint/header/raw 값을 포함하지 않음을 검증.
- `JobSafetyAuditReportServiceTest`: MEMORY와 HTTPS 두 fixture가 동일 event에 대해
  같은 retained JSON/redaction/byte budget 결과를 report에 보여주는지 parameterized
  비교를 수행하고 `report.transport == transport.name`을 고정한다. HTTPS fixture는
  실제 socket 대신 gated fake client의 bounded request-count assertion을 사용한다.

### 모듈·통합 검증

- `:leader-job-safety-lab:test`는 container 없이 기본 MEMORY context와
  existing scenario regression을 함께 실행한다.
- `integrationTest`는 기존 PostgreSQL/Redis 경계를 직렬로 실행하고, 실제
  `LeaderElectionPort.tryAcquire`/`release` 경로 한 번이 listening elector의
  `Elected`/`Revoked` lifecycle과 history acquired/completed event를 exporter에
  제출하는지 bounded await로 확인한다. failed history는 sink 단위 테스트에서
  예외 메시지 redaction과 함께 확인한다.
- 별도 network endpoint를 사용하지 않는 fake `HttpClient` test가 HTTPS status
  classification을 재현한다. real external audit system은 실행하지 않는다.
- `detekt`, `git diff --check`, `./gradlew projects`, stale-check,
  `actionlint`와 Examples smoke/full workflow registration을 실행한다.

### 보안·운영 검증

- default test process에서 DNS/socket/credential 환경변수를 요구하지 않는다.
- report, JSON body, metric tag, workshop audit adapter가 소유한 local log에서
  token, raw lock/job/tenant/customer, raw exception message, authorization header를
  grep으로 확인한다. upstream core의 `SafeLeaderHistoryRecorder` failure warning은
  별도 logging contract로 분리해 이 grep의 대상에서 제외한다.
- upstream pending context에 긴 `lockName`/`nodeId`/`slotId`를 주입한 회귀
  테스트에서도 해당 raw identity가 report/payload/metric/workshop local log에 나타나지 않고,
  bounded entry/TTL 외의 heap byte bound는 주장하지 않음을 확인한다.
- `maxPayloadBytes`, queue/in-flight, recent history byte budget 조합과
  `maxBufferedBytes` aggregate guard를 검증하고, gated fake에서 caller blocking
  upper bound와 close 후 남은 task/request를 확인한다. recorder 전체 경로는
  동시 acquire/completion latency와 pending context 상한을 별도 측정한다.
- `allowedHosts` 비교는 lower-case/trailing-dot canonicalization을 사용하고,
  localhost/loopback/private/link-local/ULA/CGNAT/IPv4 대체 표기/IPv6 literal을
  negative test로 고정한다. 허용 host의 DNS rebinding과 실제 egress 주소
  정책은 운영 proxy 책임이다. JDK HTTP wire/header debug logging은 운영에서
  활성화하지 않는다.
- context close 뒤 snapshot의 queued/inFlight/scheduledRetries와 executor/
  scheduler lifecycle을 확인한다. runtime contract는 recording fake의 trace로
  `subscription.close → exporter.close → client.shutdownNow →
  client.awaitTermination(timeout) → scheduler.shutdownNow/await →
  executor.shutdownNow/await → scope.close` 순서와 timeout 내 반환,
  `isTerminated=true`, `removeOnCancelPolicy=true`, scheduler queue empty와
  cancelled retry task 제거를 직접 assertion한다. context callback 내부의
  `assertTimeoutPreemptively`로 close가 hang할 경우에도 테스트가 종료되도록 하며,
  fixture의 `shutdownTimeout=500ms`, outer watchdog=3s로 production budget과
  테스트 watchdog를 분리한다.
- queue/retry 설정은 small deterministic values로 stress를 제한하고, 실제
  production throughput이나 exactly-once를 주장하지 않는다.

## 호환성·rollback·재실행

- root `gradle/libs.versions.toml`의 `bluetape4k-jackson3` alias와 기존
  `bluetape4k-leader-*` alias는 versionless로 유지한다. 모든 버전은
  `platform(libs.bluetape4k.dependencies)`에서 해결한다.
- `LettuceLeaderElector`의 기존 두 인자 호출과 job-safety scenario/public
  controller 동작은 보존한다. 새 recorder는 leader audit 부가 경로일 뿐
  fencing/DB/outbox 판단에 참여하지 않는다.
- upstream audit API가 release artifact에서 사라지거나 snapshot 계약이
  바뀌면 implementation을 중단하고 dependency producer 상태를 먼저 복구한다.
- rollback은 audit properties/configuration, local adapter/encoder/report,
  tests/docs와 Jackson alias를 함께 revert하면 된다. 기존 job-safety 경로는
  별도 commit으로 남아야 한다.
- lifecycle 또는 timing failure가 나면 다음 순서로 재실행한다.
  `:leader-job-safety-lab:test --no-build-cache --rerun-tasks --no-parallel
  --max-workers=1` → `detekt` → `integrationTest --max-workers=1`.

## 수용 기준과 DoD 매핑

| Issue #867 기준 | 구현·검증 산출물 |
| --- | --- |
| lifecycle report와 Micrometer decorator 연결 | `JobSafetyConfiguration`, recorder wiring, meter assertions |
| 기본 in-memory/NOOP, endpoint 없는 smoke | `MEMORY` fake client, properties/context tests, README command |
| 명시적 trusted HTTPS opt-in | `HTTPS` factory, endpoint/header negative tests, fake status tests |
| bounded payload와 redaction | encoder DTO, sanitizer assertions, report/log grep, aggregate byte guard |
| queue/drop/retry/close/cancel 관찰 | upstream exporter snapshot/meter test와 lifecycle test |
| PostgreSQL authority 보존 | admission-only sink, existing repository/integration regression |
| versionless BOM/README/matrix/workflow/stale/lesson | Gradle/catalog diff, 양국 README, registry checks, lesson |

최종 DoD는 current diff에 대해 P0=0, P1=0, targeted/module validation green,
문서 locale parity, workflow/stale registration, committed lesson, exact PR
head와 live CI evidence를 포함한다. merge는 exact head에 대한 별도 fresh
`승인` 이후에만 수행한다.

## 대안과 기각 사유

### A. `NoopLeaderHistorySink`만 사용하고 report를 만들지 않음

기각한다. 기본 외부 전송은 막을 수 있지만 `LettuceLeaderElector`가 non-null
history key를 만들지 않아 acquired/completed/failed export event와 queue
snapshot을 실행할 수 없다. Issue #867의 핵심 학습 목표를 충족하지 못한다.

### B. workshop 전용 bounded exporter를 새로 구현

기각한다. snapshot lifecycle, retry/backoff, cancellation, observer와 meter
identity를 upstream 내부 구현과 중복하게 되고 2.0.0 개선 기능을 실제로
소비하는 예제가 되지 않는다. 특히 `LeaderAuditExportSnapshot` 생성자가
public하지 않아 public API 경계를 우회해야 한다.

### C. 선택안: public `HttpLeaderAuditExporter` + fake memory client + HTTPS opt-in

선택한다. queue/retry/close semantics와 Micrometer fixed meter를 upstream에
위임하면서 default path는 실제 네트워크 없이 실행할 수 있다. 같은 encoder,
options, report를 사용하므로 memory와 HTTPS 예제의 차이는 delivery trust
경계로 제한된다.

## SPW writer gate

- **SPW-01:** artifact=spec, 독자=workshop contributor/operator, 목적=Issue #867
  acceptance를 현재 source와 upstream API에 연결, 근거=Issue #867·upstream #535/#792·
  local `JobSafetyConfiguration`/`JobSafetyProperties`/`JobSafetyController`;
  미확정=운영 DNS/SSRF policy는 caller 소유로 명시했다.
- **SPW-02:** 문제, 목표/제외, 구성·데이터 흐름, 실패 모드, 테스트, 호환성,
  rollback, DoD와 대안을 포함했다.
- **SPW-03:** 한국어 기술 문체로 작성하고 code/API/commands/URLs/numbers를
  원문 토큰으로 보존했다. 자연스러움 점검 결과: 번역투·홍보 문장·모호한
  결론 없음.
- **SPW-04:** upstream class/method names와 local paths를 source에서 대조했고,
  public snapshot 생성자를 직접 사용하지 않는 선택을 기록했다.
- **SPW-05:** Markdown heading/table/code fence를 read-back했고 placeholder와
  모순을 제거했다. transport-independent capture, listening publisher lifecycle,
  bounded HTTP shutdown과 secret-independent header equality를 반영한 뒤
  artifact checklist `SPW-01~05=PASS`를 재확인했다.

이 spec을 기준으로 구현 plan을 작성하고, 여섯 관점의 spec/plan review에서
P0/P1을 0으로 수렴시킨 뒤 TDD 구현을 시작한다.
