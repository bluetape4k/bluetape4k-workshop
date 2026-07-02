# Issue 327 SQS/SNS Coroutine Messaging Lesson

## Context

Issue #327 needed a learner-facing AWS SNS/SQS messaging example for
`bluetape4k-workshop` milestone 1.3.1.

## Decision

Use bluetape4k `SnsOperations` and `SqsOperations` as the production-shaped
boundary, but provide conditional in-memory adapters for the default workshop
path. Use `Jackson.defaultJsonMapper` from `bluetape4k-jackson3` instead of
rebuilding a raw Jackson mapper. Add a Floci/Testcontainers integration test for
the default `test` task so the example proves real bluetape4k SQS/SNS operation
templates without real AWS credentials.

## Outcome

The module teaches SNS publish request mapping, SQS polling, handler ack,
visibility-based retry, dead-letter classification, malformed payload handling,
Micrometer outcome metrics, and cancellation propagation through a small
service-first example. The integration test uses `FlociServer.Launcher.floci`,
`SnsCoroutinesTemplate`, `SqsCoroutinesTemplate`, and Awaitility
`untilSuspending` to verify publish and consume behavior against a local
AWS-compatible endpoint.

## Verification

- `:aws-sqs-sns-coroutines:test` passed with 8 tests, including
  `OrderNotificationFlociIntegrationTest`.
- `./scripts/smoke-validate.sh aws` and `./scripts/smoke-validate.sh all-smoke`
  passed.
- README parity/language, stale-check, actionlint, architecture/sequence
  validators, targeted diagram QA, full-size PNG inspection, and
  `git diff --check` passed.

## Future Rule

For queue consumers in workshop examples, malformed or incompatible payloads
should become explicit retry/dead-letter reports unless the example is
intentionally demonstrating fail-fast transport behavior.

When an example declares bluetape4k AWS/Testcontainers dependencies, include at
least one integration test that uses the real bluetape4k operation/template
against `FlociServer.Launcher.floci`; keep that module in the sequential
container-backed CI lane instead of the non-container smoke lane.
