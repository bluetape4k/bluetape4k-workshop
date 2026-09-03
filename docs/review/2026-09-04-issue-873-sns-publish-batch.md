# Issue #873 SNS PublishBatch 재사용 경계 검토

## 검토 범위

- 기존 `aws/sqs-sns-coroutines` 주문 알림 예제의 SNS `PublishBatch` 소비 경계
- `bluetape4k-dependencies:2.0.0` BOM과 versionless AWS alias 사용
- local emulator와 실제 AWS 요청 모델의 FIFO·부분 실패·입력 검증 계약

## 결정

예제는 `OrderNotificationMessagingService.publishBatch`를 통해 최대 10개
entry를 한 번에 전송하고, partial failure를 entry별 결과로 보존한다. FIFO
group/deduplication key와 bounded report를 테스트·README·smoke guard에
연결했으며, 실제 AWS credential과 운영 retry/DLQ 정책은 범위 밖으로 남긴다.

## 검증 증거

- module targeted/full tests와 root build/Detekt 통과
- README parity/language, stale-check, actionlint, `git diff --check` 통과
- PR #924가 `develop`을 대상으로 하며 이 scope의 expected head ref를 사용
