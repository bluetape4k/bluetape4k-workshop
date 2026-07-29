# Issue 331 Recommendation Explainer

## 배경

Issue #331은 explainable graph recommendation workshop example을 요청했다. 기존
`graph/recommendation` module은 이미 seed graph, blocking/suspend recommendation service,
TinkerGraph smoke test, optional Neo4j/Memgraph integration test를 갖고 있었다.

## 결정

새 module을 추가하지 않고 기존 module을 확장한다. 새로운 `explain*` API는 현재 ranking
contract를 재사용하고 다음 payload를 추가한다.

- evidence paths that created the score
- candidate exclusions such as already purchased, already followed, and self
- blocking/suspend API parity

이렇게 하면 TinkerGraph를 default no-Docker path로 유지하면서도 같은 abstract test를 Neo4j와
Memgraph에 대해 실행할 수 있다.

## 결과

`RecommendationService` and `RecommendationSuspendService` now expose:

- `explainProductRecommendations`
- `explainFollowRecommendations`

README locale set은 작은 table로 evidence payload를 문서화해 learner가 전체 graph를 읽지
않고도 추천 이유를 이해할 수 있게 한다.

## 검증

- `./gradlew :graph-recommendation:compileTestKotlin`
- `./gradlew :graph-recommendation:cleanTest :graph-recommendation:test --no-build-cache`
  - 74 tests, 0 failures, 0 skipped
- `./gradlew :graph-recommendation:integrationTest --no-build-cache`
  - 148 tests, 0 failures, 0 skipped

## 향후 guard

recommendation example에 explanation payload를 추가하면 plain ranking API와 explainable
API를 함께 테스트한다. 그렇지 않으면 learner-facing explanation이 ranking code에서 drift될
수 있다.
