# Issue #992 Reservation fingerprint threat model과 Tink 재사용

## Context

Reservation idempotency에는 production HMAC key digest와 unkeyed request
fingerprint가 함께 존재해 같은 보안 속성으로 오해할 수 있었다.

## Decision or Finding

Production raw idempotency key는 계속 `ReservationCredentialService`의 keyed
HMAC으로 보호한다. `IdempotencyFingerprint.request`는 owner digest가 포함된
canonical payload의 replay 충돌을 감지하는 unkeyed checksum으로 유지한다.
`IdempotencyFingerprint.key`는 test/fixture 전용이다.

## Outcome

공용 `TinkDigesters.SHA256.digestHex`를 사용하면서 domain, NUL separator, field
order, UTF-8, lowercase 64자 persisted format을 유지했다. Credential HMAC 구현과
format은 변경하지 않았다.

## Verification

기존, Unicode, 빈 field golden vector와 module test, detekt,
dependencyInsight, README parity, ecosystem reuse gate를 검증한다.

## Future Guidance

Unkeyed fingerprint에 low-entropy secret을 넣으면 저장소 유출 시 offline
enumeration이 가능하다. 그런 요구는 key lifecycle, rotation, backfill과 저장
호환성을 포함한 별도 keyed migration으로 처리한다.
