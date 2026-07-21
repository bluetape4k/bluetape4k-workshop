# Issue #537 사전 생성 바우처 풀 구현 교훈

## 배경

사전 생성 바우처는 단순 CRUD 예제가 아니었다. code는 한 번만 공개되어야 하고, PostgreSQL이 capacity와 lifecycle의 유일한 권위여야 하며, Redis 장애와 process 재시작 뒤에도 command replay, worker checkpoint, revoke/reconciliation이 같은 결과로 수렴해야 했다. 이 때문에 module을 Spring Boot MVC + Exposed JDBC + PostgreSQL로 만들고 Redis는 admission과 leader hint에만 제한했다.

## 유지해야 할 설계 결정

1. **PostgreSQL authority**: reservation, allocation, code envelope, idempotency descriptor/tombstone, worker claim을 한 transaction 경계에서 다룬다.
2. **Memory-only reveal**: raw code는 response와 browser memory에만 존재하며 log, metric, audit, DOM attribute, storage에 남기지 않는다.
3. **Stable command identity**: idempotency key는 HTTP 요청 단위가 아니라 logical command intent 단위다.
4. **Advisory Redis**: Redis가 없거나 leader 결과가 불명확해도 PostgreSQL claim fencing이 durable work를 계속한다.
5. **Fail-closed startup**: schema migration checksum과 live row가 참조하는 KEK/digest version을 확인하기 전 readiness를 열지 않는다.
6. **Bounded resources**: Hikari 16 중 readiness 1개를 예약하고 foreground/worker/SSE를 11/1/3으로 제한한다.

## RED/GREEN에서 얻은 핵심 교훈

### 응답 수신과 command 완료는 같은 사건이 아니다

초기 browser 구현은 network exception에는 같은 key를 재사용했지만 두 경계를 놓쳤다.

- 서버가 effect를 commit한 뒤 `2xx` JSON이 잘리면 parse 실패는 여전히 ambiguous다.
- `COMMAND_IN_PROGRESS`, `POOL_BUSY`, `BACKEND_TIMEOUT` 같은 non-2xx는 terminal이 아니라 같은 command를 재시도하라는 응답이다.

여섯 command 모두 malformed 2xx와 retryable response에서 같은 key를 유지하는 테스트를 먼저 실패시켰다. 이후 성공 payload 또는 retry 정보가 없는 확인된 terminal response에서만 key를 폐기하도록 수정했다. 앞으로 idempotency UI를 검토할 때는 fetch 성공/실패가 아니라 **effect certainty**를 기준으로 key lifecycle을 판단해야 한다.

### lifecycle check는 작업 시작점과 등록 직전에 모두 필요하다

`reserveSubscriber()`에서 shutdown을 검사해도 initial PostgreSQL snapshot이 오래 걸리면 충분하지 않았다. 그 사이 `close()`가 poller registry를 비우고 끝난 뒤 request thread가 새 poller를 등록할 수 있었다.

blocking `initial()`과 concurrent `close()`를 강제로 교차시킨 RED 테스트가 이 race를 재현했다. poller 조회·생성과 동일한 registry lock 안에서 `closed`를 다시 확인한 뒤 GREEN이 됐다. shutdown correctness는 “처음에 닫혔는가”가 아니라 **공유 자원 등록 직전에도 닫히지 않았는가**를 증명해야 한다.

### configured timeout은 성능 증거가 아니다

stress evidence가 설정값을 그대로 기록하면 실제 queueing과 Hikari 획득 지연을 숨긴다. permit 획득과 `DataSource.getConnection()`의 실제 경과를 측정하고 sample 수 0도 실패시키도록 바꿨다. Production 기본 wait는 200ms로 유지하되, `Semaphore.tryAcquire` 반환 뒤 virtual thread가 다시 scheduling될 때까지 포함하는 관측치는 shared CI에서 250ms를 넘을 수 있었다. 따라서 실제 관측을 잘라내지 않고 scheduler-inclusive hard gate를 500ms로 두고, 실패 메시지에는 실제값과 상한을 함께 남긴다.

최종 64/128 client × Redis healthy/unavailable matrix는 permit max 15, violation 0을 기록했다. 설정된 획득 deadline과 scheduler-inclusive stress hard gate는 서로 다른 값이므로 runbook에도 기본 200ms와 관측 상한 500ms를 분리해 적었다.

Hikari의 순간 pending peak도 active 15/16, acquisition wait 1ms, drain 0ms, leak 0인 정상 run에서 3까지 관측됐다. Connection handoff 순간의 waiter 수는 구조적 상한 1이 아니므로 report-only evidence로 남기고, acquisition deadline과 pending drain deadline을 hard gate로 유지한다.

### Database timestamp 정밀도도 idempotency authority의 일부다

PostgreSQL/JDBC는 nanosecond `Instant`를 microsecond로 반올림해 저장한다. Exact create recovery가 요청값만 `truncatedTo(MICROS)`로 자르면 500ns 이상인 값은 저장 record와 1마이크로초 어긋나 정상 replay도 `CREATE_FINGERPRINT_CONFLICT`가 된다.

Campaign과 batch create를 다음 초로 반올림되는 `.999999789` 입력으로 고정하자 기존 구현이 2/2 RED가 됐다. 비교 양쪽을 PostgreSQL 저장 정밀도로 반올림하고 초 rollover를 정규화한 뒤 동일 테스트를 3회 반복 통과시켰다. Database가 authority인 exact replay에서는 business field뿐 아니라 **database가 실제 보존하는 표현**까지 fingerprint 비교 계약에 포함해야 한다.

### `-x test`는 custom `Test` task를 제외하지 않는다

CI의 compile-only lane은 `build -x test --parallel`을 사용했지만 새 `stressTest`와 `migrationCompatibilityTest`는 이름이 다른 `Test` task라 그대로 실행됐다. 그 결과 repo 전체 AOT/compile 부하와 stress가 경쟁해, 전용 sequential Container lane에서는 통과한 SSE permit deadline이 compile lane에서만 실패했다. `--dry-run`으로 task graph를 먼저 RED로 확인하고 CI/nightly compile lane에서 두 custom verification task를 명시적으로 제외했다. Resource-sensitive 검증은 이미 존재하는 전용 sequential lane에서 실행해야 하며, task type이 같다는 이유만으로 `-x test`에 포함된다고 가정하면 안 된다.

### 복구 경로는 wiring까지 검증해야 한다

migration runner, key preflight, worker trigger가 클래스와 단위 테스트로 존재하는 것만으로 production readiness가 되지 않는다. 실제 Spring bean과 기본 property가 켜져 있어야 한다.

- startup initializer는 V001 migration 뒤 live referenced key를 확인하고 readiness를 연다.
- dispatcher는 PostgreSQL에서 due/released/stale claim을 찾고 Redis가 없어도 실행한다.
- completed claim은 무한대 next-attempt로 자동 재실행에서 제외한다.
- 실제 integration test는 Redis 없이 partial checkpoint를 재개해 terminal state까지 수렴한다.

## 예상보다 어려웠던 부분

- manifest 내부 목록끼리의 일관성만 확인하면 key category 누락을 발견할 수 없다. 독립 inventory가 필요했다.
- SSE를 “닫는 함수가 호출됐다”는 mock 순서만으로는 late registration race를 증명할 수 없다.
- DB timeout 뒤 owner release는 한 번의 best effort로 끝내면 durable ownership을 남긴다.
- full module 재실행 중 Testcontainer가 사라지면 대량의 ApplicationContext failure가 발생한다. 최초 connection-refused와 container lifecycle을 확인한 뒤 clean rerun으로 코드 회귀와 환경 장애를 분리해야 한다.
- review P1을 한 번 수리했다고 리뷰가 끝난 것이 아니다. 최종 독립 검토가 malformed success, retryable response, close/open race를 추가로 찾아냈다.

## 최종 검증 명령

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --rerun-tasks --no-daemon --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:migrationCompatibilityTest --rerun-tasks --no-daemon --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:stressTest \
  -PvoucherPoolStressRun=task15-final-exact-head --rerun-tasks --no-daemon --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:detekt \
  :commerce-pre-generated-voucher-pool:detektTest --no-daemon --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:koverXmlReport --no-daemon --max-workers=1
EXPECTED_GRADLE_PROJECTS=108 ./scripts/smoke-validate.sh stale-check
node scripts/validate-voucher-pool-runbook.mjs
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
./gradlew build -x test -x stressTest -x migrationCompatibilityTest --parallel --continue --no-daemon
git diff --check
```

## 다음 변경을 위한 guard

- command UI는 success schema와 terminal/retryable semantics를 함께 검증하고 ambiguous outcome에서 key를 유지한다.
- timestamp를 exact replay authority에 포함할 때는 database 저장 정밀도와 반올림 경계를 deterministic fixture로 검증한다.
- 공용 schema의 TTL 경계 fixture는 `finally`에서 tenant 단위로 제거한다. Awaitility나 더 긴 시간 여유는 후속 전역 purge로의 fixture 누수를 해결하지 않는다.
- compile-only workflow에 custom `Test` task를 추가할 때는 `--dry-run` task graph와 전용 verification lane을 함께 확인한다.
- lifecycle에 blocking I/O가 추가되면 shutdown과 마지막 registry mutation 사이의 race test를 추가한다.
- stress evidence에는 실제 sample 수, 최대 wait, permit/Hikari drain과 leak 0을 포함한다.
- 새 worker trigger는 Redis 없이도 PostgreSQL claim에서 재개되는 integration test를 가져야 한다.
- restore inventory를 실제 backup payload digest 또는 signature에 결합하는 후속 개선을 검토한다.
- runnable claim history가 커지면 `next_attempt_at` 선두 partial index와 dispatcher fairness를 계측 후 결정한다.
