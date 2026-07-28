# exposed-javers-persistence-audit 생태계 코드 리뷰

모듈: `:exposed-javers-persistence-audit`
브랜치: `refactor/exposed-javers-persistence-audit-ecosystem-patterns`
날짜: 2026-07-05

## 범위

이 문서는 Redis-backed JaVers persistence audit example을 bluetape4k validation helper, Redis test isolation, JaVers persistence boundary, regression evidence 기준으로 점검한 7-Tier review와 remediation 결과다.

## 7-Tier 결과

| Tier | 상태 | 근거 |
|---|---|---|
| API와 domain boundary | PASS | `Order.totalAmount`는 이제 bluetape4k `requireZeroOrPositiveNumber`를 사용하고, 기존 id/customer validation은 유지된다. |
| Correctness와 audit persistence | PASS | Redis-backed JaVers snapshot, Exposed current-state row, terminal delete, sink failure 동작은 계속 커버된다. |
| Redis/Testcontainers isolation | PASS | test는 전체 shared Redis DB를 flush하지 않고 `javers:workshop:order-audit:*` key만 삭제한다. |
| bluetape4k 생태계 사용 | PASS | bluetape4k JaVers Redis repository, Testcontainers launcher, assertion, validation helper를 사용한다. |
| Kotlin style과 safety | PASS | 수정된 code에는 `!!`, deprecated Exposed import, `runCatching`, raw `flushdb`, boolean assertion anti-pattern이 없다. |
| Test와 regression coverage | PASS | 기존 persistence rebuild, diff, terminal delete, audit sink failure test는 scoped cleanup 변경 후에도 통과한다. |
| Documentation과 maintainability | PASS | review artifact는 module evidence와 열린 P0/P1/P2/P3 finding이 없음을 기록한다. |

## 발견 사항

- P0: 0
- P1: 0
- P2: 0
- P3: 0

## 검증

- `repo-test-summary -- ./gradlew :exposed-javers-persistence-audit:compileKotlin :exposed-javers-persistence-audit:compileTestKotlin :exposed-javers-persistence-audit:cleanTest :exposed-javers-persistence-audit:test --no-build-cache --warning-mode all --console=plain --max-workers=1`
  - PASS, 4개 test.
- `MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh data-access-full`
  - PASS, Gradle exit 0.
- `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`
  - PASS, active module 101개, stale reference 없음, 깨진 image link 없음.
- `git diff --check`
  - PASS.
- 금지된 assertion style, deprecated Exposed import, `runCatching`, `!!`, raw `flushdb`에 대한 static scan
  - PASS, 수정된 path에서 hit 없음.
- Native code-reviewer 재리뷰
  - APPROVE, P0/P1/P2/P3 = 0.

이 Codex surface에서는 IntelliJ diagnostics를 사용할 수 없어 Gradle compile/test를 fallback diagnostics evidence로 사용했다. full data-access
smoke는 test 성공 후 Redis shutdown noise를 출력했지만 command exit code는 0이었다.
