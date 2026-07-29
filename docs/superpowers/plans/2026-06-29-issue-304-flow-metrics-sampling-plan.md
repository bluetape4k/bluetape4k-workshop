# Issue 304 계획 - Flow 측정항목 샘플링 워크숍

## 범위

`feat/issue-304-flow-metrics-sampling` 브랜치의 이슈 #304를 새로운 이슈로 구현하세요.
워크숍 모듈:

`kotlin/flow-extensions-metrics-sampling`

## 0단계 - 작업 트리 및 증거

- 조치: `.worktrees/feat-issue-304-flow-metrics-sampling`에서 작업
  `origin/develop`.
- DoD: `repo-status`은 깨끗하고 살아있습니다. `gh issue view 304`는 OPEN을 확인합니다.
  담당자 `debop`, 마일스톤 `1.2.0` 및 라벨.

## 1단계 - 현재 코드 조사

- 조치: 기존 Flow 모듈, 업스트림 `throttle`, `pairwise`을 검사합니다.
  `zipWithNext`, `takeUntil` 및 관련 테스트.
- DoD: 구현 가정은 소스를 기반으로 합니다.
  - `throttleLeading`은 창당 첫 번째 값을 즉시 내보냅니다.
  - `throttleTrailing`은 창이 닫힐 때 창당 마지막 값을 내보냅니다.
  - `pairwise`은 `sliding(2)`을 통해 인접 쌍을 파생합니다.
  - `takeUntil`은 알림자 방출 시 중지되고 다운스트림 취소를 래핑하지 않습니다.

## 2단계 - TDD RED 테스트(복잡도: 중간, 적용 `$bluetape4k-code-patterns`)

프로덕션 코드 앞에 `MetricsSamplingPipelineTest`을 만듭니다.

- 선행 미리보기는 각 스로틀 창에서 첫 번째 샘플을 내보냅니다.
- 후행 대시보드는 각 스로틀 창에서 최종 샘플을 내보냅니다.
- 인접한 델타는 샘플 순서와 델타 계산을 유지합니다.
- 중요한 변화는 절대 임계값과 방향을 기준으로 필터링됩니다.
- stop 신호는 `takeUntil`을 통해 수집을 종료합니다.
- 수집기 취소가 전파되고 업스트림 정리가 실행됩니다.
- 도메인 검증은 blank/control-character 이름, 무한한 값,
  양수가 아닌 임계값 및 공개 `copy` 우회.

확인 명령:

```bash
./gradlew :kotlin-flow-extensions-metrics-sampling:test --tests "io.bluetape4k.workshop.flow.metrics.sampling.MetricsSamplingPipelineTest" --console=plain
```

예상 RED: 테스트 생성 후 해결되지 않은 프로덕션 classes/functions.

## 3단계 - 구현(복잡도: 중간, `$bluetape4k-code-patterns` 적용)

아래에 프로덕션 파일 추가
`src/main/kotlin/io/bluetape4k/workshop/flow/metrics/sampling/`:

- `MetricSamplingDomain.kt`
- `MetricsSamplingPipeline.kt`

구현 규칙:

- 검증된 제약 클래스는 전용 생성자와 팩토리 메소드를 사용합니다.
  유효성 검사가 중요한 경우 공개 데이터 클래스 `copy` 대신 사용하세요.
- 직렬화 가능한 값 classes/data 클래스에는 `serialVersionUID`이 포함됩니다.
- `MetricsSamplingPipeline`은 다음을 사용합니다.
  - `samples.throttleLeading(window).log("metrics-leading-preview")`
  - `samples.throttleTrailing(window).log("metrics-dashboard")`
  - `samples.pairwise(MetricDelta::from)`
  - `deltas(...).map(...).filter { it.significant }`
  - `samples.takeUntil(stopSignal).log("metrics-lifecycle")`
- 광범위한 `catch` 없음, `runCatching` 없음, 통화 차단 없음, scheduler/executor 없음.

DoD: 대상 테스트 명령이 통과되었습니다.

## 4단계 - 모듈 등록(복잡도: 낮음)

- 형제 Flow 모듈과 동일한 종속성 형태로 `build.gradle.kts`을 추가합니다.
- `src/test/resources/junit-platform.properties`을 추가합니다.
- `src/test/resources/logback-test.xml`을 추가합니다.
- `./gradlew projects`를 통해 Gradle 자동 등록을 확인합니다.

DoD: `:kotlin-flow-extensions-metrics-sampling`이 프로젝트 목록에 나타납니다.

## 5단계 - 학습자 문서 및 다이어그램(복잡도: 중간, `$bluetape4k-blog` 및 `$bluetape4k-diagram` 적용)

추가하다:

- `README.md`
- `README.ko.md`
- `docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-architecture-01.svg`
- PNG 일치
- `docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg`
- PNG 일치
- 밀착 인화지 PNG

README 요구사항:

- 제목 바로 아래에서 언어를 전환하세요.
- 시나리오와 학습 목표.
- 선행 대 후행 비교표.
- scheduler/timestamp 스니펫 앞.
- Flow 확장 체인 조각 뒤.
- 중고 Bluetape4k 기능 표.
- 테스트 및 연기 명령.

다이어그램 요구 사항:

- 아키텍처 보기에서는 위에서 아래로의 레이어와 명확한 차선 그룹화를 사용합니다.
- lifecycle/sequence 보기는 현재 시퀀스 모범 사례 제품군을 따릅니다.
  참가자, 생명선, 활성화 막대, 알약 라벨 및 alt/stop 영역.
- 카드는 code/Flow 역할을 담당하므로 Redis/DB/Kafka/service 아이콘이 없습니다.
- SVG XML 구문 분석, CairoSVG을 통한 PNG 렌더링, geometry/endpoint/style 감사
  통과하면 접착 시트가 검사되고 터치된 PNG이 전체 크기로 열립니다.

## 6단계 - Repo 등록(복잡도: 낮음)

- `README.md`에 새 모듈 행을 추가합니다.
- 소스에 해당하는 행을 `README.ko.md`에 추가합니다.
- 예제 워크플로 경로 필터, smoke 명령 및 아티팩트 업로드 경로를 추가합니다.
- `scripts/smoke-validate.sh` `all-smoke` 및 `async`에 모듈을 추가합니다.
- 오래된 확인 예상 프로젝트 수를 `85`에서 `86`로 늘립니다.

확인:

```bash
actionlint .github/workflows/Examples.yml
./scripts/smoke-validate.sh stale-check
```

## 7단계 - 검증(복잡도: 중간)

implementation/docs 이후에 실행:

```bash
./gradlew :kotlin-flow-extensions-metrics-sampling:test --console=plain
./gradlew :kotlin-flow-extensions-metrics-sampling:compileKotlin :kotlin-flow-extensions-metrics-sampling:compileTestKotlin --console=plain
./scripts/smoke-validate.sh async
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
actionlint .github/workflows/Examples.yml
git diff --check
```

다이어그램별 검증:

```bash
xmllint --noout docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-architecture-01.svg
xmllint --noout docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-architecture-01.svg docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-architecture-01.svg docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-sequence-style-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg
```

## 8단계 - 복습, 강의 및 PR

- 추적된 리뷰 아티팩트 생성:
  `docs/review/2026-06-29-issue-304-flow-metrics-sampling-code-review.md`.
- 추적된 수업 아티팩트 만들기:
  `docs/lessons/2026-06-29-issue-304-flow-metrics-sampling.md`.
- Lore 예고편으로 커밋하세요.
- 분기를 푸시합니다.
- 다음을 사용하여 PR를 만듭니다.
  - `Closes #304`
  - 담당자 `debop`
  - 이정표 `1.2.0`
  - 이슈 라벨이 미러링됨
  - 최종 PR 본문 섹션 정확히 `## DoD Status`
- `gh issue view` 및 `gh pr view`을 사용하여 라이브 issue/PR 메타데이터를 확인합니다.
- CI을 기다리고 병합 준비 보고서에서 중지합니다. 병합에는 명시적인 사용자가 필요합니다.
  병합 요청.

## 게이트 메모 검토

전체 기능을 갖춘 작업 흐름에서는 6가지 관점 검토가 필요합니다. 네이티브 하위 에이전트인 경우
사용할 수 있는 차선이 있으면 6-R단계로 실행하세요. 세션 표면이 이를 차단하는 경우
차선, 추적 검토에 지역에 해당하는 6개 관점 검토를 기록합니다.
아티팩트를 만들고 현재 세션의 심각도 정규화를 수행합니다.
