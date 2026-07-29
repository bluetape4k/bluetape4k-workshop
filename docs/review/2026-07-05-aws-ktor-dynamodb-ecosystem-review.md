# aws-ktor-dynamodb 생태계 코드 리뷰

날짜: 2026-07-05
브랜치: `refactor/aws-ktor-dynamodb-ecosystem-patterns`
모듈: `:aws-ktor-dynamodb`

## 범위

Ktor DynamoDB workshop sample을 bluetape4k code pattern, Kotlin style, local-first AWS 안전 경계 기준으로 검토하고 강화했다.

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
| --- | --- | --- |
| Correctness | PASS | list limit은 이제 bluetape4k `requireInRange`를 사용하고, 잘못된 page token은 repository regression test로 커버한다. |
| Stability | PASS | 기존 conditional create/update/delete 및 readiness 동작은 변경 없고, targeted test count는 26개가 되었다. |
| Security / AWS boundary | PASS | `DynamoDbKtorPlugin.autoCreateTables`는 이제 local-mode 전용이며, real AWS mode는 명시적으로 미리 생성된 table을 요구한다. |
| Performance | PASS with follow-up | bounded scan limit을 강제한다. request-size rejection은 여전히 `Content-Length`에 의존하며, local jar에는 단순한 Ktor 3.5 request-body limit plugin이 없었다. |
| 생태계 재사용 | PASS | 기존 `DynamoDbKtorPlugin`, bluetape4k DynamoDB model helper, `require*` validation, `runSuspendIO`, Base58, bluetape4k assertion을 보존했다. |
| Tests / CI | PASS | targeted compile/test, AWS smoke group, stale-check, `git diff --check`가 통과했다. |
| Documentation | PASS | README와 README.ko는 table auto-creation이 local-only이고 real AWS mode는 명시적 table creation을 요구한다고 설명한다. |

## 발견 사항

- P0: 0
- P1: 0
- Repaired P1: real AWS mode는 더 이상 DynamoDB table을 자동 생성하지 않는다.
- Repaired P2: 잘못된 `nextToken`은 `OrderSessionDynamoRepositoryTest`가 커버한다.
- Residual P2 / follow-up: 누락되거나 chunked request body는 현재 `Content-Length` guard로 제한되지 않는다.

## 검증

```bash
repo-test-summary -- ./gradlew :aws-ktor-dynamodb:compileKotlin :aws-ktor-dynamodb:compileTestKotlin :aws-ktor-dynamodb:cleanTest :aws-ktor-dynamodb:test --no-build-cache --warning-mode all --console=plain --max-workers=1
# PASS, 26개 test

MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh aws
# PASS

repo-test-summary -- ./scripts/smoke-validate.sh stale-check
# PASS, active module 101개, stale README ref 없음, 깨진 image link 없음

git diff --check
# PASS
```
