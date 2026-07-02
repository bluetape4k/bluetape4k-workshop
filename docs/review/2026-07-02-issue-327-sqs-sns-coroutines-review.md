# Issue 327 SQS/SNS Coroutine Messaging 7-Tier Review

## Scope

- Module: `aws/sqs-sns-coroutines`
- Docs: root README locale set, AWS README locale set, module README locale set
- Diagrams: `aws-sqs-sns-coroutines-readme-architecture-01.*`,
  `aws-sqs-sns-coroutines-readme-sequence-01.*`
- CI and smoke: `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`

## Findings

| Tier | Review focus | Verdict | Notes |
|---|---|---|---|
| 1 | Correctness | PASS | Tests cover SNS mapping, SQS ack, retry, dead-letter, malformed payload retry, validation, cancellation rethrow, and Floci-backed real SQS/SNS operation templates. |
| 2 | Coroutine safety | PASS | `CancellationException` is rethrown before broad exception handling. No `runCatching` around suspend work. |
| 3 | Security and privacy | PASS | Default path uses local adapters; README keeps real AWS usage opt-in and warns about IAM/cost/cleanup. |
| 4 | Operability | PASS | Smoke script and Examples workflow keep the Floci-backed module in the sequential container lane, with path filters, comments, and artifact paths. |
| 5 | Learner documentation | PASS | English/Korean READMEs explain local-first boot behavior, Floci integration tests, SNS versus SQS responsibilities, retry/dead-letter outcomes, and test walkthroughs. |
| 6 | Diagram quality | PASS | Repo-local diagram QA, architecture validator, sequence validator, sequence style audit, official AWS SNS/SQS icons, and full-size PNG visual inspection passed. |
| 7 | Maintainability | PASS | Real AWS integration remains behind `SnsOperations`/`SqsOperations`; local adapters are conditional and scoped to the workshop. |

## Review Fix

The first review pass found one P1 candidate: malformed SQS JSON payloads could
escape `consumeOnce()` before becoming an explicit report. The service now
classifies malformed payloads as retry or dead-letter based on receive count.
A later review found that the example declared `bluetape4k-jackson3`,
Awaitility, and Floci-shaped infrastructure without exercising them. The module
now registers `Jackson.defaultJsonMapper`, includes a Floci-backed integration
test through real bluetape4k SQS/SNS coroutine templates, and runs the module in
the sequential container CI lane.

## Evidence

| Gate | Evidence |
|---|---|
| Targeted compile | `./gradlew :aws-sqs-sns-coroutines:compileKotlin :aws-sqs-sns-coroutines:compileTestKotlin --warning-mode all --max-workers=1 --console=plain` -> `BUILD SUCCESSFUL`. |
| Re-run test | `./gradlew :aws-sqs-sns-coroutines:test --no-build-cache --rerun-tasks --max-workers=1 --console=plain` -> 8 tests passed, including `OrderNotificationFlociIntegrationTest`, `BUILD SUCCESSFUL`. |
| AWS smoke | `./scripts/smoke-validate.sh aws` -> `BUILD SUCCESSFUL`; `aws-sqs-sns-coroutines` ran 8 tests including the Floci integration test. |
| All smoke | `./scripts/smoke-validate.sh all-smoke` -> `BUILD SUCCESSFUL`. |
| README links | `./scripts/smoke-validate.sh stale-check` -> active modules `97 (expected: 97)`, no stale refs, no broken image links. |
| README parity | `node scripts/validate-readme-parity.mjs` -> `failures=0`. |
| README language | `node scripts/validate-readme-language.mjs` -> `offenders=0`, `totalHits=0`. |
| Workflow lint | `actionlint .github/workflows/Examples.yml` -> no output. |
| Diagram QA | `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/aws-sqs-sns-coroutines-readme-architecture-01.svg docs/images/readme-diagrams/aws-sqs-sns-coroutines-readme-sequence-01.svg` -> `PASS targets=2 weak_reference_rows=0`. |
| Architecture visual | Full-size PNG inspection: official SNS/SQS icons render, no broken image placeholder, card text is centered, layer text is clear, connector arrowheads match line direction and color. |
| Sequence visual | Full-size PNG inspection: labels numbered 1-8, alt area is transparent, branch colors differ, arrowheads match line colors, labels do not hide call lines. |
| Whitespace | `git diff --check` -> clean. |

## Result

P0 = 0, P1 = 0. Ready for commit, PR metadata parity checks, and CI.
