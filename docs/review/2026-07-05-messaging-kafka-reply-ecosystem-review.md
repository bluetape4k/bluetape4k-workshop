# messaging-kafka-reply 생태계 리뷰

날짜: 2026-07-05
모듈: `:messaging-kafka-reply`
브랜치: `refactor/messaging-kafka-reply-ecosystem-patterns`

## 범위

- support helper 사용을 위해 direct `bluetape4k-core` dependency를 선언했다.
- ping payload와 pong request를 bluetape4k `requireNotBlank`로 검증했다.
- Guard nullable Kafka request/reply send future with bluetape4k `requireNotNull`.

## 7-Tier 리뷰

| Tier | 관점 | 판정 | 근거 |
|---|---|---|---|
| 1 | Security/input | PASS | Request payload validation rejects blank ping/pong messages before Kafka flow proceeds. |
| 2 | Architecture | PASS | request/reply flow와 topic topology는 변경 없다. |
| 3 | Messaging/coroutines | PASS | 기존 `RequestReplyFuture.await()` flow는 유지되고 nullable `sendFuture`는 이제 명시적으로 보호된다. |
| 4 | Code quality | PASS | Kotlin spacing normalized in touched classes; magic ping payload moved to a named constant. |
| 5 | Tests | PASS | `./gradlew :messaging-kafka-reply:test --console=plain --max-workers=1`가 통과했다. |
| 6 | Operations | PASS | runtime configuration, workflow, listener container setting 변경은 없다. |
| 7 | 근거/docs | PASS | `git diff --check`가 통과했고 CodeGraph review context는 low risk 및 impacted node 0개를 보고했다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- Deferred: 기존 `companion object:` spacing은 수정하지 않은 application file에 남아 있으며 이 PR slice 범위 밖이다.
