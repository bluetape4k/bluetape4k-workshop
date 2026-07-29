# Issue #524 구현 리뷰

## 결론

`optimization/planning-contracts`는 애플리케이션 소유 planning contract를
Spring Boot, PostgreSQL, Exposed repository, Java 25 virtual thread로 구현한다.
최종 리뷰에서 열린 P0/P1은 없다. PR 생성과 원격 push는 이번 범위에 포함하지 않았다.

## 계약 확인

| 요구사항 | 구현 및 증거 |
|---|---|
| Java 25 optimization 영역 | root toolchain 분기와 JDK 25 runtime contract test |
| dependencies 1.3.1 기준 | versionless module 선언과 dependencyInsight |
| Exposed repository 적극 활용 | `UUIDAuditableJdbcRepository`, `LongAuditableJdbcRepository`, `LongJdbcRepository` |
| exposed-jdbc-tests 활용 | `withTables(TestDB.POSTGRESQL, ...)` repository fixture |
| PostgreSQLServer | Spring/동시성 통합 테스트의 singleton launcher |
| Virtual Threads | `VirtualThreads.executorService()`와 실제 `Thread.isVirtual` 검증 |
| provider-neutral boundary | fake, Timefold, custom Solver adapter가 같은 `PlanningEngine` 계약 사용 |
| idempotency 및 restart | unique inbox, leased outbox, 서비스 재구성 duplicate replay test |
| 보안 | HMAC-before-state, provider 일치, bounded body/response, redacted query/log |
| Redis 정책 | 현재 불필요하므로 미사용; 후속 필요 시 Lettuce |

`bluetape4k-dependencies:1.3.1`은 현재 JetBrains Exposed `1.3.0`,
Bluetape Exposed `1.11.0`, virtualthread-jdk25 `1.11.0`을 선택한다. BOM 버전과
개별 라이브러리 버전을 동일시하지 않는다. jdk21 provider는 runtimeClasspath에 없다.

## 6-lens 리뷰

| 관점 | 확인 내용 | 결과 |
|---|---|---|
| Performance | batch 16, 짧은 task-owned transaction, 외부 HTTP를 transaction 밖에서 호출, 64 KiB response 상한 | P0=0, P1=0 |
| Stability | atomic request/outbox, atomic inbox/audit, claim lease, retry/dead-letter, bounded executor drain | P0=0, P1=0 |
| Security | signature-first, constant-time HMAC, active/stored provider 일치, 256 KiB callback 상한, secret/payload 미로그 | P0=0, P1=0 |
| Operator/Ops | Prometheus endpoint, low-cardinality observation/counter, recoverable lease, Java 21/25 CI setup | P0=0, P1=0 |
| Caller/User | planning 결과를 command candidate와 분리하고 aggregate version을 최종 재검증 | P0=0, P1=0 |
| Maintainability | provider port, application transaction boundary, Bluetape Exposed repository, bilingual README | P0=0, P1=0 |

리뷰 중 다음 P1을 발견해 구현과 회귀 테스트로 닫았다.

1. provider response를 전체 문자열로 읽은 뒤 크기를 검사하던 경로를
   `readNBytes(limit + 1)` 방식으로 변경했다.
2. request/callback provider 불일치를 차단하고 immutable audit decision으로 남겼다.
3. raw callback을 256 KiB까지만 읽고 동일 byte array로 HMAC과 parsing을 수행했다.
4. virtual-thread executor 종료 시 최대 30초 drain 후 강제 종료하도록 lifecycle을 추가했다.

## 검증 기록

- `:optimization-planning-contracts:cleanTest :optimization-planning-contracts:test`
  - 34 tests, failures 0, errors 0, skipped 0
- Java 21 `:exposed-mvc-virtualthread:compileKotlin`과 Java 25
  `:optimization-planning-contracts:compileKotlin` 혼합 compile 성공
- `scripts/smoke-validate.sh optimization` 성공
- `scripts/smoke-validate.sh stale-check`: 102 modules, stale refs 0, broken images 0
- 변경된 workflow 3개 `actionlint` 성공
- `git diff --check` 성공
- root `detekt`는 `NO-SOURCE`라 정적 분석 증거로 사용할 수 없다.

전체 README validator는 이번 변경 밖의 기존
`image-processing/profile-image-moderation/README.md`에서만 실패한다. language
switch 순서와 English README의 한국어 token 문제이며 #524 diff에는 포함되지 않는다.
