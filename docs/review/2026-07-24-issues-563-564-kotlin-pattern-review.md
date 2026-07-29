# Kotlin Pattern Review: #563 / #564

## 결론

P0=0, P1=0이다. Order Lifecycle SSE의 virtual-thread monitor와 production `!!`는
제거됐고, SSE poll concurrency 설정은 BOM이 해석한 `bluetape4k-core:1.11.0`의
`requirePositiveNumber`으로 전환됐다. 남은 P2는 독립 issue #565–#569으로 모두
추적한다. 따라서 #564는 후속 P2가 열려 있어 닫지 않는다.

## 발견과 처리

| ID | Severity | 결과 | 근거 |
|---|---|---|---|
| KT-01 | P1 | resolved | `OrderEventStream`의 `synchronized(lifecycleLock)`을 `ReentrantLock.withLock`으로 교체했다. virtual-thread executor에서 monitor를 쓰지 않는다. |
| KT-02 | P1 | resolved | `feeds.compute(...)!!`를 `checkNotNull(...){ "feed creation must return an OrderFeed" }`로 교체했다. |
| KT-03 | P2 | resolved in this slice | `maxConcurrentPolls`는 released `requirePositiveNumber` helper를 사용한다. |
| KT-04 | P2 | tracked | Job Console의 production `UUID.randomUUID()`은 #565로 분리했다. |
| KT-05 | P2 | tracked | assertion migration은 #566 (21 files), #567 (7 files), #568 (4 files)로 분리했다. |
| KT-06 | P2 | tracked | 남은 Order Lifecycle raw validation은 #569로 분리했다. regex/length helper 부재는 `bluetape4k-projects#1079`을 따른다. |

## Triggered Kotlin Checklist

| Checklist | 상태 | Evidence |
|---|---|---|
| KT-01 | PASS | Kotlin production/test 변경이므로 `bluetape-kotlin-patterns`, testing, final checklist를 적용했다. Spring configuration, Exposed schema, module layout 변경은 없다. |
| KT-02 | PASS | BOM `dependencyInsight`, `javap`, current source/test/caller search를 실행했다. 새 helper·dependency·`shared` abstraction은 추가하지 않았다. |
| KT-03 | PASS | caller validation은 released `requirePositiveNumber`을 사용하고 internal compute invariant은 `checkNotNull`이다. virtual-thread lifecycle monitor와 production `!!`는 없다. |
| KT-04 | PASS | focused `OrderEventStreamTest` 9개와 full module 42개 test를 fresh run했다. |
| KT-05 | PASS | 이 문서와 #564 live update에 P0/P1/P2 수렴 상태를 기록한다. |
| KT-TEST-01 | PASS | touched test는 JUnit `assertInstanceOf`를 `shouldBeInstanceOf`로 교체했고 `assertFailsWith`/Bluetape assertions를 사용한다. |
| KT-TEST-02 | PASS | existing virtual-thread race, shared-poller, bounded shutdown test를 보존했다. fixed sleep은 Awaitility `during(...).atMost(...)` stable condition으로 교체했다. |
| KT-TEST-03 | N/A | Testcontainers fixture 변경이 없다. |
| KT-TEST-04 | N/A | HTTP adapter/HC5 surface 변경이 없다. |
| KT-TEST-05 | PASS | red test 2개를 각각 관찰한 뒤 green focused suite와 full module suite를 실행했다. |

## Final Checklist

| Item | 상태 | Evidence |
|---|---|---|
| KT-FIN-01 | PASS | `OrderEventStream`, `OrderEventStreamTest`, released JAR signature, #563 inventory를 current branch에서 재검토했다. |
| KT-FIN-02 | PASS | SSE positive poll count의 `IllegalArgumentException` contract를 released helper로 유지했다. |
| KT-FIN-03 | PASS | `rg 'synchronized\\(|!!'`가 target production stream에서 no match를 반환했다. |
| KT-FIN-04 | PASS | snapshot failure, timeout/disconnect, shared poller, bounded shutdown, open/destroy race test가 lifecycle ownership을 증명한다. |
| KT-FIN-05 | N/A | Exposed boundary 변경이 없다. |
| KT-FIN-06 | PASS | testing trigger를 적용했고 Spring/module/Testcontainers/HTTP trigger는 N/A다. |
| KT-FIN-07 | PASS | explicit-lock 및 released-helper test는 변경 전 각각 예상대로 실패했고 변경 후 통과했다. |
| KT-FIN-08 | N/A | public API/KDoc/README 변경이 없다. |
| KT-FIN-09 | PASS | targeted compile/test와 full module compile/test가 통과했다. |
| KT-FIN-10 | PASS | full module validation, root `detekt`, `git diff --check`를 실행했다. |
| KT-FIN-11 | PASS | #563 inventory, SSE P1, documented P2 handoff만 포함한다. P0=0/P1=0이다. |

## Fresh commands

```text
./gradlew :commerce-order-lifecycle-fulfillment:test --tests '*OrderEventStreamTest' --console=plain
# SUCCESS: 9 tests
./gradlew :commerce-order-lifecycle-fulfillment:compileKotlin :commerce-order-lifecycle-fulfillment:compileTestKotlin :commerce-order-lifecycle-fulfillment:test --warning-mode all --console=plain
# BUILD SUCCESSFUL: 42 tests
./gradlew detekt --console=plain
# BUILD SUCCESSFUL
```

Byte Buddy가 `sun.misc.Unsafe`의 terminal deprecation warning을 출력했지만, branch
source 변경과 무관한 dependency runtime warning이며 test/detekt verdict에는 failure가
없었다.
