# Issue 304 Flow metrics sampling code review

## 범위

- Issue: #304, milestone 1.2.0.
- Module: `kotlin/flow-extensions-metrics-sampling`.
- Artifacts: Flow metrics sampling example, bilingual README, top-to-bottom architecture/sequence
  diagram, Examples workflow, smoke validation wiring.

## 리뷰 finding

여섯 review lane이 correctness, cancellation behavior, Kotlin/API style, learner experience,
documentation parity, CI registration을 확인했다.
핵심 판단은 cancellation contract가 구현 세부 channel boundary가 아니라 learner가 호출하는
Result mapping API에서 증명되어야 한다는 점이었다.

- P0: 0.
- P1 before fixes: unique finding 1건.
- P1 after fixes: known 0건.
- P2 before fixes: cancellation test가 explicit cancellation-safe Result mapping extension 대신
  `throttleLeading` channel boundary를 고정하고 있었다.
- P2 after fixes: PR handoff 기준 known 0건.

## 리뷰 후 적용한 수정

- cancellation regression을 `significantChangeResults`를 보여주도록 재작업했다. 이 함수는
  `mapResultCatching`을 사용하고 `CancellationException`을 `Result.failure`로 감싸지 않고
  명시적으로 다시 던진다.
- `takeUntil` coverage는 stop signal을 통한 normal lifecycle termination에 집중하도록 유지했다.
- module에 실제 Redis, broker, database, server, cache infrastructure가 없으므로 code-only
  text card를 사용한 top-to-bottom layered architecture와 best-practices sequence diagram을
  추가했다.
- module을 README table, Examples workflow path filter/task/artifact, async smoke validation에 등록했다.

## 검증 증거

- `./gradlew :kotlin-flow-extensions-metrics-sampling:test --tests "io.bluetape4k.workshop.flow.metrics.sampling.MetricsSamplingPipelineTest" --console=plain` 통과: 7 tests.
- `./gradlew :kotlin-flow-extensions-metrics-sampling:test :kotlin-flow-extensions-metrics-sampling:compileKotlin :kotlin-flow-extensions-metrics-sampling:compileTestKotlin --console=plain` 통과.
- `./scripts/smoke-validate.sh async` 통과, `:kotlin-flow-extensions-metrics-sampling:test` 포함.
- `./scripts/smoke-validate.sh stale-check` 통과: active modules 86개, stale README ref 없음,
  broken README image link 없음.
- `node scripts/validate-readme-language.mjs`, `node scripts/validate-readme-parity.mjs`,
  `node scripts/validate-readme-architecture-diagrams.mjs`,
  `node scripts/validate-sequence-diagrams.mjs`가 통과했다.
- 새 SVG에 대해 Diagram XML, geometry, endpoint, connector, mixed-corner, sequence-style
  audit가 통과했다.
- connector audit 증거: architecture
  `PASS markers=1 connectors=5 cards=5 intrusions=0 crossings=0`; sequence
  `PASS markers=5 connectors=7 cards=0 intrusions=0 crossings=0`.
- geometry audit 증거: architecture `geometry_failures=0`; sequence `geometry_failures=0`.
- mixed-corner audit 증거: `PASS files=2 paths=12 q_bends=0 failures=0`.
- sequence style audit 증거: `PASS sequence_files=1`.
- architecture, best-practices sequence, contact sheet에 대한 PNG visual inspection이 통과했다.
- `actionlint .github/workflows/Examples.yml` 통과.
- `git diff --check` 통과.

## Rollback

이 example을 안전하게 제거하려면 다음을 수행한다.

1. `kotlin/flow-extensions-metrics-sampling`을 삭제한다.
2. `scripts/smoke-validate.sh`에서 `kotlin-flow-extensions-metrics-sampling`을 제거하고 expected module count를 복원한다.
3. `.github/workflows/Examples.yml`에서 module path filter, Gradle task, artifact path를 제거한다.
4. root README와 README.ko의 module link를 제거한다.
5. `docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-*`를 제거한다.
6. `./gradlew projects`, `./scripts/smoke-validate.sh stale-check`, README diagram validator,
   `git diff --check`를 다시 실행한다.
