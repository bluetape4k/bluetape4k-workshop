# #532 Order Lifecycle Fulfillment 검토

Date: 2026-07-18
Module: `:commerce-order-lifecycle-fulfillment`
Scope: `commerce/order-lifecycle-fulfillment`
Branch: `feature/issue-532-order-lifecycle`

## 결론

- 독립 재검토: P0 0건, P1 0건. 초기 P1 7건은 수정 또는 명시된 local-only 보안 경계로 해소했다.
- PostgreSQL을 상태와 운영 증거의 최종 권위로 유지한다.
- 주문, 결제, 재고, fulfillment group, cancellation, refund는 독립 revision과 audit을 가진다.
- Redis/Kafka/실제 payment provider는 이 예제에 추가하지 않았다.
- `bluetape4k-logging`은 HTTP 결과, 멱등성 disposition, provider event, 상태 전이,
  publication replay, refund, SSE lifecycle 같은 운영 경계에 적용했다.

## 핵심 수정 검증

| 항목 | 결과 | 근거 |
|---|---|---|
| Provider payload conflict | PASS | inbox row를 `CONFLICT`로 갱신하고 unresolved evidence로 유지하는 통합 테스트 |
| 실제 split fulfillment | PASS | 한 order line을 두 group에 분배하고 2 groups/3 links를 검증 |
| 부분 취소 | PASS | 이미 `SHIPPED`인 group은 유지하고 미배송 group의 수량만 감소 |
| Order terminal 계산 | PASS | `DELIVERED + CANCELLED -> COMPLETED`, 모든 group 취소 시 `CANCELLED` |
| SSE admission | PASS | initial snapshot 실패 시 connection slot 반환 회귀 테스트 |
| SSE lifecycle/performance | PASS | order별 shared poller, DB permit 4, heartbeat/timeout/disconnect, bounded shutdown 및 open/shutdown race 테스트 |
| Idempotency retention | PASS | 만료 terminal bounded cleanup, `IN_PROGRESS` 보존, scheduled invocation PostgreSQL 테스트 |
| Publication projection | PASS | payload materialization 없이 단일 bounded aggregate query, unknown status fail-fast 테스트 |
| Restart recovery | PASS | 동일 PostgreSQL schema를 공유하는 두 Spring context에서 기본 자동 publication replay 검증 |
| Local security boundary | PASS | identity provider 없는 workshop은 `127.0.0.1`에만 bind; 외부 bind 전 tenant/operator authorization 필수 |
| Local CSRF boundary | PASS | operator POST는 browser simple request가 설정할 수 없는 custom header를 요구 |
| Java 25 범위 | PASS | workflow runtime Java 21을 덮지 않고 module toolchain만 Java 25 사용 |
| 실제 HTTP 테스트 | PASS | `RANDOM_PORT + WebTestClient.bindToServer()`로 Tomcat transport 검증 |
| Logging redaction | PASS | raw key/customer/SKU를 제외하고 hash prefix와 disposition만 남기는 회귀 테스트 |

## Ecosystem 및 dependency 확인

- 단일 BOM: `io.github.bluetape4k:bluetape4k-dependencies:1.3.1`
- `bluetape4k-exposed-jdbc:1.11.0`
- `bluetape4k-exposed-jdbc-tests:1.11.0`
- JetBrains Exposed core/JDBC: `1.3.0`
- `bluetape4k-virtualthread-api:1.11.0`
- runtime provider: `bluetape4k-virtualthread-jdk25:1.11.0`
- `bluetape4k-virtualthread-jdk21`은 runtime classpath에서 제외
- `bluetape4k-logging:1.11.0`
- `bluetape4k-testcontainers:1.11.0`과 `PostgreSQLServer.Launcher.postgres` 사용

## 검증 결과

- `:commerce-order-lifecycle-fulfillment:build --rerun-tasks`: PASS, 40 tests와 `bootJar`
- `./scripts/smoke-validate.sh commerce`: PASS
- `ktlint` module main/test: PASS
- `actionlint`: PASS
- `./scripts/smoke-validate.sh stale-check`: PASS
- 변경 SVG 두 개의 diagram QA: PASS, targets 2
- architecture/sequence repository validator: PASS
- PNG 원본 크기 육안 검토: PASS
- #532가 변경한 세 README 쌍의 language switch, heading, code fence, image target parity: PASS
- `git diff --check`: PASS

전역 README language/parity validator는 변경 범위 밖인
`image-processing/profile-image-moderation/README.md`의 기존 language switch와 한글 표기만
실패했다. #532가 변경한 README에는 같은 문제가 없다.

Java 25 테스트 중 Netty가 `System.loadLibrary` native-access 경고를 출력하지만 빌드와
40개 테스트는 성공했다. 현재 예제 결함은 아니며 향후 JVM 차단 전 runtime option 검토가 필요하다.

## 비차단 후속 항목

- audit unique key 중복 거부 repository 테스트
- 모든 deterministic provider mode의 독립 contract test
- inventory failure의 `RECONCILIATION_REQUIRED` 통합 테스트
- application-owned low-cardinality metric
- 외부 bind가 필요한 배포의 identity provider 및 tenant/operator authorization

위 항목은 현재 #532의 P0/P1 acceptance를 막지 않으며, 계획 문서에 미완료 상태로 유지한다.
