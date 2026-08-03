# 공유 voucher contract fixture는 실행일에 만료되면 안 된다

## 맥락

2026-08-02 `Examples` run 30771298111과 이전 `Nightly` run 30764691938에서
`commerce-event-sourced-promotion-voucher-campaign:integrationTest`가 실패했다.
실패한 HTTP 응답은 `409 CAMPAIGN_ENDED`였다. 같은 기간 최신 Dependabot `CI`
run 30768173911은 `bucket4j-caffeine-web`과 `bucket4j-redis`의
`collectReachabilityMetadata`가 `schemas` 디렉터리가 없는 캐시 저장소를 읽어
실패했다. 따라서 voucher fixture와 Gradle native metadata cache 문제가 함께
존재했다.

## 원인

공유 `VoucherCampaignBlackBoxContract`가 `2026-07-22`부터 `2026-07-31`까지의
고정 campaign window를 사용했다. 계약을 추가한 뒤 실행일이 7월 31일을 지나면서
두 voucher adapter가 정상 allocation을 보내도 만료 campaign으로 거부했다.
`Nightly`의 같은 실행은 이 문제와 별개로 Java 25에서 Detekt parser가 실패한
오래된 `develop` SHA도 포함했다. 또한 GraalVM native plugin의
`collectReachabilityMetadata`는 compile/test 결과에 필요하지 않은 선택적 task인데,
Dependabot의 cache-read-only 실행에서 오래된 reachability metadata 저장소를
재사용했다.

PR #709의 첫 `Examples` 실행(30804446013)에서는 별도로
`ImageOcrServiceImplTest`의 `blocking OCR timeout releases the native lane`가
실패했다. 테스트 fixture의 전체 OCR timeout이 100ms라서, CI에서 두 번째 호출의
이미지 decode가 그 예산을 조금 넘으면 lane 자체는 해제됐어도 정상 응답 대신
`FAILED`가 반환됐다. 이는 production timeout 회귀가 아니라 환경별 decode 지연에
민감한 테스트 경계였다.

## 결정

공유 contract scenario의 시작 시각을 로드 시점 1분 전으로 두고, 종료 시각을
그로부터 2일 뒤로 계산한다. 이렇게 하면 fixture의 의도(현재 시각에 활성인
campaign)를 유지하면서 달력 날짜가 바뀌어도 재발하지 않는다. production clock나
별도 dependency는 추가하지 않는다. CI·Nightly·Examples의 compile/test 및
high-contention Gradle 경로에는 `-x collectReachabilityMetadata`를 적용해 선택적
native-image metadata cache를 소비하지 않도록 한다. 실제 native-image 빌드 경로는
이 workflow 범위에 포함하지 않는다. OCR timeout regression fixture는 timeout을
500ms로 늘려 첫 번째 blocking 호출의 timeout 의미는 유지하면서 CI decode variance를
흡수한다.

## 검증

- 실패 artifact에서 `CAMPAIGN_ENDED` 응답과 409 assertion을 확인했다.
- `:shared:test --tests ...VoucherCampaignBlackBoxContractTest` 3개 테스트 통과.
- `:commerce-promotion-voucher-campaign:compileTestKotlin` 통과.
- `:commerce-event-sourced-promotion-voucher-campaign:compileTestKotlin` 통과.
- `:bucket4j-caffeine-web:build -x collectReachabilityMetadata -x detekt -x test` 통과.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/Examples.yml` 통과.
- `scripts/smoke-validate.sh`의 공통 Gradle 경로와 세 workflow의 직접 Gradle 경로에
  metadata task 제외가 적용됐음을 확인했다.
- `ImageOcrServiceImplTest`의 OCR timeout regression을 500ms fixture로 실행해 1개
  테스트 통과.
- PR #709의 첫 `Examples` 실행에서 위 테스트가 100ms 경계로 실패한 로그와 artifact를
  확인하고, 로컬에서도 같은 실패를 재현한 뒤 fixture 조정 후 재실행 통과.
- 로컬 event-sourced integrationTest는 Docker 미가용으로 Spring context 초기화에서
  중단되었으며, 이는 테스트 assertion 실패가 아니다. GitHub artifact가 남긴 원래
  RED 증거는 별도로 보존했다.

2026-08-03 merge commit `22787298`의 Examples run `30807714538`에서는
`commerce-order-lifecycle-fulfillment`의 `OrderLifecycleWebIntegrationTest`가
Container runner에서 `browser commands reconcile delayed payment and keep cancellation
separate from refund` 상태 검증을 10초 안에 끝내지 못했다. `test` task가 공유하는
Nightly full 경로도 같은 HTTP/Testcontainers 테스트를 실행하므로, Examples만 재시도해
넘기는 것은 충분한 수정이 아니다. production 결제 상태 전이는 올바르게 구현되어
있고, 실패 지점은 CI 부하에서의 HTTP/DB 비동기 후속 상태 확인 예산이었다.

따라서 `AbstractOrderLifecycleIntegrationTest`의 `WebTestClient` response timeout과
웹 lifecycle 테스트의 Awaitility 예산을 30초로 맞췄다. 기능을 건너뛰거나 production
timeout을 바꾸지 않고, Container/Nightly의 공유 테스트가 실제 `SUCCEEDED` 상태와
후속 취소/환불 계약을 계속 검증하도록 유지한다.

## 다음 실행 지침

공유 voucher compatibility scenario에 고정된 과거 날짜를 넣지 않는다. 시간 경계
동작 자체를 검증해야 하는 테스트는 해당 adapter의 test clock을 고정하고, 공용
black-box contract는 실행 시점에 유효한 window를 사용한다. Blocking/native 테스트는
CI의 image decode와 scheduler variance보다 짧은 timeout을 assertion budget으로
사용하지 않는다.
Container/Nightly가 공유하는 HTTP lifecycle 테스트는 실제 runner의 DB 및 이벤트 처리
지연보다 짧은 response/Awaitility timeout을 사용하지 않는다.

추가로 수동 Nightly full run `30810489739`에서는 `Build (compile only)`가 이름과 달리
`build` lifecycle을 통해 `commerce-event-sourced-promotion-voucher-campaign:integrationTest`까지
실행했고, `EventSourcedProjectionRuntimeIntegrationTest`의 poison gauge가 아직 등록되지
않은 순간에 실패했다. compile-only 단계는 검증 테스트를 중복 실행하지 않도록 `assemble`을
사용하고, bounded reason-class metric gauge는 초기화 시 미리 등록해 첫 관측 tick의
순서와 무관하게 `HANDLER_REJECTED`/`UNKNOWN` 값을 관측할 수 있게 한다.

Examples run `30810461549`에서도 동일한 주문 lifecycle web test가 30초 예산으로
재차 `SUCCEEDED` 상태 assertion을 끝내지 못했다. polling Awaitility는 60초로 늘리되
개별 HTTP 요청은 10초에서 끊어 다음 polling 시도에 진행권을 넘긴다. 이는 production
timeout을 바꾸지 않고 Container/Nightly의 일시적인 DB·이벤트 처리 정체를 회복할 수 있게
하는 test-only 경계다.

수정된 PR head `085dfc7cdf9b145578974cbced3735c5f92ddfb9`의 수동 Nightly full run
`30811875199`에서는 compile-only 단계가 `assemble`로 정상 종료했지만, 두 개의 별도
결정적 실패가 드러났다. `kotlin-flow-extensions-parallel-enrichment`의 테스트가
published `bluetape4k-junit5`에 없는 `io.bluetape4k.coroutines.tests.withParallels`를
import하고 있었고, `observability-basic`은 공유 `MockWebServer` connection 재사용으로
404 inventory 요청이 180초까지 대기했다. `redis-cluster-demo`는 cluster 전체에 대한
무인자 `flushDb()`가 replica를 임의 선택해 `READONLY`를 반환했다. high-contention의
`ticket-spring/redis-path-outage`는 동일 run에서 PostgreSQL/Exposed prepared statement
실패로 별도 RED가 남았고, 같은 suite의 과거 Nightly에도 반복된 runner/DB contention
신호이므로 재실행 결과와 raw log를 함께 확인해야 한다.

Flow 테스트는 published package인 `io.bluetape4k.junit5.coroutines.withParallels`를
사용하도록 고쳤다. Observability의 공유 mock 응답에는 `Connection: close`를 명시해
테스트 context 간 pooled connection 재사용을 차단했고, Redis cluster fixture는
`clusterGetNodes()`에서 MASTER만 골라 각 master에 `flushDb(node)`를 보내도록 바꿨다.
이렇게 하면 테스트를 무작정 늘린 timeout이나 replica 쓰기로 통과시키지 않고 CI runner의
공유 상태 경계를 명시적으로 격리할 수 있다.

로컬 검증에서는 Flow 7개, Observability 6개 테스트와 Redis test compile이 통과했다.
Flow는 로컬 `mavenLocal()`의 오래된 동일 버전 jar가 helper를 포함하지 않아 published
Central jar를 직접 사용한 임시 Gradle 검증으로 확인했다. Redis runtime은 Docker socket이
없어 실행하지 못했으므로, GitHub Nightly 재실행에서 cluster master 초기화 동작을
확인해야 한다.

새 head의 Examples run `30817734373`에서도 같은 lifecycle 테스트의 두 번째 상태 조회가
`WebTestClient`의 10초 response timeout에서 즉시 종료됐다. 기존 `untilAsserted`는
Awaitility의 assertion failure는 재시도하지만 이 일시적인 `IllegalStateException`을
무시하지 않아, 전체 60초 polling 예산을 사용하기 전에 테스트가 실패했다. 모든
test-only 상태 polling을 `await.ignoreExceptions().atMost(...).untilAsserted`로 바꿔
transport timeout을 일시적인 관측 실패로 취급하고, 최종 상태가 끝내 도달하지 않으면
동일한 bounded timeout으로 실패하도록 했다. 이 실패는 새 production 동작 변경이 아니라
Container runner의 첫 polling 요청 지연이라는 재현 가능한 테스트 경계 문제였다.
