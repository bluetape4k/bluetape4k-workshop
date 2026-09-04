# Issue #876 Spring Modulith 외부화 재사용 경계 검토

## 검토 범위

- 기존 `aws/sqs-sns-coroutines` 주문 이벤트의 Spring Modulith 외부화
- `bluetape4k-dependencies:2.0.0` BOM 아래 AWS Modulith SNS·SQS bridge 사용
- event type registry, Jackson3 payload redaction, FIFO key, acknowledgement/retry 계약

## 결정

예제는 domain event를 허용된 integration DTO로 매핑한 뒤 SNS/SQS transport가
완료될 때까지 publish 결과를 반환하지 않는다. correlation id는 bounded digest로
보존하고 private field는 외부 payload에서 제거한다. 정상 소비만 delete하며
handler 실패는 visibility retry, cancellation은 예외를 보존한다. 운영 DLQ와
exactly-once 분산 보장은 범위 밖으로 남긴다.

## 검증 증거

- module targeted/full tests와 root build/Detekt 통과
- README parity/language, stale-check, actionlint, `git diff --check` 통과
- PR #927이 `develop`을 대상으로 하며 이 scope의 expected head ref를 사용
