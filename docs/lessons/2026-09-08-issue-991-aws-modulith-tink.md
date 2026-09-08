# Issue #991 AWS Modulith correlationRef를 Tink로 통일

## Context

원문 correlation ID를 외부 envelope와 header에 노출하지 않기 위해 SHA-256의 앞
16 hex 문자를 사용하고 있었다.

## Decision or Finding

`TinkDigesters.SHA256.digestHex(value).take(16)`로 provider만 교체하고 기존
UTF-8, lowercase, 16자 계약을 유지했다. 64-bit truncation은 collision 가능성이
있으므로 인증·접근 제어·signature·유일 deduplication key로 사용할 수 없다.

## Outcome

대표 이메일은 `06c3645baad7d2fd`, Unicode 입력은 `0c40ec5d051f9c7d`로 기존과
같다. Raw correlation source는 message body와 header에 나타나지 않는다.

## Verification

Modulith fixture test, module test, detekt, dependencyInsight, README parity,
terminology audit, ecosystem reuse gate를 실행한다.

## Future Guidance

Truncated digest 길이는 wire contract다. Cardinality가 커지거나 uniqueness가
필요하면 길이만 조용히 늘리지 말고 versioned header와 compatibility를 설계한다.
