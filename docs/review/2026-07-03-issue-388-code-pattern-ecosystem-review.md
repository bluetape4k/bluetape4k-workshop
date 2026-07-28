# Issue #388 Code Pattern Ecosystem Review

날짜: 2026-07-03
범위: repository-wide Kotlin pattern scan과 11개 workshop module의 safe fix.

## 요약

이 review는 milestone 1.3.1 example 이후 남은 ecosystem-use drift와, 초기 PR 범위가 너무 좁았다는 후속 scan 요청을 확인한다.

- `OrderId.newId()`의 opaque string ID generation을 `Base58.randomString(8)`로 교체한다.
- `kotlin.test.assertFailsWith`를 `io.bluetape4k.assertions.assertFailsWith`로 교체한다.
- touched domain value object에서 `bluetape4k.support` validation helper를 선호한다.
- scanned source tree의 production `!!`를 제거하고, caller-input과 invariant 의도에 따라 `requireNotNull`, `requireNotBlank`, `checkNotNull`로 대체한다.
- runtime `println`을 bluetape4k lazy logging으로 교체한다.
- 여전히 `!!`를 가르치던 오래된 commented projection example을 제거한다.

## Repository Pattern Scan

wide scan은 tracked Kotlin file 1,473개를 다뤘다.

| Pattern | Total | `src/main` | 처리 |
|---|---:|---:|---|
| `!!` | 176 | 0 | Production code는 수정됨. 남은 match는 test/example이며 #392에서 추적한다. |
| Raw `require(...)` | 155 | 151 | 넓은 domain-validation cleanup은 남아 있다. 많은 항목이 constructor/domain invariant site이며 #390에서 module-by-module intent check가 필요하다. |
| Raw `check(...)` | 33 | 15 | 대부분 invariant check이므로 mechanical replacement를 하지 않는다. |
| `Thread.sleep(...)` | 113 | 31 | 상당수는 virtual-thread/blocking demonstration example이다. production/service sleep은 #391에서 추적한다. |
| `runBlocking { ... }` | 6 | 6 | 모든 production match는 blocking-to-suspend bridge example이다. cancellation/dispatcher review는 #391에서 추적한다. |
| Raw `println(...)` | 12 | 7 | runtime usage는 수정됨. 남은 production match는 KDoc example이다. |
| Raw `GenericContainer` | 0 | 0 | 위반 없음. |
| Direct `XxxServer(...)` constructor | 7 | 1 | main match는 issue #382 / PR #387에서 다룬다. test match는 documented custom-container exception이다. |
| Legacy assertion imports | 0 | 0 | `kotlin.test`, JUnit assertion, AssertJ, Kluent import는 남아 있지 않다. |
| `UUID.randomUUID()` | 0 | 0 | 남은 UUID generation drift 없음. |

## 7-Tier Review

| Tier | 판정 | 근거 |
|---|---|---|
| 1 Security | PASS | 생성된 order identifier는 opaque synthetic example id로 남으며 PII를 포함하지 않는다. |
| 2 Correctness | PASS | `OrderId.newId()`는 계속 non-blank `order-*` id를 생성한다. validation은 caller-input failure를 `IllegalArgumentException`으로 유지하고, persisted-id/observation/singleton assumption은 이제 명시적 invariant로 실패한다. |
| 3 Architecture | PASS | module boundary나 public workflow는 변경되지 않았다. diff는 raw JDK/test helper와 unsafe null assertion을 bluetape4k ecosystem helper 또는 명시적 Kotlin invariant로 바꾼다. |
| 4 Code Quality | PASS | touched code는 `Base58.randomString`, `requireNotBlank`, `requireNotEmpty`, `requireNotNull`, `requirePositiveNumber`, `requireZeroOrPositiveNumber`, `checkNotNull`, lazy logging, `bluetape4k.assertions.assertFailsWith`를 사용한다. |
| 5 Tests | PASS | touched module 전체와 original issue module에서 compile/test가 통과했다. |
| 6 Docs/Examples | PASS | README behavior는 변경되지 않았다. review와 lesson artifact가 ecosystem rule 및 exception을 기록한다. |
| 7 Evidence | PASS | Pattern grep, compile, test, `git diff --check`를 feature branch에서 실행했다. |

P0/P1 발견사항: 0.

## 확인된 예외

- `leader/leader-election/src/test/.../RedisFailureTest.kt`는 test가 Redis를 의도적으로 중지해야 하며 shared launcher singleton을 멈추면 안 되므로 dedicated `RedisServer`를 유지한다.
- `spring-boot/cache-resilience/src/test/.../ResilientCacheServiceTest.kt`는 Redis container가 Toxiproxy와 같은 Docker network에 `redis` alias로 참여해야 하므로 dedicated `RedisServer`를 유지한다.
- `spring-data/elasticsearch*/src/test/.../ElasticsearchServerTest.kt`는 non-default password로 server wrapper behavior를 검증하기 위해 server를 의도적으로 직접 생성한다.
- `ratelimit/bucker4j-bluetape4k-webflux/.../TestRedisConfig.kt`는 별도 issue #382 / PR #387 branch에서 다룬다.
- Production `Thread.sleep` 및 `runBlocking` match는 여러 module이 explicit blocking, virtual-thread, leader-bridge example이므로 기계적으로 변경하지 않았다. behavior-preserving module issue가 필요하다.
- 이 pass에서 생성한 follow-up issue: #390 raw validation helpers, #391 blocking/sleep boundaries, #392 test null assertions.

## 검증

- `rg -n "UUID\\.randomUUID\\(\\)|import java\\.util\\.UUID|import kotlin\\.test|kotlin\\.test\\.assertFailsWith|assertThrows\\(|org\\.junit\\.jupiter\\.api\\.Assertions" spring-modulith/ddd-order-audit kotlin/flow-extensions-parallel-enrichment -g '*.kt'`는 match를 반환하지 않았다.
- 수정 후 repository scan: production `!!` = 0, raw `GenericContainer` = 0, legacy assertion imports = 0, `UUID.randomUUID()` = 0.
- `./gradlew :spring-modulith-ddd-order-audit:compileKotlin :spring-modulith-ddd-order-audit:compileTestKotlin :kotlin-flow-extensions-parallel-enrichment:compileKotlin :kotlin-flow-extensions-parallel-enrichment:compileTestKotlin :spring-data-jpa-querydsl:compileKotlin :spring-data-jpa-querydsl:compileTestKotlin :spring-data-redis-examples:compileKotlin :spring-data-redis-examples:compileTestKotlin :spring-data-elasticsearch-webflux:compileKotlin :spring-data-elasticsearch-webflux:compileTestKotlin :spring-data-mongodb-transactions:compileKotlin :spring-data-mongodb-transactions:compileTestKotlin :exposed-webflux-r2dbc:compileKotlin :exposed-webflux-r2dbc:compileTestKotlin :bucket4j-redis:compileKotlin :bucket4j-redis:compileTestKotlin :spring-modulith-jpa-demo:compileKotlin :spring-modulith-jpa-demo:compileTestKotlin :micrometer-observation:compileKotlin :micrometer-observation:compileTestKotlin :kotlin-design-patterns:compileKotlin :kotlin-design-patterns:compileTestKotlin --warning-mode all --max-workers=1 --console=plain` 통과.
- `./gradlew :spring-modulith-ddd-order-audit:test :kotlin-flow-extensions-parallel-enrichment:test :spring-data-jpa-querydsl:test :spring-data-redis-examples:test :spring-data-elasticsearch-webflux:test :spring-data-mongodb-transactions:test :exposed-webflux-r2dbc:test :bucket4j-redis:test :spring-modulith-jpa-demo:test :micrometer-observation:test :kotlin-design-patterns:test --max-workers=1 --console=plain` 통과.
- `git diff --check` 통과.

잔여 위험:

- Spring Modulith test run은 성공 후 PostgreSQL/JPA shutdown warning을 기록한다. 이는 기존 container lifecycle noise이며 code-pattern refactor가 도입한 것이 아니다.
- Test source에는 여전히 많은 `!!`와 `Thread.sleep` usage가 있다. 여러 테스트는 기계적 교체가 아니라 assertion intent와 Awaitility/coroutine-helper rewrite가 필요하므로 별도 focused issue로 처리해야 하는 visible debt다.
