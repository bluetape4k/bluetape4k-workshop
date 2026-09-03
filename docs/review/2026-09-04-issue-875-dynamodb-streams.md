# Issue #875 DynamoDB Streams 재사용 경계 검토

## 검토 범위

- 기존 `aws/ktor-dynamodb` 주문 세션 예제의 DynamoDB Streams Flow 소비
- `bluetape4k-dependencies:2.0.0` BOM 아래 AWS Kotlin Streams alias 사용
- shard cursor, checkpoint, duplicate/retry, Ktor route와 local table fixture 계약

## 결정

예제는 shard별 Flow 소비와 명시적 checkpoint 저장을 조합하고, 재시작 시
checkpoint 이후부터 이어받는다. duplicate와 실패 재시도 결과를 bounded
report로 보존하며, local emulator 경계와 Ktor health/route를 함께 검증한다.
실제 DynamoDB Streams IAM, multi-shard resharding, 운영 checkpoint store는
범위 밖으로 남긴다.

## 검증 증거

- module targeted/full tests와 root build/Detekt 통과
- README parity/language, stale-check, actionlint, `git diff --check` 통과
- PR #926이 `develop`을 대상으로 하며 이 scope의 expected head ref를 사용
