# Issue 327 SQS/SNS Coroutine Messaging 7-Tier Review

## 범위

- Module: `aws/sqs-sns-coroutines`
- 문서: root README locale set, AWS README locale set, module README locale set
- Diagram: `aws-sqs-sns-coroutines-readme-architecture-01.*`,
  `aws-sqs-sns-coroutines-readme-sequence-01.*`
- CI와 smoke: `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`

## 발견사항

| Tier | Review focus | 판정 | 메모 |
|---|---|---|---|
| 1 | Correctness | PASS | 테스트가 SNS mapping, SQS ack, retry, dead-letter, malformed payload retry, validation, cancellation rethrow, Floci 기반 real SQS/SNS operation template를 다룬다. |
| 2 | Coroutine safety | PASS | broad exception handling보다 먼저 `CancellationException`을 다시 throw한다. suspend work 주변에 `runCatching`을 사용하지 않는다. |
| 3 | Security and privacy | PASS | 기본 path는 local adapter를 사용한다. README는 real AWS usage를 opt-in으로 유지하고 IAM/cost/cleanup을 경고한다. |
| 4 | Operability | PASS | Smoke script와 Examples workflow는 path filter, comment, artifact path와 함께 Floci 기반 module을 sequential container lane에 둔다. |
| 5 | Learner documentation | PASS | English/Korean README가 local-first boot behavior, Floci integration test, SNS와 SQS의 책임, retry/dead-letter outcome, test walkthrough를 설명한다. |
| 6 | Diagram quality | PASS | Repo-local diagram QA, architecture validator, sequence validator, sequence style audit, official AWS SNS/SQS icon, full-size PNG visual inspection이 통과했다. |
| 7 | Maintainability | PASS | Real AWS integration은 `SnsOperations`/`SqsOperations` 뒤에 유지된다. Local adapter는 conditional이며 workshop 범위로 제한된다. |

## Review Fix

첫 review pass는 P1 candidate 하나를 찾았다. malformed SQS JSON payload가 explicit report로 바뀌기 전에 `consumeOnce()` 밖으로 escape할 수 있었다. service는 이제 receive count를 기준으로 malformed payload를 retry 또는 dead-letter로 분류한다.

이후 review에서는 예제가 `bluetape4k-jackson3`, Awaitility, Floci 형태의 infrastructure를 선언했지만 실제로 사용하지 않는다는 점을 발견했다. module은 이제 `Jackson.defaultJsonMapper`를 등록하고, real bluetape4k SQS/SNS coroutine template를 통한 Floci 기반 integration test를 포함하며, sequential container CI lane에서 실행된다.

이 보정으로 문서와 실제 검증 경로가 일치한다. 기본 사용자는 local adapter로 학습하고, real AWS 경로는 명시적으로 선택한 경우에만 실행된다.

## 근거

| Gate | 근거 |
|---|---|
| Targeted compile | `./gradlew :aws-sqs-sns-coroutines:compileKotlin :aws-sqs-sns-coroutines:compileTestKotlin --warning-mode all --max-workers=1 --console=plain` -> `BUILD SUCCESSFUL`. |
| Re-run test | `./gradlew :aws-sqs-sns-coroutines:test --no-build-cache --rerun-tasks --max-workers=1 --console=plain` -> `OrderNotificationFlociIntegrationTest`를 포함해 8 tests passed, `BUILD SUCCESSFUL`. |
| AWS smoke | `./scripts/smoke-validate.sh aws` -> `BUILD SUCCESSFUL`; `aws-sqs-sns-coroutines`가 Floci integration test를 포함해 8 tests를 실행했다. |
| All smoke | `./scripts/smoke-validate.sh all-smoke` -> `BUILD SUCCESSFUL`. |
| README links | `./scripts/smoke-validate.sh stale-check` -> active modules `97 (expected: 97)`, stale ref 없음, broken image link 없음. |
| README parity | `node scripts/validate-readme-parity.mjs` -> `failures=0`. |
| README language | `node scripts/validate-readme-language.mjs` -> `offenders=0`, `totalHits=0`. |
| Workflow lint | `actionlint .github/workflows/Examples.yml` -> output 없음. |
| Diagram QA | `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/aws-sqs-sns-coroutines-readme-architecture-01.svg docs/images/readme-diagrams/aws-sqs-sns-coroutines-readme-sequence-01.svg` -> `PASS targets=2 weak_reference_rows=0`. |
| Architecture visual | Full-size PNG inspection: official SNS/SQS icon render됨, broken image placeholder 없음, card text는 centered, layer text는 명확함, connector arrowhead는 line direction 및 color와 일치. |
| Sequence visual | Full-size PNG inspection: label은 1-8로 numbered, alt area는 transparent, branch color는 서로 다름, arrowhead는 line color와 일치, label이 call line을 숨기지 않음. |
| Whitespace | `git diff --check` -> clean. |

## 결과

P0 = 0, P1 = 0. commit, PR metadata parity check, CI 준비가 가능하다.
