# Issue #317 Plan Review

- 날짜: 2026-06-30
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/317
- Plan: `docs/superpowers/plans/2026-06-30-issue-317-cloudwatch-imds-observability-plan.md`
- Spec: `docs/superpowers/specs/2026-06-30-issue-317-cloudwatch-imds-observability-design.md`
- Spec review: `docs/review/2026-06-30-issue-317-spec-review.md`
- 리뷰 게이트: Step 3-R
- 실행 메모: 이 세션에서 native subagent 관리가 불안정했으므로, 이 review는 main session에서 동일한 6개 관점 계약을 기록한다.

## 판정

| 심각도 | 건수 | 상태 |
|---|---:|---|
| P0 | 0 | PASS |
| P1 | 0 | PASS |
| P2 | 9 | implementation checklist로 이월 |
| P3 | 3 | 선택적 polish |

P0=0, P1=0이므로 Step 3-R은 진행할 수 있다.

## 관점별 발견사항

### 1. 성능

- P0/P1: 없음.
- P2: implementation 중 high-cardinality CloudWatch dimension이 실수로 추가되면 review에서 거부해야 한다. plan은 dimension을 `Outcome`, `Service`, `Source`로 올바르게 제한한다.
- P3: README는 이 예제가 explicit snapshot publisher이며 high-throughput telemetry pipeline이 아니라는 점을 설명해야 한다.

근거:

- Plan Task 5는 CloudWatch dimension을 low-cardinality 값으로 제한한다.
- Plan Tasks 2, 7, 8은 기본 검증을 no-container, credential-free로 유지한다.

### 2. 안정성

- P0/P1: 없음.
- P2: implementation은 partial-failure report의 deterministic ordering을 정의하여 테스트가 brittle해지지 않게 해야 한다.
- P2: cancellation test는 `CancellationException`을 던지는 publish path를 적어도 하나 이상 다뤄야 한다.

근거:

- Plan Task 2는 production code 작성 전에 mixed partial-failure test와 cancellation propagation test를 요구한다.
- Plan Task 3은 metric/log/meter/metadata 실패 semantic을 독립적으로 정의한다.

### 3. 보안

- P0/P1: 없음.
- P2: 생성된 JSON log 예시가 secret, header, environment value, raw metadata document, credential path를 포함하도록 learner를 유도하지 않는지 검토해야 한다.

근거:

- Plan Tasks 2와 5는 safe log field와 sensitive request data 부재를 assert한다.
- Plan Tasks 2와 6은 IMDS credential document read가 없음을 assert하고 credential boundary를 문서화한다.

### 4. 운영자

- P0/P1: 없음.
- P2: optional real AWS profile 문서가 resource creation을 포함한다면, 양쪽 locale에 cleanup/cost guidance를 포함해야 한다.
- P3: `actionlint`가 로컬에 설치되어 있지 않다면 설치 gap을 기록하고 PR 생성 후 GitHub Actions 검증에 의존한다고 명시한다.

근거:

- Plan Task 6은 local run command, optional real AWS command, environment variable, cost/cleanup warning을 포함한다.
- Plan Task 7은 정확한 Examples workflow, artifact, smoke group, stale-count 수정을 포함한다.

### 5. 개발자/API

- P0/P1: 없음.
- P2: 기존 Spring Boot 예제는 값을 명시하므로 Task 1에서 `springBoot.mainClass` 값을 확인해야 한다.
- P2: resolved `bluetape4k-aws` auto-configuration이 real client를 이미 제공한다면 optional real profile class는 critical path 밖에 둔다.

근거:

- plan은 implementation 전에 resolved artifact API 검증을 포함한다.
- plan은 serializable DTO rule, validation helper, local bluetape4k version pin 금지를 포함한다.

### 6. 사용자/호출자

- P0/P1: 없음.
- P2: README 예시는 happy path output만이 아니라 failed report와 metadata skipped report를 적어도 하나씩 포함해야 한다.
- P2: Diagram review는 official AWS icon을 실제 managed service에만 사용했는지, local fake bean의 styling이 구분되는지 검증해야 한다.

근거:

- Plan Task 6은 bilingual README source-equivalence, successful/failed report examples, metadata/credential boundary, official icon, transparent sequence region, branch-colored call, full-size PNG inspection을 요구한다.

## 통합 검토

plan은 spec과 issue acceptance criteria를 구현 가능한 순서의 concrete task로 매핑한다.

1. Dependency/API guard와 module skeleton이 code보다 먼저 온다.
2. Red test가 production implementation보다 앞선다.
3. Domain, local bean, service, HTTP boundary, docs, diagram, CI, final verification이 forward dependency 없이 순서화되어 있다.
4. Step 2-R의 P2 항목이 모두 named task로 표현되어 있다.
5. README locale pair, diagram audit, visual inspection, CI registration, PR metadata, final `## DoD Status` evidence가 포함되어 있다.

구현을 막는 P0/P1 발견사항은 없다.

## 필수 Implementation Checklist 추가 항목

다음 항목은 plan이 이미 다루므로 별도 plan edit은 필요 없지만, Step 4-6에서 확인해야 한다.

1. `springBoot.mainClass`를 명시한다.
2. Partial-failure report ordering은 deterministic이어야 한다.
3. Cancellation test는 실제 `CancellationException`을 사용한다.
4. README examples는 failed report와 metadata-skipped report를 포함한다.
5. Optional real AWS docs는 양쪽 locale에 cleanup/cost guidance를 포함한다.
6. Diagram visual inspection은 icon source, layer distinction, sequence numbering, transparent branch body, branch line color를 확인한다.

## 기각한 항목

- module을 Spring Boot directory로 옮기지 않는다. issue는 AWS observability이고 `settings.gradle.kts`가 이미 AWS module을 자동 등록한다.
- 이 예제에 LocalStack 또는 Testcontainers를 추가하지 않는다. learner-facing CloudWatch/IMDS 경계를 흐리고 기본 smoke coverage를 느리게 만든다.
- docs와 diagram을 PR review 이후로 미루지 않는다. workshop example의 핵심 deliverable이다.

## 열린 질문

없음.
