# Issue 326 EventBridge Scheduler Lesson

## 배경

Issue #326에는 `bluetape4k-workshop` milestone 1.3.1을 위한 learner-facing
EventBridge Scheduler example이 필요했다.

## 결정

아직 release되어 사용할 수 없는 bluetape4k EventBridge/Scheduler Spring wrapper에
의존하지 않고, AWS SDK v2 `PutEventsRequestEntry`와 workshop-local boundary interface를
사용한다. default path는 local-first로 유지하고, real AWS는 같은 interface 뒤의 future
adapter로 문서화한다.

## 결과

module은 credential, LocalStack, 실제 AWS account 없이 EventBridge event envelope와 delayed
Scheduler request split을 가르친다. README pair는 이것이 local application event 및 Kafka
outbox workflow와 어떻게 다른지 설명한다.

## 검증

- `:aws-eventbridge-scheduler:test` passed with 5 tests.
- `./scripts/smoke-validate.sh aws` passed.
- README parity/language, stale-check, actionlint, and `git diff --check`
  passed.
- Diagram QA passed for the architecture and sequence SVG/PNG pairs, followed
  by full-size PNG visual inspection.

## 향후 규칙

새 untracked diagram asset을 추가할 때는 default diff detection에 의존하기 전에 명시적 SVG
path로 repo diagram QA wrapper를 실행한다. untracked file은 base-vs-HEAD target detector가
발견하지 못한다.
