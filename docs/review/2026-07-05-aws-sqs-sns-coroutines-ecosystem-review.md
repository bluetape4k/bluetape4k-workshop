# aws-sqs-sns-coroutines 생태계 리뷰

날짜: 2026-07-05
브랜치: `refactor/aws-sqs-sns-coroutines-ecosystem-patterns`
모듈: `:aws-sqs-sns-coroutines`

## 범위

이 리뷰는 metric classification과 documentation을 bluetape4k code pattern에 맞춘 뒤 SQS/SNS coroutine workshop sample을 검토한 결과다.

영향을 받은 동작:

- publish cancellation은 `success`가 아니라 `cancelled`로 기록한다.
- handler cancellation은 다시 던지고 `acked`가 아니라 `cancelled`로 기록한다.
- handler failure는 visibility change가 성공한 뒤에만 `retry`를 기록한다.
- delete 및 visibility-change side effect 실패는 `acked` 또는 `retry`를 중복 집계하지 않고 `failure`를 기록한다.
- README file은 durable DLQ handoff와 local adapter limit을 정확히 설명한다.

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
|---|---:|---|
| Correctness | PASS | outcome metric은 이제 실제 완료된 side effect를 따른다. regression test는 publish cancellation, handler cancellation, handler retry, delete failure, visibility-change failure를 커버한다. |
| Kotlin style | PASS | cancellation은 broad failure handling 전에 다시 던진다. 수정된 test는 bluetape4k assertion을 사용하며 새 Java assertion API를 추가하지 않았다. |
| bluetape4k 생태계 재사용 | PASS | 기존 coroutine service boundary, Micrometer registry, bluetape4k assertion helper를 재사용했고 새 infrastructure나 third-party dependency를 도입하지 않았다. |
| Test coverage | PASS | targeted module test는 `cleanTest --no-build-cache` 후 11개 test를 실행했고 AWS smoke lane도 통과했다. |
| Documentation | PASS | `README.md`와 `README.ko.md`는 metric outcome, DLQ scope, local adapter limit을 명확히 설명한다. |
| Security / operations | PASS | credential, network, durable queue 의미를 확장하지 않았다. 문서는 이제 local discard가 durable DLQ handoff라고 암시하지 않는다. |
| Maintainability | PASS | metric boundary는 ack/retry/delete/visibility side effect 주변에서 명시적이며 모호한 finalizer를 피한다. |

## 발견 사항

P0: 0
P1: 0
P2: 0
P3: 0

독립 diff review에서 P0/P1/P2/P3 finding이 없었다.

## 검증

| 단계 | 상태 | 근거 |
|---|---:|---|
| Targeted compile/test | PASS | `repo-test-summary -- ./gradlew :aws-sqs-sns-coroutines:compileKotlin :aws-sqs-sns-coroutines:compileTestKotlin :aws-sqs-sns-coroutines:cleanTest :aws-sqs-sns-coroutines:test --no-build-cache --warning-mode all --console=plain --max-workers=1`가 `BUILD SUCCESSFUL`과 11개 test로 완료됐다. |
| AWS smoke lane | PASS | `MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh aws`가 `BUILD SUCCESSFUL`로 완료됐다. |
| Stale reference check | PASS | `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`가 active module 101개, stale ref 없음, 깨진 image link 없음으로 보고했다. |
| Whitespace check | PASS | `git diff --check`가 clean을 반환했다. |
| 7-Tier review | PASS | Native code-reviewer subagent가 P0/P1/P2/P3 = 0을 보고했다. |
| IDE diagnostics | NOT RUN | 이 세션에는 IntelliJ diagnostics tool이 노출되지 않았다. |

## 잔여 위험

full repository test suite는 실행하지 않았다. 변경 module과 AWS smoke lane은 직렬로 검증했다.
