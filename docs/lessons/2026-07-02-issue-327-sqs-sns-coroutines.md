# Issue 327 SQS/SNS Coroutine Messaging Lesson

## 배경

Issue #327에는 `bluetape4k-workshop` milestone 1.3.1을 위한 learner-facing AWS SNS/SQS
messaging example이 필요했다.

## 결정

production-shaped boundary로 bluetape4k `SnsOperations`와 `SqsOperations`를 사용하되,
default workshop path에는 conditional in-memory adapter를 제공한다. raw Jackson mapper를
다시 만들지 않고 `bluetape4k-jackson3`의 `Jackson.defaultJsonMapper`를 사용한다. default
`test` task에 Floci/Testcontainers integration test를 추가해 실제 AWS credential 없이도
example이 real bluetape4k SQS/SNS operation template을 증명하게 한다.

## 결과

module은 작은 service-first example을 통해 SNS publish request mapping, SQS polling,
handler ack, visibility-based retry, dead-letter classification, malformed payload handling,
Micrometer outcome metric, cancellation propagation을 가르친다. integration test는
`FlociServer.Launcher.floci`, `SnsCoroutinesTemplate`, `SqsCoroutinesTemplate`, Awaitility
`untilSuspending`을 사용해 local AWS-compatible endpoint에 대한 publish/consume behavior를
검증한다.

## 검증

- `:aws-sqs-sns-coroutines:test` passed with 8 tests, including
  `OrderNotificationFlociIntegrationTest`.
- `./scripts/smoke-validate.sh aws` and `./scripts/smoke-validate.sh all-smoke`
  passed.
- README parity/language, stale-check, actionlint, architecture/sequence
  validators, targeted diagram QA, full-size PNG inspection, and
  `git diff --check` passed.

## 향후 규칙

workshop example의 queue consumer에서는 malformed 또는 incompatible payload를 explicit
retry/dead-letter report로 만들어야 한다. 단, example이 의도적으로 fail-fast transport
behavior를 보여주는 경우는 예외다.

example이 bluetape4k AWS/Testcontainers dependency를 선언하면
`FlociServer.Launcher.floci`에 대해 real bluetape4k operation/template을 사용하는 integration
test를 최소 하나 포함한다. 해당 module은 non-container smoke lane이 아니라 sequential
container-backed CI lane에 둔다.
