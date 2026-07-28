# spring-boot-idempotency 생태계 코드 리뷰

날짜: 2026-07-05
범위: `:spring-boot-idempotency`
브랜치: `refactor/spring-boot-idempotency-ecosystem-patterns`

## 범위

이 리뷰는 생태계 패턴 정리 이후 idempotency 예제를 검토한 결과다:

- `OrderRequest` 검증을 bluetape4k `requireNotBlank`와 `requirePositiveNumber`로 추가했다.
- `Idempotency-Key` 실패는 HTTP 400에 매핑한 채 `requireNotBlank`로 검증하도록 유지했다.
- 코드가 replay된 cached response를 반환하는데 동시성 loser가 409를 받는다고 적은 낡은 service KDoc을 고쳤다.
- `IdempotencyResult`를 직렬화 가능하게 만들고 nested result data class에 `serialVersionUID`를 추가했다.
- Reactor `block()` 기반 테스트 응답 추출을 typed `WebTestClient.expectBody`로 바꿨다.
- 직접 model validation 테스트를 추가했다.

## 검색 근거

| 출처 | 결과 |
|---|---|
| GNO `bluetape4k-docs` 쿼리: idempotency/Spring Boot workshop | 일반 생태계 문서만 발견했고 모듈별 선행 결정은 없었다. |
| GNO `bluetape4k-wiki` system design query | 관련 결과 없음. |
| context-mode timeline search | 일반 workspace policy만 반환했다. |
| CodeGraph `semantic_search_nodes` for module classes | 노드 매치 0개: `OrderController`, `IdempotencyService`, 및 관련 class. |
| CodeGraph `detect_changes` | staging 전 tracked 변경 파일 4개를 감지했지만 untracked `OrderModelsTest`는 graph diff 범위 밖이었다. graph는 이 slice의 함수 수준 Kotlin 영향을 제공하지 못했다. |

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
|---|---|---|
| 1 Security / input trust | PASS | header와 body 검증은 blank idempotency key/product ID/user ID와 양수가 아닌 quantity를 거부한다. Redis key 처리는 type-safe 상태를 유지한다. |
| 2 Performance / allocation | PASS | Redisson `RMapCache.putIfAbsent` 경로는 변경 없다. 검증은 local scalar check만 추가한다. |
| 3 Reliability / lifecycle | PASS | Testcontainers Redis singleton과 Redisson shutdown bean은 변경 없다. KDoc은 이제 실제 replay 동작과 맞다. |
| 4 Kotlin code quality | PASS | data/result class는 Serializable UID 규칙을 만족하고, 테스트는 직접 Reactor blocking을 피한다. |
| 5 Test coverage | PASS | 기존 HTTP idempotency 테스트와 새 model validation 테스트가 변경 동작을 커버한다. |
| 6 Ecosystem reuse | PASS | `Uuid.V7`, `Base58`, `RedisServer.Launcher.redis`, bluetape4k assertion, bluetape4k 검증 헬퍼를 계속 사용한다. |
| 7 Docs / release evidence | PASS | README는 동시성 loser가 cached response를 받는다고 이미 설명한다. 낡은 KDoc을 수정했다. |

## 검증

| 명령 | 결과 |
|---|---|
| `git diff --check` | PASS |
| `repo-status` | PASS, commit 후 working tree clean 및 upstream synced |
| `repo-diff --stat` | PASS, 없음: unstaged/index diff after commit |
| `repo-log --top 3` | PASS, feature branch의 head commit 확인 |
| `repo-test-summary -- ./gradlew :spring-boot-idempotency:test --console=plain --max-workers=1` | PASS, exit 0, `BUILD SUCCESSFUL in 4s`, Redis/Testcontainers module run serially |

## P0/P1 게이트

- P0: 0
- P1: 0
- P2/P3: deferred 없음

## DoD 상태

| 단계 | 상태 | 근거 |
|---|---|---|
| Step 0 - worktree | PASS | worktree `refactor-spring-boot-idempotency-ecosystem-patterns` from `develop` `4b72a0b1a`. |
| Step 1-R - 리서치 | PASS | GNO/context-mode를 확인했고 모듈별 선행 artifact는 없었다. |
| Step 4 - 구현 | PASS | header/body 검증, KDoc drift 수정, result 직렬화 계약, WebTestClient test 정리를 적용했다: `spring-boot/idempotency`. |
| Step 4-T - 테스트 | PASS | `repo-test-summary -- ./gradlew :spring-boot-idempotency:test --console=plain --max-workers=1` 직렬로 통과했다. |
| Step 6-R - 리뷰 | PASS | 이 리뷰에서 P0=0/P1=0을 확인했다. |
