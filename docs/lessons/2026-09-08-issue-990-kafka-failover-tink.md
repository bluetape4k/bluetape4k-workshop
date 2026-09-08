# Issue #990 Kafka failover fingerprint를 Tink로 통일

## Context

Kafka failover codec은 caller-owned canonical JSON을 직접 SHA-256으로 계산했다.

## Decision or Finding

Canonical JSON과 strict parser는 그대로 두고 digest primitive만
`TinkDigesters.SHA256.digestHex`로 교체했다. Test는 exact JSON과 독립 JDK
oracle, 고정 fingerprint `3e723b543374486eaf7bcfc5921456b252ac5a3c187601e35f514b88a8f3c983`을
함께 검증한다.

## Outcome

Production raw SHA-256 구현을 제거하면서 field order, allowlist,
duplicate/trailing token, scalar와 size 제한을 보존했다.

## Verification

Module test, detekt, dependencyInsight, README validator, ecosystem reuse gate를
실행한다. Fingerprint는 인증 수단이 아닌 충돌 탐지 checksum이다.

## Future Guidance

Provider 재사용은 caller가 소유한 canonicalization이나 strict parsing 정책까지
대체한다는 뜻이 아니다. 공유 가능한 primitive와 domain contract를 분리한다.
