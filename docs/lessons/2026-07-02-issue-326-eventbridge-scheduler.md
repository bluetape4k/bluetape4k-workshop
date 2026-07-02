# Issue 326 EventBridge Scheduler Lesson

## Context

Issue #326 needed a learner-facing EventBridge Scheduler example for
`bluetape4k-workshop` milestone 1.3.1.

## Decision

Use AWS SDK v2 `PutEventsRequestEntry` and workshop-local boundary interfaces
instead of depending on unavailable released bluetape4k EventBridge/Scheduler
Spring wrappers. Keep the default path local-first and document real AWS as a
future adapter behind the same interfaces.

## Outcome

The module teaches the EventBridge event envelope and delayed Scheduler request
split without requiring credentials, LocalStack, or a real AWS account. The
README pair explains how this differs from local application events and Kafka
outbox workflows.

## Verification

- `:aws-eventbridge-scheduler:test` passed with 5 tests.
- `./scripts/smoke-validate.sh aws` passed.
- README parity/language, stale-check, actionlint, and `git diff --check`
  passed.
- Diagram QA passed for the architecture and sequence SVG/PNG pairs, followed
  by full-size PNG visual inspection.

## Future Rule

When adding new untracked diagram assets, run the repo diagram QA wrapper with
explicit SVG paths before relying on default diff detection; untracked files are
not discovered by the base-vs-HEAD target detector.
