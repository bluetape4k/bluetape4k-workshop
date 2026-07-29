# Issue #330 Event Lineage Workshop

## 배경

Issue #330에는 기존 graph-io import pipeline과 겹치지 않으면서 event lineage, aggregate
audit reconstruction, approval evidence, superseding event, missing-cause detection을
가르치는 advanced graph workshop example이 필요했다.

## 결정

`graph/event-lineage`를 빠른 TinkerGraph lane과 Neo4j Testcontainers integration lane을
가진 GraphOperations workshop module로 만든다.

- `EventLineageService`는 idempotent vertex creation, direct edge creation, bounded
  causal traversal, superseded chain, aggregate audit assembly를 소유한다.
- `EventLineageSchema`는 learner를 위해 label과 property name을 명시적으로 유지한다.
- `EventLineageSeed`는 의도적인 missing causal link 하나를 포함한 deterministic order
  approval scenario를 만든다.
- README와 README.ko는 architecture/sequence diagram 및 실행 가능한 test command로 model을
  가르친다.

## 결과

module은 이제 business state를 event, decision, actor, correction vertex로 설명하는 방법을
보여준다. default test lane은 TinkerGraph로 smoke-safe하게 유지되고, `integrationTest` lane은
`bluetape4k-testcontainers`의 `Neo4jServer`에 대해 같은 service를 증명한다.

## 검증

- `./gradlew --no-daemon :graph-event-lineage:test --no-build-cache --rerun-tasks --console=plain`
- `./gradlew --no-daemon :graph-event-lineage:integrationTest --no-build-cache --rerun-tasks --console=plain`
- `./gradlew --no-daemon :graph-event-lineage:compileKotlin :graph-event-lineage:compileTestKotlin --warning-mode all --console=plain`
- `./gradlew --no-daemon projects --console=plain`
- `./scripts/smoke-validate.sh all-smoke`
- `./scripts/smoke-validate.sh stale-check`
- architecture와 sequence SVG에 대한 explicit
  `node scripts/validate-readme-diagram-qa.mjs`
- 두 diagram 모두에 대한 full-size PNG eye inspection
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## 향후 지침

sequence diagram에서는 visual appearance에만 의존하지 않는다. direct QA script는 `num`이
있는 numbered call label, transparent alt body, 일치하는 arrowhead color, terminal segment가
card edge와 맞는 activation endpoint를 기대한다. architecture diagram에서는 connector,
endpoint, rounded-corner audit이 검사할 수 있도록 모든 rendered connector path에
`data-connector` metadata가 필요하다.
