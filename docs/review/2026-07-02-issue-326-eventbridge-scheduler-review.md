# Issue 326 EventBridge Scheduler 7-Tier Review

## 범위

- Module: `aws/eventbridge-scheduler`
- 문서: root README locale set, AWS README locale set, module README locale set
- Diagram: `aws-eventbridge-scheduler-readme-architecture-01.*`,
  `aws-eventbridge-scheduler-readme-sequence-01.*`
- CI와 smoke: `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`

## 발견사항

| Tier | Review focus | 판정 | 메모 |
|---|---|---|---|
| 1 | Correctness | PASS | 테스트가 EventBridge mapping, Scheduler mapping, failure split, validation, cancellation rethrow를 다룬다. |
| 2 | Coroutine safety | PASS | broad exception handling보다 먼저 `CancellationException`을 다시 throw한다. suspend work 주변에 `runCatching`을 사용하지 않는다. |
| 3 | Security and privacy | PASS | README는 EventBridge detail payload에 raw secret과 sensitive personal data를 넣지 말라고 경고한다. |
| 4 | Operability | PASS | Smoke script와 Examples workflow는 새 non-container module과 artifact path를 포함한다. |
| 5 | Learner documentation | PASS | English/Korean README가 local-first behavior, EventBridge와 Scheduler의 책임, comparison point를 설명한다. |
| 6 | Diagram quality | PASS | Repo-local diagram QA, architecture validator, sequence validator, sequence style audit, full-size PNG visual inspection이 통과했다. |
| 7 | Maintainability | PASS | upstream wrapper가 제공될 때까지 real AWS integration은 local boundary interface 뒤에 격리되어 있다. |

## 검토 메모

이 review의 핵심은 EventBridge와 Scheduler 책임을 분리하되, learner가 기본 실행에서 외부 AWS resource나 credential을 요구받지 않도록 경계를 유지했는지 확인하는 것이다. 테스트, README, smoke lane, diagram evidence가 같은 module 범위를 가리키므로 PR 전 검증 증거로 사용할 수 있다.

## 근거

| Gate | 근거 |
|---|---|
| Targeted test | `./gradlew :aws-eventbridge-scheduler:test --no-build-cache --rerun-tasks --max-workers=1 --console=plain` -> 5 tests passed, `BUILD SUCCESSFUL`. |
| Compile | `./gradlew :aws-eventbridge-scheduler:compileKotlin :aws-eventbridge-scheduler:compileTestKotlin --warning-mode all --max-workers=1 --console=plain` -> `BUILD SUCCESSFUL`; warning은 기존 root/build-script deprecation이다. |
| AWS smoke | `./scripts/smoke-validate.sh aws` -> `BUILD SUCCESSFUL in 18s`. |
| README links | `./scripts/smoke-validate.sh stale-check` -> active modules `96 (expected: 96)`, stale ref 없음, broken image link 없음. |
| README parity | `node scripts/validate-readme-parity.mjs` -> `failures=0`. |
| README language | `node scripts/validate-readme-language.mjs` -> `offenders=0`, `totalHits=0`. |
| Workflow lint | `actionlint .github/workflows/Examples.yml` -> output 없음. |
| Diagram QA | `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/aws-eventbridge-scheduler-readme-architecture-01.svg docs/images/readme-diagrams/aws-eventbridge-scheduler-readme-sequence-01.svg` -> `PASS targets=2 weak_reference_rows=0`. |
| Architecture visual | Full-size PNG inspection: title/connector overlap 없음, card intrusion 없음, dashed/solid 의미가 in-image legend에 있음, official AWS EventBridge/Lambda icon render됨. |
| Sequence visual | Full-size PNG inspection: label은 1-7로 numbered, label은 line 위에 위치, alt body는 transparent, branch color는 서로 다름, arrowhead는 line color와 일치. |
| Whitespace | `git diff --check` -> clean. |

## 결과

P0 = 0, P1 = 0. commit metadata와 GitHub issue/PR parity check 후 PR 준비가 가능하다.
