# Issue #522 결정론적 고경합 Load·Failure Profile 설계

## 1. 목적

Issue #522는 Job Operations Console과 Concert Ticket Flash Sale 예제가 고경합과 장애 상황에서도
권위 상태, idempotency, fencing, reconciliation, outbox replay 불변식을 보존하는지 반복 가능하게
증명한다.

이 작업의 결과는 framework 성능 순위나 production capacity 수치가 아니다. 같은 versioned
profile을 Spring Job Console, Ktor Job Console, Spring Ticket Flash Sale에 적용하고 다음 두 종류의
증거를 명확히 분리하는 것이 목적이다.

1. 환경과 무관하게 반드시 통과해야 하는 correctness·convergence gate
2. 실행 환경과 함께 기록하는 throughput·latency 관찰값

## 2. 현재 근거

### 2.1 Issue 계약

Issue #522는 다음을 요구한다.

- 재현 가능한 arrival curve, duration, hardware/container topology, fake clock/provider control
- queue/admission ordering, inventory, idempotency, reconciliation, lease recovery, outbox replay 검증
- Redis unavailable/key loss, slow payment, worker restart, duplicate delivery 장애 주입
- setup, workload, expected invariant, measured evidence, known limitation을 포함한 결과
- heavyweight topology의 순차 실행과 framework별 독립 결과

명시적 비목표는 cross-framework winner ranking, developer machine 결과의 production capacity 주장,
기존 functional failure fixture 대체다.

### 2.2 기존 구현

Job Console은 다음 재사용 표면을 이미 제공한다.

- `JobConsoleScenario`
- `JobConsoleFixtureClock`
- `JobConsoleBarrier`
- `JobConsoleContainerFixture`
- PostgreSQL authority, Redis cancel signal, lease/checkpoint, outbox fixture
- Spring과 Ktor의 실제 HTTP integration test

Ticket Flash Sale은 다음 재사용 표면을 이미 제공한다.

- `AbstractTicketIntegrationTest`
- `PurchaseFixture`
- deterministic payment provider와 `Clock`
- PostgreSQL inventory/attempt/order/payment/ticket authority
- Redis multi-key lease와 DB active guard
- worker restart, reconciliation, duplicate effect fixture
- opt-in `TicketStressProfileTest`

Bluetape ecosystem에는 `bluetape4k-testcontainers`의 `ToxiproxyServer`와 `RedisServer`를 같은
Docker network에 연결하는 검증된 fixture가 있다. 저장소에도 Kafka broker path를 실제로
차단·복구하는 `UsageBillingMicroserviceFixture`가 있다. #522는 Bluetape
`ToxiproxyServer`/`RedisServer` 조합을 우선 사용하고 기존 Kafka fixture의 path-cut pattern만
참고한다. Kafka fixture 자체나 raw `ToxiproxyContainer`에는 의존하지 않는다.

## 3. 범위

### 3.1 포함

- repository-local versioned high-contention profile contract
- Job Console core/Spring/Ktor module-local profile runner
- Ticket Spring Boot module-local profile runner
- 실제 PostgreSQL과 Redis Testcontainers topology
- Toxiproxy를 통한 Redis network path 단절·지연·복구
- 격리된 Redis 대상 key 제거를 통한 key-loss fixture
- 실제 application context 또는 worker lifecycle restart
- fake clock/provider와 barrier를 통한 결정론적 domain transition
- framework별 JSON report와 correctness summary
- container-backed CI와 nightly의 순차 실행·artifact 보존
- English/Korean README 실행법과 결과 해석

### 3.2 제외

- 새 generic queue, load, idempotency, Redis lock, failure-injection library
- 별도 공용 `backend-contract-test-kit` Gradle module
- 실제 payment provider, WAF, CAPTCHA, production Redis/Kafka cluster
- framework throughput ranking
- production SLO나 capacity 약속
- wall-clock sleep에 의존하는 domain transition
- PostgreSQL/Redis authority를 mock으로 대체하는 correctness proof

## 4. 검토한 접근

### 4.1 선택: 공통 계약과 module-local runner

공통 profile 정의와 report contract는 repository asset으로 관리한다. 각 application은 기존
fixture를 사용하는 module-local adapter를 구현한다. repository coordinator는 heavyweight task를
한 번에 하나씩 실행하고 결과를 독립 artifact로 모은다.

장점:

- #520·#521의 현재 fixture와 transaction boundary를 직접 재사용한다.
- Kotlin `internal` 구현을 다른 module에 공개하기 위한 production API가 필요 없다.
- Job Console과 Ticket의 서로 다른 권위 모델을 억지 SPI로 통일하지 않는다.
- profile/report 의미는 공통으로 유지한다.

### 4.2 거절: 독립 integration profile module

하나의 새 module이 모든 application을 기동하면 실행 진입점은 단순해진다. 그러나 두 도메인의
internal fixture를 재노출하거나 HTTP·DB bootstrap을 중복 구현해야 한다. application 변경 때마다
cross-domain mega-module이 깨지고 repository 연구에서 보류한 generic test-kit을 사실상 먼저
도입하게 된다.

### 4.3 거절: module별 완전 독립 profile

현재 test task만 확장하는 방식은 초기 diff가 작다. 그러나 arrival curve, report 필드,
correctness/observation 구분, failure trigger 의미가 빠르게 달라진다. #522가 요구하는 공통 재현
계약을 만족하지 못한다.

## 5. Architecture

```text
profiles/high-contention/v1/
  profile-contract.json
  report-contract.json
  schedule-vectors.json
  suite-manifest.json
  profiles/
    ci-correctness/<profile-id>.json
    local-reference/<profile-id>.json
                |
       sequential coordinator
        /        |         \
 Job Spring   Job Ktor   Ticket Spring
      \          |          /
   module-local profile adapters
              |
 PostgreSQL + RedisServer + ToxiproxyServer
```

`profiles/high-contention/v1`은 Gradle library가 아니라 executable test asset이다. application
adapter가 profile 문서를 읽어 workload를 구성하고, 실행 뒤 같은 report contract로 결과를
쓴다.

공통화 대상은 data contract와 report vocabulary뿐이다. domain command, repository, worker,
payment, ticket effect를 공통 interface로 추출하지 않는다.

`suite-manifest.json`은 `suiteSchemaVersion`, `runDeadline`, `runCleanupActionBudgets`,
`runJournalFinalizeReserve`, `runCleanupReserve`, `dockerCleanupPollInterval`,
`dockerCleanupQuietPeriod`와 ordered entry마다 `mode`, unique `profileId`, normalized relative
`profileFile`, 적용할 ordered `implementations[]`를 선언한다. coordinator selection key는
`(mode, profileId, implementation)`이며 manifest가 expected report matrix와 run-level budget의
유일한 source다. 각 profile file은 단일 `profileId`·curve·failure를 담고 경로의 mode/profile
ID와 내용이 일치해야 한다.

## 6. Profile contract

각 profile 정의는 다음 값을 고정한다.

| 필드 | 의미 |
|---|---|
| `profileSchemaVersion` | profile contract version |
| `profileId` | stable profile identity |
| `mode` | `ci-correctness` 또는 `local-reference` |
| `seed` | identity와 command 순서 생성 seed |
| `arrivalCurve` | `burst`, `step`, `retry-storm` |
| `operationCount` | 제출할 operation 수 |
| `concurrency` | 최대 동시 실행 수 |
| `dispatcherBacklogCapacity` | scheduler와 bounded executor 사이 queue 상한 |
| `maxScheduleDelay` | scheduled offset 대비 dispatch 지연 허용 관찰 경계 |
| `warmupOperationCount` | 측정에서 제외할 bounded warm-up 수 |
| `workloadDuration` | warm-up과 recovery를 제외한 제출 schedule 기간 |
| `epochs` | step curve의 단계별 duration과 operation 수 |
| `retryShape` | identity 수, 최초 시도를 포함한 `attemptsPerIdentity`, retry epoch duration (`retryCount = attemptsPerIdentity - 1`) |
| `contentionShape` | domain별 hot authority cardinality, collision/skew 분포 |
| `expectedSubmissionOutcomes` | 최소 dispatch/completion과 local rejection/missed-deadline 상한 |
| `failure` | 장애 종류와 barrier trigger |
| `operationTimeout` | 개별 client/database operation의 최대 실제 시간 |
| `injectionDeadline` | trigger barrier에 도달할 최대 실제 시간 |
| `failureDetectionDeadline` | 지정 operation이 fault를 관측할 최대 실제 시간 |
| `workloadJoinDeadline` | workload executor를 완료·취소·join할 최대 실제 시간 |
| `recoveryDeadline` | 상태 수렴을 기다릴 최대 실제 시간 |
| `cleanupActionBudgets` | resource/action별 ordered cleanup 최대 실제 시간 |
| `reportFinalizeReserve` | terminal report와 child journal finalize 전용 시간 |
| `cleanupReserve` | non-cleanup phase가 소비할 수 없는 profile cleanup 전용 시간 |
| `profileDeadline` | setup부터 cleanup까지 profile 전체 wall-clock budget |
| `expectedInvariants` | 반드시 통과해야 하는 invariant ID |
| `observationFields` | report-only 측정값 |

canonical schedule은 floating-point와 platform PRNG를 사용하지 않는다. seed-derived rank는
`SHA-256(UTF-8("$profileSchemaVersion:$seed:$kind:$ordinal"))`의 32-byte unsigned big-endian
lexicographic order로 정하고 digest가 같으면 original ordinal로 tie-break한다. authority selection은
profile에 명시한 authority ordinal별 positive integer weight의 누적 구간에서 digest의 첫 8 byte를
unsigned integer로 읽고 `mod sum(weights)`한 위치를 선택한다.

`burst`는 모든 제출 offset이 0이고 stable ordinal로 tie-break한다. `step`의 epoch 안 i번째
operation offset은 overflow-safe integer arithmetic의
`epochStartNanos + floor(i * epochDurationNanos / epochOperationCount)`이며 epoch는 half-open
`[start, end)`다. `retry-storm`은 identity를 위 digest rank로 정렬하고 `(identityRank,
attemptOrdinal)` 순서의 slot에 같은 floor 산식을 적용한다. 최종 ordered token tie-break는
`(offsetNanos, identityRank, attemptOrdinal, stableOrdinal)`이다. 세 curve 모두 open-loop schedule을
먼저 계산하며, bounded executor가 밀리더라도 closed-loop로 바꾸지 않는다.

`schedule-vectors.json`은 edge case를 포함한 versioned golden
`(offsetNanos, stableOrdinal, identityOrdinal, attemptOrdinal, authorityOrdinal)` 목록과 expected
SHA-256을 보유한다. 모든 module-local runner는 이 vector를 통과해야 하고 schedule 알고리즘 변경은
새 profile schema/version directory를 요구한다.

scheduler는 ordered arrival token만 생성하고 dispatcher/executor와 분리한다. dispatcher의 bounded
backlog가 가득 차면 scheduler thread를 block하지 않고 해당 token을 `LOCALLY_REJECTED` terminal
observation으로 기록한다. queue에 들어간 token은 dispatch하며 `maxScheduleDelay`를 넘으면
`MISSED_DEADLINE` observation도 함께 기록한다. 어떤 scheduled token도 조용히 사라지지 않는다.
같은 profile 문서와 seed는 항상 같은 ordered submission offset 목록을 생성해야 한다.

Job contention shape는 tenant 수, hot-tenant 비율, tenant별 job 수, idempotency key collision
distribution을 고정한다. Ticket shape는 sale/grade 수, grade별 inventory, user/IP pool cardinality,
same-user/same-IP collision ratio와 hot-grade skew를 고정한다. report는 설정값뿐 아니라 실제
authority key cardinality와 collision count를 기록한다.

parser는 `operationCount == sum(epochs.operationCount)` 또는 retry profile의
`identityCount * attemptsPerIdentity`를 검증하고 workload duration이 epoch duration 합과
일치하는지 확인한다. contention cardinality는 operation count보다 클 수 없고 backlog,
concurrency, schedule delay, warm-up count, retry shape, 모든 phase deadline은 mode별 상한 안에
있어야 한다. `expectedSubmissionOutcomes`의 최소값은 operation count를 넘을 수 없고
`minimumCompleted <= minimumDispatched`여야 한다.
`cleanupReserve >= sum(cleanupActionBudgets) + reportFinalizeReserve`이고
`cleanupReserve < profileDeadline`이어야 한다. suite parser는
`runCleanupReserve >= sum(runCleanupActionBudgets) + runJournalFinalizeReserve`와
`runCleanupReserve < runDeadline`을 검증한다. `dockerCleanupPollInterval > 0`,
`dockerCleanupQuietPeriod >= 2 * dockerCleanupPollInterval`, Docker discovery action budget이
quiet period, 추가 poll interval, bounded delete/recheck budget의 합 이상인지도 검증한다. parser가
계산한 effective configuration 전체를 report에 다시 기록하여 원본 profile 없이도 실행 조건을
재구성할 수 있게 한다.

`ci-correctness`의 local rejection과 missed deadline 기본 상한은 0이다. 명시적 overload profile만
bounded nonzero 상한을 가질 수 있다. minimum dispatched/completed, configured-versus-realized
contention tolerance를 충족하지 못하면 domain invariant가 우연히 PASS해도 run은 PASS가 아니며
terminal `ERROR`, `errorCode=INVALID_REALIZATION`로 종료한다.

### 6.1 실행 등급

`ci-correctness`는 작은 고정 부하로 모든 correctness와 recovery branch를 실행한다. 절대 latency나
throughput threshold를 gate로 사용하지 않는다.

`local-reference`는 opt-in 고경합 부하로 더 큰 operation count와 concurrency를 사용한다.
correctness gate는 동일하고 latency·throughput을 추가로 기록한다. nightly에서도 이 mode를
실행할 수 있지만 결과는 hosted runner 환경의 관찰값으로만 해석한다.

### 6.2 시간 모델

- domain timeout, lease expiry, reconciliation transition에는 controllable `Clock`을 사용한다.
- barrier release와 operation count가 장애 주입 시점을 결정한다.
- latency 측정에는 monotonic `System.nanoTime()`을 사용한다.
- warm-up은 workload duration과 latency/throughput 측정에서 제외한다.
- workload elapsed observation은 start barrier release부터 마지막 workload completion까지이며 recovery
  deadline은 포함하지 않는다.
- recovery polling에는 Awaitility와 별도 bounded real-time deadline을 사용한다.
- run 시작 시 `runExecutionDeadline = absoluteRunDeadline - runCleanupReserve`를 계산한다.
  coordinator는 새 profile 전에 `profileDeadline` 전체가 run execution budget에 남았는지
  검사한다. profile 시작 시
  `profileExecutionDeadline = absoluteProfileDeadline - cleanupReserve`를 계산한다.
- non-cleanup phase timeout은
  `min(configured phase budget, remaining profile execution budget, remaining run execution budget)`으로
  줄인다. cleanup은 미리 예약한 `cleanupReserve` 안의 independent hard deadline을 사용하고
  run-level compensating cleanup은 `runCleanupReserve`를 사용한다.
- correctness를 만들기 위한 고정 `Thread.sleep`은 사용하지 않는다.

## 7. Profile matrix

| Profile | Job Console invariant | Ticket invariant |
|---|---|---|
| `burst` | tenant FIFO, active job 최대 1, queue version 수렴 | admission ordering, `held + sold <= total`, identity guard |
| `duplicate-storm` | 같은 key가 job·sequence·terminal history를 한 번 생성 | 같은 request가 attempt·order·ticket를 한 번 생성 |
| `redis-path-outage` | signal 단절 후 PostgreSQL cancel state에서 수렴 | 신규 purchase fail closed, 기존 payment는 DB state에서 수렴 |
| `redis-key-loss` | key 유실이 durable cancel/history를 지우지 않음 | DB active guard가 재승인을 차단 |
| `slow-provider` | 장시간 work가 lease/checkpoint fencing을 깨지 않음 | timeout 뒤 hold를 유지하고 reconciliation으로 이동 |
| `worker-restart` | lease expiry 뒤 checkpoint 재개, stale commit 거절 | same operation ID 재개, duplicate issue/refund 없음 |
| `duplicate-delivery` | stable event ID당 effect 한 번 | ticket/refund effect와 receipt 한 번 |

## 8. 실행 흐름

각 application/profile 조합은 fresh topology에서 다음 순서로 실행한다.

1. profile과 report contract를 parse하고 caller input을 검증한다.
2. strict allowlist를 통과한 unique run ID와 비어 있는 output directory를 확인한다.
3. source/profile digest, expected implementation/profile 조합을 포함한 append-only run
   manifest/journal을 먼저 만들고 phase transition을 기록한다.
4. Docker, CPU/memory/disk quota, profile/run cleanup reserve와 전체 wall-clock budget을
   preflight한다.
5. PostgreSQL, Redis, 필요한 Toxiproxy의 owner와 close action을 acquired-resource ledger에
   `ALLOCATED` 상태로 먼저 등록한 뒤 `STARTING`, `STARTED` transition을 기록하며 시작한다.
6. application 또는 domain fixture를 profile seed로 초기화한다.
7. 별도 namespace에서 bounded warm-up을 실행하고 authority/effect baseline을 snapshot한다.
8. workload worker와 fault-observer operation을 barrier에 준비시킨다.
9. barrier 또는 accepted-operation count에서 장애를 주입한다.
10. 지정 operation 하나 이상이 bounded client timeout 안에 fault를 실제 관측했는지 확인한다.
11. workload를 deadline 안에 완료하거나 cancel한 뒤 bounded join한다.
12. fault 관측 뒤에만 장애 경로를 복구하고 data-plane operation 성공으로 recovery를 판정한다.
13. PostgreSQL authority, Redis 보조 상태, effect receipt를 baseline delta로 query한다.
14. invariant별 correctness gate를 판정한다.
15. 실행·검증 예외를 수집한 뒤 outer `finally`에서 resource ledger를 reverse order로 cleanup한다.
16. cleanup outcome까지 포함한 단 하나의 immutable terminal report를 임시 파일에 쓰고 fsync한 뒤
    replace 없는 atomic move로 확정한다. serialization 자체가 실패하면 manifest/journal에
    redacted error class와 terminal `ERROR`, `errorCode=REPORT_SERIALIZATION` fallback record를
    남긴다.
17. manifest/journal을 final 상태로 닫고 expected report가 모두 존재하며 manifest와 일치하고
    live resource가 0인 것을 확인한 뒤 다음 topology를 시작한다.

Testcontainers profile은 Gradle parallel, 다른 module profile, native subagent test lane과 동시에
실행하지 않는다.

## 9. 장애 주입

### 9.1 Redis network path

같은 Testcontainers `Network`에 per-profile `RedisServer()`와 `ToxiproxyServer()`를 직접
생성하고 Redis network alias를 Toxiproxy upstream으로 연결한다. toxic 상태가 다른 profile로
유출될 수 있으므로 `ToxiproxyServer.Launcher` singleton은 사용하지 않는다. proxy endpoint는
Spring/Ktor application context와 Lettuce client를 시작하기 전에 test-only fixture property로
주입하며 production test hook은 추가하지 않는다. startup assertion은 application의 effective
Redis URI가 proxy endpoint와 일치하고 direct Redis host/port가 client configuration에 없음을
검증한다.

Job Console은 기존 shared fixture의 별도 proxied factory가 `Network`, Redis, Toxiproxy, proxy
URI의 ownership을 갖고 Spring/Ktor test fixture에 URI만 제공한다. Ticket은 module-local
container fixture가 같은 ownership contract를 따른다. close는 application/client, proxy toxic과
proxy, Toxiproxy, Redis, Network 순서로 각각 시도하여 일부 cleanup 실패가 나머지 resource 정리를
막지 않게 한다.

warm-up은 기존 pooled connection을 명시적으로 수립하고 connection identity를 evidence에 남긴다.
기존 connection outage는 `reset_peer` 또는 bounded `timeout` toxic으로, 신규 connection outage는
proxy disable/listener-down으로 각각 검증한다. `bandwidth` toxic은 unavailable profile이 아니라
별도 slow/degraded observation에만 사용한다.

control-plane 명령 성공과 data-plane failure는 별도 증거다. 하나의 outage profile 안에서도
`failureInjection.steps[]`를 순차 실행한다.

1. warm-up에서 만든 old pooled connection에 `reset_peer` 또는 bounded `timeout` toxic을 적용한다.
2. reserved fault-observer executor의 old-connection operation이 bounded client timeout 안에
   기대 failure class를 관측한 뒤 toxic을 제거한다.
3. 새 connection의 `PING` 또는 domain Redis operation 성공으로 old-connection path recovery를
   확인한다.
4. resolved `toxiproxy-java`의 `Proxy.disable()`을 호출하여 listener-down 상태를 만들고 fresh
   client의 new-connection operation이 allowlisted connection-establishment failure를 관측하게
   한다.
5. proxy를 enable하고 또 다른 fresh connection의 data-plane operation 성공으로 recovery를
   확인한다.

각 step은 target connection class, control action, owned toxic ID, armed/triggered/confirmed/restored/
recovered timestamp, expected/observed failure class를 독립 기록한다. 정상 scenario의 validation
restore는 confirmed 뒤에만 허용한다. timeout/error의 emergency compensating cleanup은 confirmation과
무관하게 always-run이며 owned toxic 제거와 `Proxy.enable()`을 시도한다. proxy 생성·toxic 조작에
필요한 `ToxiproxyClient`는 `ToxiproxyServer`가 노출하는 격리된 control endpoint에만 사용한다.
현재 catalog가 해석한 `toxiproxy-java 2.1.11`의 compile-time API는 `Proxy.disable()`과
`Proxy.enable()`이며 runner compile test가 이 계약의 drift를 검출한다. 버전을 module에서 직접
pin하지 않는다.

실패 중에는 Redis를 correctness authority로 승격하지 않는다.

- Job Console은 durable cancel row와 terminal history를 PostgreSQL에서 확인한다.
- Ticket은 신규 purchase를 fail closed 처리하면서 이미 commit된 payment/reconciliation workflow를
  계속 진행한다.

### 9.2 Redis key loss

격리된 test Redis에서 해당 run이 소유한 namespace의 key만 제거한다. canonical namespace는
`hc:v1:<validated-run-id>:<implementation-id>:<profile-id>:`처럼 terminal delimiter를 포함하고
각 key를 exact owner-key parser로 다시 검증한다. write worker를 barrier에서 pause하고 exact
owned-key manifest 또는 bounded `SCAN` + `UNLINK`가 convergence한 뒤 resume한다.

SCAN 결과가 owner parser를 통과하지 않거나 delete candidate가 profile-derived upper bound를
넘으면 아무 key도 삭제하지 않고 terminal `ERROR`, `errorCode=KEY_SCOPE_VIOLATION`으로 종료한다.
삭제 key count, shared-prefix neighbouring namespace와 foreign sentinel 보존을 함께 검증한다.
production 코드에 `flushAll`이나 test-only management API를 추가하지 않는다.

### 9.3 Worker restart

- Job Console의 old worker는 transaction 밖이며 open DB transaction, connection, row/advisory
  lock을 보유하지 않는 non-locking pre-commit barrier에서 pause하고 획득한 lease/generation
  token과 pending commit input만 기록한다. fake clock으로 lease expiry를 진행한 뒤 새 worker가
  같은 DB를 takeover하고 commit한다. 그 후 old attempt를 release하여 새 짧은 transaction에서
  기존 token CAS를 시도하고 authority가 `IGNORED_FENCED`로 거절하는 실제 stale-commit 경로를
  증명한다. pause 중 open transaction/held lock count는 0이어야 한다.
- Spring/Ktor adapter profile은 application context lifecycle을 실제로 close/restart하고 durable
  state와 HTTP snapshot 수렴을 확인한다. context와 worker executor는 별도로 close/restart하며
  각각 bounded termination을 검증한다.
- Ticket은 payment/ticket/refund worker context를 재생성하고 stable operation ID를 유지한다.
  old attempt의 late effect/receipt count는 0이어야 하고 takeover attempt만 terminal effect의
  one-terminal-winner가 된다.
- pause barrier는 성공·실패·timeout 모두 `finally`에서 release하여 old worker가 영구 대기하지
  않게 한다.

### 9.4 Slow provider와 duplicate delivery

slow payment는 deterministic fake provider의 real-time bounded barrier에서 응답을 보류하고 fake
clock으로 domain timeout 경계를 넘긴다. reconciliation takeover가 terminal authority를 commit한
후 original late response를 release하여 그 attempt가 `IGNORED_FENCED`이고 effect/receipt를
추가하지 않음을 확인한다. barrier는 `finally`에서 fail-open release하고 provider future도
cancel·bounded join한다.

duplicate delivery는 같은 stable event ID와 payload digest를 재전달한다. 새로운 random event를
발행해 중복처럼 보이게 만들지 않는다. 모든 operation은 stable identity와 0-based
`attemptOrdinal`을 가진다. accepted-success identity의 여러 attempt 중 정확히 하나만 terminal
authority/effect를 얻고 duplicate attempt는 winner가 0인 one-terminal-winner 규칙을 따른다.
late attempt는 성공으로 다시 세지 않고 `IGNORED_FENCED` 같은 disposition으로 기록한다.

### 9.5 Failure와 cleanup lifecycle

topology owner의 `close()`는 idempotent해야 한다. acquired-resource ledger는 resource의 owner와
close action을 `start()` 호출 전에 `ALLOCATED`로 등록하고 `STARTING`, `STARTED`, `CLOSED`,
`CLOSE_FAILED` 상태를 기록한다. Docker object를 만든 뒤 readiness 전에 실패하는 경우까지 포함해
partial startup, workload failure, restore failure에서도 등록된 resource를 reverse order로
정리한다.

timeout/error 시 cancellation은 다음 상태 기계로 수행한다.

1. scheduler와 dispatcher의 신규 제출을 중단한다.
2. 모든 barrier/provider latch를 `finally`에서 fail-open release한다.
3. workload/fault-observer/provider future를 cancel한다.
4. outbound Lettuce/Hikari client를 먼저 close하여 interrupt를 무시하는 blocking I/O를 깨운다.
5. fixture가 직접 소유한 external executor를 `shutdownNow()`하고 cleanup reserve 안에서 bounded
   rejoin한다.
6. context-owned executor는 Spring/Ktor context close가 종료한다. potentially blocking close
   action은 각 action마다 새 daemon cleanup thread에서 실행하고 deadline을 넘기면 leak을 기록한
   뒤 다음 close action을 계속한다.
7. live thread/future 또는 timed-out cleanup thread가 남으면 terminal `ERROR`,
   `errorCode=CLEANUP_TIMEOUT`으로 기록하고 coordinator는 즉시 중단한다. 다음 topology를
   시작하지 않는다.

executor와 future, application context, Lettuce/Hikari, proxy/toxic, Toxiproxy,
Redis/PostgreSQL, Network마다 `cleanupActionBudgets`의 bounded close action을 순서대로 적용하고
전체 합과 report finalize가 `cleanupReserve`를 넘지 않게 preflight한다. interrupt status를
보존하고 original failure를 primary로 유지하며 cleanup failure를 suppressed error로 합친다.

개별 cleanup failure는 나머지 cleanup을 막지 않는다. cleanup 실패는 immutable terminal report와
run manifest에 기록하고 task를 실패시킨다. CI에서 Ryuk가 비활성화되어도 후속 profile은 이전
topology의 live container, network, executor/thread가 0임을 확인해야 시작할 수 있다.

coordinator/parent process는 child profile JVM과 별도로 collision-resistant run/profile label set,
child PID, report journal path를 먼저 생성해 child에 전달한다. child는 Docker create request
전에 logical resource key, resource type, exact full label set을 parent journal에 fsync한다. child는
Docker create request 자체에 그 labels를 넣고 returned container/network ID를 readiness wait 전에
후속 record로 fsync한다.

exact full label set은 parent-issued run ID, profile ID, allowlisted `resourceKey`, `resourceType`을
모두 포함한다. suite/parent는 같은 profile의 `(runId, profileId, resourceKey, resourceType)` tuple
uniqueness를 Docker create 전에 검증한다.

child가 cleanup reserve 안에 종료하지 않으면 parent가 child를 terminate한 뒤
`runCleanupReserve` 안에서 compensating cleanup한다. ID record가 있으면 journal ID와 실제 exact
labels가 모두 일치하는 대상만 정리한다. ID record가 없는 in-flight create는 parent-issued exact
full label set을 `dockerCleanupPollInterval`마다 반복 조회한다. 하나면 container-before-network
reverse dependency order로 정리하고 다시 조회한다. 0건은 즉시 성공으로 보지 않고
`dockerCleanupQuietPeriod` 동안 연속 stable-zero를 확인한다. 둘 이상이면 collision으로 보고
삭제하지 않은 채 fail closed한다. reserve 만료 전 stable-zero가 성립하지 않거나 ID/label
mismatch 또는 parent cleanup이 실패하면 `PARENT_CLEANUP_ERROR`로 run을 즉시 실패시키고 다음
profile을 금지한다.

### 9.6 Submission과 attempt conservation

fault observer는 workload와 별도 reserved executor/permit을 사용하여 backlog 포화 때문에 장애
관측 자체가 굶지 않게 한다. 모든 scheduled token은 다음 hard invariant를 만족한다.

- `scheduledCount == dispatchedCount + locallyRejectedCount`
- `expectedTokenCount == operationCount == scheduledCount`이고 expected stable ordinal마다 realized
  record가 exactly-one이어야 한다. missing, duplicate, unknown ordinal은
  terminal `ERROR`, `errorCode=INVALID_REALIZATION`이다.
- `completedCount`는 dispatched attempt 중 terminal response 또는 exception을 반환한 `SUCCEEDED`,
  `FAILED_CLOSED`, `DUPLICATE_SUPPRESSED`, `IGNORED_FENCED`, `EXECUTION_FAILED` count의 합이다.
- `dispatchedCount == completedCount + cancelledCount + timedOutCount`이며
  `terminalDispositionCounts`의 non-`LOCALLY_REJECTED` 합과도 같아야 한다.
- `terminalDispositionCounts.LOCALLY_REJECTED == locallyRejectedCount`여야 한다.
- retry profile은 `scheduledAttemptCount == sum(identity.attemptCount)`를 만족한다.
- authority expectation은 completed request만이 아니라 seed로 만든 전체 scheduled identity/token
  manifest에서 파생한다.
- 모든 attempt는 정확히 하나의 terminal disposition을 갖는다.
- expected terminal class별 winner cardinality는 다르다. accepted-success identity는 authority/
  effect/receipt winner가 각각 exactly-one이고, fail-closed/local-rejected/fenced/duplicate identity는
  effect/receipt winner가 0이다. `EXECUTION_FAILED`는 caller가 terminal exception을 관측했다는
  뜻이며 `failurePoint=BEFORE_AUTHORITY|AFTER_AUTHORITY|UNKNOWN`을 기록한다. convergence query에
  따라 authority/effect/receipt winner는 각각 0 또는 1일 수 있지만 공통 at-most-one이다. run은
  terminal `ERROR`, `errorCode=EXECUTION_ERROR`다.

### 9.7 Durable run journal

manifest/journal은 one-line canonical JSON record 뒤 newline을 붙이는 JSONL이다. 각 record는
monotonic sequence, previous-record SHA-256, payload SHA-256을 포함하며 append 뒤 file channel을
`force(true)`한다. reader는 newline이 없는 마지막 torn record만 무시하고 그 이전 record의
sequence/hash chain 손상은 fail closed 처리한다. final close record도 fsync한 뒤 immutable report와
manifest 일치를 검증한다.

## 10. Report contract

framework별 report는 다음 구조를 갖는다.

```text
reportSchemaVersion
suiteSchemaVersion
profileSchemaVersion
runId
profileId
mode
implementation
startedAt
endedAt
environment
  sourceCommit
  sourceDirty
  sourceReproducible
  sanitizedDiffSha256
  suiteSha256
  profileSha256
  scheduleVectorsSha256
  reportContractSha256
  implementationArtifactVersion
  workflowRunAndAttempt
  runnerImage
  jdk
  os
  cpuModel
  availableProcessors
  effectiveCpuQuota
  effectiveCpuset
  memoryLimitBytes
  jvmVendor
  jvmVersion
  jvmHeapSummary
  jvmGc
  relevantJvmFlags
  containerRuntime
  containerRuntimeVersion
  cgroupMode
  diskAvailableBytes
  containerImagesAndDigests
  perContainerResourceLimits
  containerDiagnostics[]
    resourceKey
    inspectState
    healthStatus
    exitCode
    oomKilled
    restartCount
    waitStrategyResult
    startupDurationNanos
    cleanupAttempts
    cleanupOutcome
    logTailEvidenceReference
  imagePullAndStartupDurationsNanos
  databaseConfigurationSummary
  redisConfigurationSummary
  networkTopology
  hikariLimits
  workerLimits
phaseDurationsNanos
workload
  seed
  arrivalCurve
  contentionShape
  expectedScheduleSha256
  realizedTokenManifestSha256
  effectiveConfiguration
    operationCount
    concurrency
    dispatcherBacklogCapacity
    maxScheduleDelay
    warmupOperationCount
    workloadDuration
    epochs
    retryShape
    contentionShape
    expectedSubmissionOutcomes
    operationTimeout
    injectionDeadline
    failureDetectionDeadline
    workloadJoinDeadline
    recoveryDeadline
    cleanupActionBudgets
    reportFinalizeReserve
    cleanupReserve
    profileDeadline
    runCleanupActionBudgets
    runJournalFinalizeReserve
    runCleanupReserve
    dockerCleanupPollInterval
    dockerCleanupQuietPeriod
    runDeadline
  operationCount
  expectedTokenCount
  concurrency
  scheduledDurationNanos
  actualDurationNanos
  maxScheduleDeviationNanos
  scheduledCount
  dispatchedCount
  locallyRejectedCount
  missedDeadlineCount
  completedCount
  cancelledCount
  timedOutCount
  scheduledAttemptCount
  terminalDispositionCounts
  submissionConservation
  attemptConservation
  executionFailures[]
    identityOrdinal
    attemptOrdinal
    failurePoint
    authorityWinnerCount
    effectWinnerCount
    receiptWinnerCount
failureInjection
  type
  configuredTrigger
  effectiveTrigger
  steps[]
    targetConnectionClass
    controlAction
    ownedToxicIds
    armedAt
    triggeredAt
    confirmedAt
    restoredAt
    recoveredAt
    expectedFailureClasses
    observedFailureClasses
    affectedOperationCount
    recoveryObservation
invariantResults[]
  invariantId
  authority
  expectation
  observation
  status
  evidenceReference
observations
  realizedAuthorityKeyCardinality
  realizedCollisionCount
  latencyBuckets[]
    outcome
    attemptKind
    sampleCount
    scheduledResponseLatencyNanos
      p50
        status
        value
      p95
        status
        value
      p99
        status
        value
    dispatchServiceLatencyNanos
      p50
        status
        value
      p95
        status
        value
      p99
        status
        value
  observedThroughputPerSecond
  throughputNumerator
  throughputElapsedNanos
  percentileAlgorithm
  saturation
    hikariActiveMax
    hikariPendingMax
    executorQueueDepthMax
    bulkheadPermitsUsedMax
    bulkheadRejectionCount
    workerBusyMax
    configuredCapacities
    samplingIntervalNanos
    sampleCount
    hikariTimeAtCapacityNanos
    executorTimeAtCapacityNanos
    bulkheadTimeAtCapacityNanos
    workerTimeAtCapacityNanos
    hikariWait
      sampleCount
      sumNanos
      maxNanos
      p50
        status
        valueNanos
      p95
        status
        valueNanos
      p99
        status
        valueNanos
    executorQueueWait
      sampleCount
      sumNanos
      maxNanos
      p50
        status
        valueNanos
      p95
        status
        valueNanos
      p99
        status
        valueNanos
    bulkheadWait
      sampleCount
      sumNanos
      maxNanos
      p50
        status
        valueNanos
      p95
        status
        valueNanos
      p99
        status
        valueNanos
    cumulativeWaitNanos
deadlines[]
  phase
  configuredBudgetNanos
  effectiveBudgetNanos
  absoluteDeadlineNanos
  actualDurationNanos
  expired
  timeoutOrigin
observationScope
crossImplementationComparable
productionCapacityClaim
result
  terminalStatus
  correctness
  errorCode
  failedPhase
  failedInvariantIds
  redactedErrorClass
cleanup
  status
  resourceOutcomes
knownLimitations
```

### 10.1 판정 규칙

- run 시작 시 manifest가 expected implementation/profile 조합을 열거한다. 누락 report는 coordinator
  failure이며 성공 summary를 만들 수 없다.
- assertion, injection, recovery, timeout 중 예외가 발생해도 outer `finally` cleanup 이후
  `FAIL` 또는 `ERROR` terminal report를 생성한다. Docker server/version, container runtime,
  cgroup, CPU/memory/disk quota, image pull/startup budget preflight가 부족하면 `UNAVAILABLE`로
  분류하여 correctness regression과 구분한다.
- terminal report는 `failedPhase`, failed invariant ID, redacted error class, phase duration,
  cleanup outcome을 포함한다. cleanup 실패는 original failure에 합쳐 task를 실패시킨다.
- `result.correctness`는 `invariantResults`에서 기계적으로 파생한다. invariant 하나라도
  `FAIL`이면 aggregate도 `FAIL`이며 test도 실패한다.
- 각 invariant result는 stable ID, 조회한 authority, expected/observed value, PASS/FAIL,
  report 내부 또는 별도 artifact의 evidence reference를 가져야 한다.
- environment 필수 필드가 없으면 report validation이 실패한다.
- 성공 resource는 bounded inspect/wait/cleanup summary만 기록한다. startup, readiness, stop/remove가
  실패한 resource는 state/health/exit/OOM/restart, wait-strategy 결과, parent/child cleanup
  attempts/outcomes와 redacted log tail evidence를 필수로 남긴다. log tail은 resource당 최대
  200 lines/64 KiB로 cap하고 10.3 sentinel scan을 통과해야 한다.
- environment configuration은 allowlist 기반 secret-free summary다. JDBC/Redis URI, user-info,
  password, token, environment variable value, query parameter, private endpoint, mapped/control port를
  기록하지 않는다.
- `sourceDirty=true`이면 raw diff를 artifact에 넣지 않고 canonical sanitized diff의 SHA-256만
  기록하며 `sourceReproducible=false`다. `local-reference`와 CI workflow는 clean source를
  요구한다. effective CPU quota/cpuset, JVM vendor/version/heap/GC/relevant flags와 container별
  CPU/memory limit이 없으면 performance observation validation이 실패한다.
- latency와 throughput은 `observed` vocabulary로만 노출한다.
- scheduled-response latency는 scheduled arrival부터 terminal outcome까지, dispatch-service
  latency는 executor dispatch부터 terminal outcome까지 측정한다. warm-up은 제외하고 retry
  attempt, terminal success/failure, local rejection을 별도 bucket으로 기록한다.
- 각 percentile의 `status`는 `MEASURED`, `INSUFFICIENT_SAMPLES`, `NOT_APPLICABLE`의 closed
  enum이다. `MEASURED`만 `value`를 요구하고 나머지는 `value`가 absent여야 한다.
  `LOCALLY_REJECTED` bucket의 dispatch-service latency는 dispatch 시점이 없으므로 세 percentile이
  모두 `NOT_APPLICABLE`이다.
- percentile은 정렬된 bucket sample의 nearest-rank `ceil(p * n) - 1`을 사용한다. 각 bucket은
  `sampleCount`를 기록하며 p50은 2개, p95는 20개, p99는 100개 미만이면 값을 생성하지 않고
  해당 percentile만 `INSUFFICIENT_SAMPLES`로 표시한다.
- `maxScheduleDeviationNanos`는 dispatched token에 대해
  `max(0, actualDispatchNanos - (startBarrierNanos + scheduledOffsetNanos))`의 최대값이다.
  local rejection은 제외하며 monotonic clock 역행으로 음수가 되면 0으로 clamp한다.
- throughput 분모는 start barrier release부터
  `max(last scheduled offset, 모든 scheduled token의 마지막 terminal disposition 시각)`까지의
  monotonic elapsed다. 분자는 application에 dispatch되어 terminal outcome을 반환한 operation
  수다. local rejection, warm-up, recovery, report serialization은 제외한다.
- Hikari pending/active, executor queue, bulkhead permit/rejection, worker saturation을 고정 cadence로
  표본화한다. 각 capacity와 sampling interval/count, time-at-capacity를 기록한다. resource wait와
  queue wait는 raw sample을 report에 노출하지 않고 count/sum/max와 latency bucket과 같은
  nearest-rank p50/p95/p99 및 small-sample 규칙으로 요약한다. cumulative wait를 maxima와 함께
  기록해 application authority contention과 client/pool throttling을 구분한다.
- saturation은 start barrier 시각의 initial sample을 필수로 하고 sample i의 상태를 left-closed
  `[sampleTime_i, min(sampleTime_(i+1), lastTerminalTime)]` 구간에 적용한다. 마지막 sample도
  `lastTerminalTime`에 clip하며 initial/final boundary가 없으면 observation validation이 실패한다.
- deadline evidence는 configured budget, non-cleanup execution deadline 또는 reserved cleanup
  deadline으로 clip된 effective budget, monotonic absolute deadline, actual duration, expiry와
  timeout origin을 phase별로 기록한다.
- submission/attempt conservation이나 `expectedSubmissionOutcomes` realization이 맞지 않으면
  aggregate correctness와 무관하게 terminal `ERROR`, `errorCode=INVALID_REALIZATION`이다.
- expected ordered token은 golden-vector와 같은 canonical field order/number encoding으로
  serialize하여 `expectedScheduleSha256`을 계산한다. realized manifest는 각 expected token에
  dispatch/local-rejection, attempt ordinal과 terminal disposition을 결합한 canonical serialization의
  `realizedTokenManifestSha256`을 기록한다. validator는 journal/evidence에서 expected ordinal
  전집과 realized exactly-one record를 재구성하고 두 digest를 독립 재계산한다.
- 각 attempt의 exactly-one terminal disposition을 집계한다. identity별 authority/effect/receipt
  cardinality는 9.6의 expected terminal class 규칙에 따라 accepted-success는 exactly-one,
  non-winning class는 zero, 모든 class는 공통 at-most-one으로 검증한다.
- 모든 report는 `observationScope=developer-or-ci-reference`,
  `crossImplementationComparable=false`, `productionCapacityClaim=false`를 포함한다.
- coordinator summary는 correctness와 artifact path만 집계한다.
- framework별 throughput 차이, winner, faster/slower 판단은 생성하지 않는다.

### 10.2 Closed result vocabulary

| 필드 | 허용값과 조합 |
|---|---|
| `terminalStatus` | `PASS`, `FAIL`, `ERROR`, `UNAVAILABLE` |
| `correctness` | `PASS`, `FAIL`, `NOT_EVALUATED` |
| `errorCode` | `NONE`, `INVALID_PROFILE`, `INVALID_REALIZATION`, `KEY_SCOPE_VIOLATION`, `EXECUTION_ERROR`, `INJECTION_TIMEOUT`, `FAILURE_DETECTION_TIMEOUT`, `WORKLOAD_TIMEOUT`, `RECOVERY_TIMEOUT`, `CLEANUP_TIMEOUT`, `REPORT_SERIALIZATION`, `JOURNAL_ERROR`, `PARENT_CLEANUP_ERROR`, `PREFLIGHT_UNAVAILABLE` |
| `timeoutOrigin` | `NONE`, `OPERATION`, `INJECTION`, `FAILURE_DETECTION`, `WORKLOAD_JOIN`, `RECOVERY`, `CLEANUP`, `PROFILE_EXECUTION`, `RUN_EXECUTION`, `RUN_CLEANUP` |
| attempt terminal disposition | `SUCCEEDED`, `FAILED_CLOSED`, `EXECUTION_FAILED`, `LOCALLY_REJECTED`, `CANCELLED`, `TIMED_OUT`, `DUPLICATE_SUPPRESSED`, `IGNORED_FENCED` |

`PASS`는 `correctness=PASS,errorCode=NONE`, `FAIL`은
`correctness=FAIL,errorCode=NONE` 조합만 허용한다. `ERROR`는 non-`NONE` error code가 필수이고
invariant 평가가 완료됐으면 파생된 `PASS`/`FAIL` correctness를 보존하며, 완료되지 않았으면
`NOT_EVALUATED`를 사용한다. `UNAVAILABLE`은
`correctness=NOT_EVALUATED,errorCode=PREFLIGHT_UNAVAILABLE` 조합만 허용한다.
`MISSED_DEADLINE`은 terminal disposition이 아니라 scheduled token의 orthogonal observation flag다.
`EXECUTION_FAILED`가 하나라도 있으면 terminal status는 `ERROR`,
`errorCode=EXECUTION_ERROR`다. 해당 identity의 `failurePoint`와 convergence query로 확인한
authority/effect/receipt cardinality 0 또는 1을 evidence에 기록한다.
unknown enum이나 유효하지 않은 조합은 reader와 coordinator가 fail closed 처리한다. enum 의미를
바꾸거나 required value를 제거할 때는 새 report schema version을 만든다.

### 10.3 Redaction

report, test display name, exception, log에는 raw tenant ID, user ID, IP, idempotency key, payment
detail, full digest, credential, URI user-info/query, token, datasource password, Redis password,
Toxiproxy control endpoint를 기록하지 않는다. profile과 invariant는 low-cardinality stable ID를
사용한다. 필요한 연관성은 run-local ordinal 또는 bounded surrogate로 표현한다.

run ID, profile ID, implementation ID, invariant ID는 typed value로 파싱한다. 외부 입력 run ID는
`[a-z0-9][a-z0-9._-]{0,63}`만 허용하고 path separator, dot segment, control character를
거부한다. resolved output path를 normalize한 뒤 report root 아래인지 다시 확인하고 symlink
escape를 거부한다.

각 profile은 raw identity, credential, URI query, token sentinel을 seed/config에 포함한 negative
fixture를 실행한다. run manifest/journal, child journal, report, summary, referenced evidence, path,
test display name, exception message, captured log 전체를 scan하여 sentinel이나 full digest가 한
곳이라도 나타나면 실패한다.

`evidenceReference`는 sanitized artifact root 안의 allowlisted relative identifier만 허용한다.
absolute path, URI, `..`, separator escape, symlink는 거부한다. workflow는 manifest에 포함되고
redaction 검증을 통과한 canonical run artifact tree만 업로드한다.

## 11. Ecosystem capability selection

| 책임 | 재사용 Bluetape module/capability | 사용하지 않는 경우와 제약 |
|---|---|---|
| caller validation | `bluetape4k-core` `require*` | 반환값을 보존하고 caller input에 `check`를 사용하지 않음 |
| JSON | `bluetape4k-jackson3` | 신규 serializer·JSON dependency 없음 |
| test lifecycle/assertion | `bluetape4k-junit5`, `bluetape4k-assertions` | raw JUnit assertion은 capability가 없을 때만 사용 |
| PostgreSQL | `PostgreSQLServer` | H2/mock은 authority proof가 아님 |
| Redis | `RedisServer`, `bluetape4k-lettuce` | Redis는 final authority가 아님 |
| persistence | 기존 Exposed JDBC/Spring Boot integration, HikariCP | repository/transaction boundary를 raw JDBC로 우회하지 않음 |
| virtual thread | `bluetape4k-virtualthread-api`, JDK 25 runtime | bounded workload에만 사용 |
| metrics | `bluetape4k-micrometer` | identity/high-cardinality tag 금지 |
| leader | Ticket의 기존 leader capability | Job worker lease를 global leader로 바꾸지 않음 |
| Redis path fault | `bluetape4k-testcontainers` `ToxiproxyServer`, `libs.testcontainers.toxiproxy` | wrapper를 재사용하고 compileOnly runtime module만 test scope에 추가 |
| domain control | 기존 fake clock/provider/barrier | 실제 provider와 wall-clock domain transition 금지 |
| Gatling | 사용하지 않음 | failure barrier와 lifecycle recovery coordination에 부적합 |
| generic test-kit | 사용하지 않음 | 두 언어 이상에서 계약 재사용이 증명되기 전 승격 보류 |

## 12. Kotlin·Spring·Exposed 원칙

- caller validation은 Bluetape `require*`의 반환값을 사용한다.
- internal lifecycle invariant만 `check`/`checkNotNull`로 검증한다.
- operational component는 `KLogging` pattern으로 path cut, recovery, restart, terminal failure를
  low-cardinality context와 함께 기록한다.
- virtual-thread workload는 monitor 대신 explicit concurrency primitive와 barrier를 사용한다.
- resource ownership을 fixture에 명시하고 startup 중간 실패와 `close()`를 모두 검증한다.
- Spring test는 application bean과 Hikari-backed datasource를 사용한다.
- Exposed repository와 transaction boundary를 profile 전용 raw JDBC query로 우회하지 않는다.
- raw JDBC는 기존 migration/schema verification 경계 밖으로 확장하지 않는다.

## 13. Gradle과 workflow

일반 `test` task는 profile을 제외하고 contract parser, report validation, deterministic helper unit
test만 실행한다.

root entry point는 `highContentionCi`와 `highContentionLocalReference`다. 두 task 모두
`-PhighContentionRunId=<allowlisted-unique-id>`를 필수로 받는다. mode는 task 이름으로 고정되며
별도 mode property는 받지 않는다. `-PhighContentionProfileId`와
`-PhighContentionImplementation`을 모두 생략하면 suite 전체를 실행하고, 제공하면 manifest의
exact allowlist로 filter한다. 빈 값, unknown ID, 선택 결과 0건, manifest에 없는
profile/implementation 조합은 topology 시작 전에 실패한다.

개발 중 단일 조합은 다음 module-local task에 run ID와 profile ID를 전달한다. implementation은
module path로 고정하며 workflow는 이 task를 직접 호출하지 않는다.

- `:operations-job-console-core:highContentionCiProfile`
- `:operations-job-console-spring:highContentionCiProfile`
- `:operations-job-console-ktor:highContentionCiProfile`
- `:commerce-concert-ticket-flash-sale:highContentionCiProfile`
- 같은 module path의 `highContentionLocalReferenceProfile`

전체/단일 실행의 canonical 예시는 다음과 같다.

```bash
./gradlew highContentionCi -PhighContentionRunId=ci-123-1 --max-workers=1
./gradlew highContentionLocalReference -PhighContentionRunId=local-20260724-1 --max-workers=1
./gradlew highContentionCi -PhighContentionRunId=redis-path-1 \
  -PhighContentionProfileId=redis-path-outage \
  -PhighContentionImplementation=job-spring --max-workers=1
```

root coordinator와 direct module task는 모두 repository의 `profiles/high-contention/v1`을 같은
Gradle declared input directory로 등록하고 task action이 resolved absolute path를 internal system
property로 runner에 주입한다. caller가 contract root를 override하는 property는 제공하지 않는다.
module runner는 working directory나 classpath를 추측하지 않고 전달받은 contract root의 존재,
version, repository-root containment를 검증한다. suite loader는 `profileFile`을 root-relative
allowlisted path로만 resolve하고 모든 path component를 `NOFOLLOW_LINKS`로 확인한다. regular
file과 real-path descendant를 검증한 handle에서 bounded content를 읽고 digest를 계산하며, open
전후 file identity가 바뀌면 fail closed 처리한다.

모든 profile task는 `[a-z0-9][a-z0-9._-]{0,63}`을 만족하는 explicit unique run ID를 요구하고
다음 canonical artifact tree만 쓴다.

```text
build/reports/high-contention/<run-id>/
  run-manifest.json
  run-journal.jsonl
  children/<implementation>/<profile-id>/child-journal.jsonl
  reports/<implementation>/<profile-id>.json
  evidence/<implementation>/<profile-id>/<allowlisted-evidence-id>.json
  summary.json
  upload-manifest.json
```

`run-manifest.json`은 시작 전 expected matrix와 digest를 no-replace로 고정하고 `summary.json`은
모든 child cleanup/validation 뒤 한 번만 확정한다. 기존 run directory를 덮어쓰지 않는다.
resolved output path를 normalize한 뒤 report root 아래인지 다시 확인하여 path traversal과
symlink escape를 거부한다. trusted real report root의 모든 parent component를 `NOFOLLOW_LINKS`로
확인하고 임시 target은 `CREATE_NEW`와 no-follow semantics로 연다. fsync 뒤 replace 없는 atomic
move를 사용하고 생성 뒤에도 real root containment를 다시 확인한다.

| 결과 | direct child task | root coordinator |
|---|---|---|
| 모든 expected report `PASS`, artifact valid, cleanup zero-live | exit 0 | exit 0 |
| `FAIL` | report 보존 후 nonzero | 안전한 cleanup을 확인하면 남은 조합을 수집하고 최종 nonzero |
| `ERROR` | report/journal 보존 후 nonzero | zero-live와 budget이 보장될 때만 계속하고 최종 nonzero |
| `UNAVAILABLE` | report 보존 후 nonzero | artifact를 보존하고 최종 nonzero |
| missing/invalid artifact, cleanup leak, parent cleanup error | nonzero | 즉시 중단, nonzero |

성공·실패 모두 coordinator는 redaction validation 결과, upload allowlist, 각 file digest를 담은
`upload-manifest.json`을 no-replace로 쓴다. validator 자체가 완료되지 않거나 redaction이 실패하면
raw run tree는 업로드하지 않고 constants-only `upload-failure-summary.json`만 별도 임시 upload
directory에 생성하며 job은 nonzero를 유지한다.

single coordinator task만 workflow entry point로 사용한다. coordinator 내부의 profile loop는
명시적으로 순차 실행하고 각 Test task에 `maxParallelForks=1`과 JUnit parallel disabled를
강제한다. manifest의 start/end event로 `maxActiveTopologies == 1`을 검증한다.

`Examples.yml`의 container-backed lane은 coordinator correctness mode만 실행하고 CI run ID를
`examples-${{ github.run_id }}-${{ github.run_attempt }}`로 만든다. `nightly.yml`은
local-reference mode와 `nightly-${{ github.run_id }}-${{ github.run_attempt }}`를 사용한다. report의
`workflowRunAndAttempt`,
run manifest ID와 이 값이 다르면 validation failure다.

workflow의 `if: always()` validation step은 `upload-manifest.json`과 redaction status를 검사한 뒤
allowlisted canonical tree만 `high-contention-<mode>-<run-id>` 이름으로 업로드한다.
correctness artifact retention은 7일, local-reference는 14일이며 `if-no-files-found: error`를
적용한다. validator/redaction failure에는 constants-only failure summary만 업로드한다.
`UNAVAILABLE`/`ERROR` report도 검증을 통과하면 artifact로 보존하지만 correctness PASS로 계산하지
않는다.
일반 smoke lane에는 Testcontainers profile을 넣지 않는다.

workflow 변경은 작은 anchored edit로 수행하고 `actionlint`, Gradle task 존재, expected artifact
존재를 검증한다. profile report는 production code coverage 산출물로 취급하지 않는다.

## 14. 문서

Job Console과 Ticket의 `README.md`, `README.ko.md`는 다음 내용을 동일하게 제공한다.

- `ci-correctness`와 `local-reference` 실행 명령
- 필요한 Docker/JDK/memory 조건
- Toxiproxy가 검증하는 실제 network path와 검증하지 않는 production failover
- correctness와 performance observation의 차이
- report 위치와 필드 해석
- framework ranking과 production capacity 비주장
- 장애 발생 시 PostgreSQL/Redis/provider/worker 중 어느 상태가 권위인지

별도 chart나 benchmark ranking diagram은 만들지 않는다. architecture와 failure flow는 기존
README diagram과 본 spec의 text/ASCII로 충분하며, 수치 chart는 비목표와 충돌한다.

## 15. 주요 실패 모드와 완화

| 실패 모드 | 위험 | 완화 |
|---|---|---|
| 장애가 application stub 또는 잘못된 toxic에서만 발생 | 실제 Redis socket/reconnect 경로가 검증되지 않음 | proxy URI guard, old connection reset/timeout, new connection proxy disable, data-plane recovery |
| fake clock으로 latency 측정 | 의미 없는 0 또는 조작된 percentile | domain clock과 monotonic measurement 분리 |
| worker restart가 새 DB를 사용 | durable recovery 증거가 사라짐 | 같은 PostgreSQL authority에 context/worker만 재연결 |
| duplicate test가 새 event ID 사용 | 실제 dedup 경로를 우회 | stable event ID와 payload digest 재전달 |
| Redis key loss가 DB guard까지 삭제 | authority 경계가 훼손됨 | run namespace Redis key만 제거하고 DB invariant query |
| shared runner가 domain SPI로 성장 | 예제 구현이 generic framework에 종속 | 공통화는 data/report vocabulary로 제한 |
| profile 병렬 실행 | Docker/port/pool 경합과 flaky 결과 | single coordinator, `maxParallelForks=1`, JUnit parallel off, active-topology assertion |
| 이전 report 덮어쓰기·path escape | 실행 증거 손실 또는 workspace 밖 write | run ID allowlist, normalized root containment, empty output check |
| performance 수치를 gate로 오해 | hosted runner 변동으로 false regression | correctness hard gate, performance report-only |
| report에 식별자 노출 | PII/high-cardinality leak | surrogate identity와 redaction contract test |
| 실패 전에 restore | fault가 workload에 걸리지 않은 vacuous pass | designated operation fault confirmation과 affected count gate |
| partial startup/cleanup 실패 | Ryuk-disabled CI의 다음 profile 오염 | acquired-resource ledger, bounded idempotent close, zero-live-resource gate |

## 16. 호환성과 migration

- production HTTP/API/schema migration은 의도하지 않는다.
- 기존 Job Console과 Ticket domain behavior는 변경하지 않는다.
- suite, profile, report contract는 각각 `suiteSchemaVersion`, `profileSchemaVersion`,
  `reportSchemaVersion`을 사용하며 지원 version set을 별도로 검증한다.
- v1 reader는 unknown top-level field를 거부한다. optional observation을 추가할 때는 reader를 먼저
  배포하고 writer를 갱신한다. required field 추가·삭제 또는 기존 필드 의미 변경은 새 version
  directory와 version number를 만든다.
- suite entry/implementation ordering, selection key 또는 expected matrix 의미 변경도 required
  semantic change로 취급하여 새 version directory와 `suiteSchemaVersion`을 만든다.
- loader는 polymorphic typing이 없는 typed DTO로만 parse한다. duplicate JSON key, closed enum 밖의
  mode/curve/failure type, negative/overflow numeric value, mode별 operation/concurrency/duration 상한,
  최대 document size/depth 초과를 fail closed 처리한다.
- profile runner는 application의 public HTTP와 기존 test fixture를 사용하며 production-only
  test hook을 추가하지 않는다.
- Redis path fixture는 이미 의존 중인 `bluetape4k-testcontainers`의 `RedisServer`와
  `ToxiproxyServer`를 사용한다. wrapper의 compileOnly dependency를 실행 가능하게 하려고 profile
  runner module의 `testImplementation(libs.testcontainers.toxiproxy)`을 추가하되 raw wrapper나
  explicit version pin은 추가하지 않는다.

## 17. Acceptance criteria 추적

| Issue 요구 | 설계 위치 | 검증 |
|---|---|---|
| reproducible curve/duration/topology/control | 6, 8, 10 | contract parser와 report validation |
| queue/admission ordering | 7 | Job/Ticket burst profile |
| inventory invariant | 7 | Ticket PostgreSQL invariant query |
| idempotency | 7, 9.4 | duplicate-storm profile |
| reconciliation | 7, 9.4 | slow-provider profile |
| lease recovery | 7, 9.3 | worker-restart profile |
| outbox replay | 7, 9.4 | duplicate-delivery profile |
| Redis unavailable/key loss | 9.1, 9.2 | Toxiproxy와 targeted key removal |
| framework 독립 결과 | 10, 13 | separate report path와 artifacts |
| correctness/performance 구분 | 6.1, 10.1 | hard gate와 observed fields |
| setup/workload/invariant/evidence/limitation | 10 | report contract validation |
| sequential heavyweight topology | 8, 13 | coordinator/workflow order proof |
| Java 25 | 11, 13 | toolchain/task validation |
| Bluetape capability 우선 | 11, 12 | import/dependency/reuse audit |

## 18. 검증 전략

1. suite/profile/report contract parse success, ordered selection matrix, path/content identity,
   closed result enum 조합, unknown field, independent version mismatch test
2. `schedule-vectors.json`을 사용한 SHA-256 rank, overflow-safe offset, epoch boundary,
   authority weight, exact ordered token과 curve별 duration 재현성 test
3. fake clock, monotonic measurement 분리 test
4. structured invariant aggregation, terminal FAIL/ERROR/UNAVAILABLE report, missing
   topology/environment, report overwrite, profile-file/output path containment, symlink/TOCTOU,
   open 전후 file identity, redaction test
5. Job Console PostgreSQL/Redis correctness profile
6. Spring Job Console live HTTP profile
7. Ktor Job Console live HTTP profile
8. Ticket PostgreSQL/Redis correctness profile
9. proxy URI/direct-bypass guard, existing/new Redis connection fault observation, data-plane recovery test
10. execution/cleanup reserve와 configured/effective/absolute deadline composition,
    injection/detection/join/recovery/cleanup timeout origin, blocking I/O client-close, external/
    context-owned executor cancellation/bounded join test
11. resource를 ledger에 등록한 직후, Docker create 뒤 readiness 전을 포함한 partial-start 각 cut
    point, restore failure, workload failure, double-close, cleanup order와 no-live-container/thread test
12. warm-up baseline delta, delimiter-safe key owner parser, delete upper bound, shared-prefix neighbour,
    key-loss exact manifest/foreign sentinel test
13. transaction 밖 old worker pause 중 open transaction/held lock 0, takeover, old token release,
    authority-level stale token CAS 거절, worker/application context bounded restart test
14. slow provider barrier fail-open, reconciliation takeover, late original response fencing,
    provider future cancel/join test
15. duplicate delivery와 identity/attempt ordinal conservation, one-terminal-winner,
    effect/receipt count test
16. scheduler/dispatcher conservation, expected/realized schedule digest, fault-observer reserved executor,
    expected ordinal missing/duplicate/unknown, invalid workload realization test
17. percentile별 partial sample status, schedule deviation, saturation left-closed sampling cadence/count,
    time-at-capacity, wait distribution/cumulative wait report test
18. cleanup 뒤 immutable terminal report, JSONL sequence/hash/torn-tail recovery,
    serialization-failure fallback journal, no-replace atomic move test
19. root/direct Gradle declared contract input, internal task system property, override 거부, run ID와
    output path guard test
20. coordinator expected-manifest, missing report, `maxActiveTopologies == 1`, child timeout/kill,
    create intent fsync 뒤와 create-return/ID-fsync 사이 crash, exact Docker ID/label compensating
    cleanup, initial-zero 뒤 delayed create, stable-zero quiet window, label collision no-delete,
    parent cleanup failure stop, bounded inspect/wait/log/cleanup evidence test
21. upload manifest/redaction handshake, CI run ID/artifact name/retention, constants-only failure
    artifact, `actionlint`, stale/module validation, `git diff --check`
22. affected module tests와 container-backed profile의 순차 실행

## 19. 완료 정의

- versioned profile과 report contract가 repository에 존재한다.
- Job Spring, Job Ktor, Ticket Spring이 공통 profile 의미를 사용한다.
- 실제 PostgreSQL, Redis, Toxiproxy 경로에서 correctness profile이 통과한다.
- Redis path outage와 key loss가 서로 다른 fixture로 검증된다.
- slow payment, worker restart, duplicate delivery가 결정적으로 수렴한다.
- framework별 report가 setup, workload, invariant, evidence, limitation을 포함한다.
- correctness와 throughput/latency observation이 명확히 분리된다.
- Testcontainers profile이 workflow에서 순차 실행된다.
- English/Korean README가 source와 동일한 실행·해석 경계를 설명한다.
- Bluetape capability selection과 raw fallback rationale가 구현과 일치한다.
- targeted test, affected module test, actionlint, diagnostics, `git diff --check`가 통과한다.
- spec/plan/code review의 P0/P1이 0이다.
- lesson과 PR DoD가 exact head에 반영된다.
- CI와 live review가 green인 exact-head PR을 보고한 뒤 별도 merge 승인을 기다린다.
