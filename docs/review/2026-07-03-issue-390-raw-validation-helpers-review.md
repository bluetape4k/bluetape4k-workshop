# Issue 390 Raw Validation Helper Review

## 범위

- 이슈: #390 `Refactor raw validation to bluetape4k helpers`
- 작업 유형: broad validation cleanup을 포함한 Type B fast-track refactor.
- Diff 범위: AWS, Exposed, image-processing, Kotlin Flow, Ktor, leader, messaging, Spring Modulith example의 19 files.
- CodeReviewGraph: 이 worktree에서는 사용할 수 없었다(`Files: 0`, `Last updated: never`). 따라서 review는 direct diff, source scan, compile, test를 사용했다.

## Scan Evidence

- 이 issue 작업 전 baseline: `src/main` raw `require(...)` occurrences `151` in `36` files.
- refactor 후: `src/main` raw `require(...)` occurrences `111` in `32` files.
- 변경된 production file은 raw validation을 `92`에서 `52` occurrences로 줄였다.
- 남은 raw `require(...)` instance는 predicate가 단순 caller-input helper case가 아니어서 의도적으로 유지했다.
  - regex, content-type, security predicate,
  - decoded image/parser boundary check,
  - state/event identity와 stock availability 같은 domain invariant,
  - `BigDecimal` amount check 같은 exact decimal comparison,
  - Okio `require(byteCount)` API call,
  - sensitive caller input을 echo하면 안 되는 error message.

## 7-Tier Review

| Tier | 판정 | 근거 |
|---|---|---|
| Security | PASS | redaction blank text helper conversion은 helper message가 raw blank input을 echo하므로 기각했다. final diff는 non-echoing `blank-text` path를 helper conversion 밖에 유지한다. |
| Stability | PASS | ByteArray helper misuse를 고치고 production `io.bluetape4k.support` import가 있는 module에 direct `bluetape4k-core` dependency를 추가한 뒤 affected module 14개의 `compileKotlin`이 통과했다. |
| Performance | PASS | Validation helper는 inline/simple check이며 hot-loop allocation이나 blocking/runtime behavior를 바꾸지 않는다. |
| Operator/Ops | PASS | workflow/container/runtime configuration은 변경되지 않았다. Testcontainers-backed affected tests는 `--max-workers=1` 단일 Gradle invocation으로 실행했다. |
| Developer/API | PASS | Production helper import에는 이제 명시적 `implementation(libs.bluetape4k.core)` boundary가 있다. test assertion은 old exact wording에서 helper semantic wording으로만 완화했다. |
| User/Caller | PASS | OCR controller는 test가 기대하는 exact oversize HTTP detail을 보존한다. helper conversion은 user-facing message contract가 중요한 곳에서 제한된다. |
| Evidence | PASS | `git diff --check`, affected-module compile/test, post-work full local build가 통과했다. |

## 검증 근거

- clean `develop`에서 작업 전 local build: `./gradlew build --max-workers=1 --console=plain` -> `BUILD SUCCESSFUL in 9m 21s`.
- Affected compile: `./gradlew :aws-s3-vectors-access-grants:compileKotlin ... :spring-modulith-module-boundaries:compileKotlin --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 5s`.
- Affected tests: `./gradlew :aws-s3-vectors-access-grants:test ... :spring-modulith-module-boundaries:test --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 9s`.
- review fix 이후 post-work full local build: `./gradlew build --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 1m 56s`.
- Diff hygiene: `git diff --check` -> PASS.

## 발견사항

- P0/P1: 0.
- P1 repair evidence: `Order.totalAmount`는 numeric helper conversion이 아니라 exact `BigDecimal.ZERO` comparison을 사용한다. production support helper를 import하는 곳에는 transitive dependency에 의존하지 않고 direct `bluetape4k-core` dependency를 추가했다.
- P2: 기존 repository-wide Gradle deprecation warning은 이 issue 범위 밖에 남아 있다.
- P3: future cleanup은 추가 dependency-boundary 결정이 필요한 module의 남은 simple raw predicate를 다룰 수 있다. 다만 security/parser/domain predicate는 explicit하게 유지해야 한다.
