# Issue 287 Graph IO Pipeline Lessons

## 배경

Issue #287은 container 없이 TinkerGraph에서 CSV import, Jackson 3 NDJSON export/import,
GraphML export/import를 가르치는 graph-io import/export workshop module을 추가했다.

## 결정

- module을 consumer-scoped로 유지한다. `bluetape4k-dependencies`가 관리하는 versionless
  alias를 사용하고 graph-specific BOM은 import하지 않는다.
- CSV는 먼저 scratch `TinkerGraphOperations`로 import한다. `GraphIoStatus.COMPLETED`
  이후에만 target graph로 copy해, 실패한 import가 learner-visible partial state를 남기지
  않게 한다.
- graph-io example은 작고 deterministic하게 유지한다. 3 vertices, 2 edges,
  Testcontainers 없음, 모든 generated round-trip file은 `@TempDir` 아래에 둔다.
- 명시적 legacy diagram allowlist를 사용한다. 새 diagram 또는 변경된 diagram SVG는 git
  cleanliness에 의존하지 말고 현재 validator structure를 만족해야 한다.

## 결과

새 `graph/io-pipeline` module은 README/README.ko, PNG/SVG diagram, CSV fixture,
fail-closed GraphML test, smoke wiring, Examples workflow coverage를 갖는다.

## 검증

- `./gradlew :graph-io-pipeline:test --rerun-tasks --console=plain --no-daemon`
- `./scripts/smoke-validate.sh all-smoke`
- `./scripts/smoke-validate.sh stale-check`
- README parity/language validators
- architecture and sequence diagram validators
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## 향후 지침

- graph-io example이 report를 가르친다면, 모든 README snippet은 exported file을 사용하기
  전에 `GraphIoStatus.COMPLETED`와 비어 있는 `failures`를 확인해야 한다.
- graph-io import가 live target graph에 write한다면, returned report count뿐 아니라 target
  graph mutation에 대한 failure path도 테스트한다.
- old diagram asset을 보존할 때는 정확한 legacy slug를 나열하고, 모든 new asset은 strict
  validator path에 둔다.
