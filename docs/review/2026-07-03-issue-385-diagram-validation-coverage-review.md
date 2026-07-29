# Issue 385 Diagram Validation Coverage Review

## 범위

- 이슈: #385 `Reduce legacy README diagram validation skip coverage`.
- 작업 유형: documentation/diagram QA governance.
- Diff 범위: validator script만 해당한다. SVG 또는 PNG asset은 수정하지 않았다.
- Baseline local build: `./gradlew build --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 1m 36s`.

## Coverage Change

| Validator | Before | After | 결과 |
|---|---:|---:|---|
| Architecture `legacySkipped` | 92 | 91 | 이미 valid한 legacy skip 하나를 제거했다. |
| Architecture `validated` | implicit | 22 | output이 이제 실제 validation coverage를 분리해서 보여 준다. |
| Sequence `legacySkipped` | 62 | 2 | 이미 valid한 legacy skip 60개를 제거했다. |
| Sequence `validated` | implicit | 86 | output이 이제 실제 validation coverage를 분리해서 보여 준다. |

## Remaining Documented Exceptions

Architecture exception은 여전히 넓고, 이제 `scripts/validate-readme-architecture-diagrams.mjs` output에서 `exceptionSlugs`로 출력된다. 첫 reduced batch는 legacy guard 없이 architecture validator를 통과하므로 `aws-s3-spring-cloud-readme-architecture-01.svg`를 제거했다.

Sequence exception은 이제 다음으로 제한된다.

- `kotlin-flow-extensions-race-fallback-readme-sequence-01.svg`
- `observability-micrometer-observation-readme-sequence-01.svg`

## Diagram QA Evidence Ledger

| Scope | Gate | 근거 | 결과 |
|---|---|---|---|
| Repo | Baseline local build | `BUILD SUCCESSFUL in 1m 36s`; log `/tmp/issue385-baseline-build.log` | PASS |
| Repo | Post-work local build | `BUILD SUCCESSFUL in 1m 48s`; log `/tmp/issue385-full-build-2.log` | PASS |
| Repo | JS syntax | 두 validator script에 대한 `node --check` | PASS |
| Architecture validator | Coverage output | `checked=113 validated=22 legacySkipped=91 documentedExceptions=91 failures=0` | PASS |
| Sequence validator | Coverage output | `checked=88 validated=86 legacySkipped=2 documentedExceptions=2 failures=0` | PASS |
| Diagram QA wrapper | Changed asset scope | `diagram QA wrapper: PASS targets=0` | PASS |
| Repo | Whitespace | `git diff --check` | PASS |
| SVG/PNG pairing | Asset changes | 이 governance pass에서는 SVG/PNG asset을 건드리지 않았다. diagram asset completion claim이 아니다. | N/A |
| Full-size PNG eye check | Asset changes | SVG/PNG asset을 건드리거나 다시 만들지 않았으므로 적용되지 않는다. | N/A |

## 7-Tier Review

| Tier | 판정 | 근거 |
|---|---|---|
| Security | PASS | runtime 또는 secret-handling path는 변경되지 않았다. |
| Stability | PASS | validator는 validation failure에서 계속 실패하며 이제 명시적 coverage count를 노출한다. |
| Performance | PASS | skip entry만 제거했다. validator work는 기존 diagram file에 대해 계속 linear하다. |
| Operator/Ops | PASS | 기존 `legacySkipped` field는 compatibility를 위해 보존하고, `validated`와 `documentedExceptions`를 추가했다. |
| Developer/API | PASS | output은 이제 실제 validation coverage와 documented exception을 구분한다. |
| User/Reader | PASS | README diagram asset이나 reader-facing content는 변경되지 않았다. |
| Evidence | PASS | baseline build, diagram validator, diagram QA wrapper, diff check, post-work full build가 통과했다. |

## 발견사항

- P0/P1: 0.
- P2: Architecture exception은 여전히 크며, 현재 validator를 이미 통과한 legacy architecture asset이 하나뿐이었으므로 작은 asset batch로 remediation해야 한다.
- P3: 남은 두 sequence exception은 현재 asset이 redraw 없이 best-practices sequence styling을 만족할 수 없다면 targeted diagram remediation issue로 처리해야 한다.
