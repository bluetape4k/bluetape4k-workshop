# Issue #989 usage-billing payload digest를 Tink로 통일

## Context

다섯 usage-billing service가 같은 SHA-256 payload checksum 계약을 각각 JDK
`MessageDigest`와 `HexFormat`으로 구현하고 있었다.

## Decision or Finding

Production 생성은 `TinkDigesters.SHA256.digestHex`, 검증은 `matchesHex`로
통일했다. Wire 값은 UTF-8 payload의 lowercase 64자 hex를 유지한다. Test는
production 구현과 같은 오류를 숨기지 않도록 JDK SHA-256 oracle을 남겼다.

이 digest는 비인증 checksum이다. 적대적 변조가 threat model에 포함되면
keyed MAC 또는 signature를 별도로 사용해야 한다.

## Outcome

- 다섯 service의 raw production SHA-256 구현을 제거했다.
- schema, database, event type, exception, quarantine reason은 바꾸지 않았다.
- `bluetape4k-tink`는 versionless alias로 선언하고 중앙 BOM이 version을 정한다.

## Verification

| module | before bootJar | after dependency bootJar | delta |
| --- | ---: | ---: | ---: |
| meter | 116,836,523 | 121,807,848 | 4,971,325 |
| invoice | 116,838,761 | 121,809,926 | 4,971,165 |
| query | 116,858,675 | 121,829,872 | 4,971,197 |
| usage | 116,866,131 | 121,837,354 | 4,971,223 |
| billing | 116,882,783 | 121,853,981 | 4,971,198 |

각 증가는 5 MiB gate보다 작다. 여섯 module test, runtime/testRuntime
dependency insight, Google Tink 단일 version, `git diff --check`를 검증한다.

## Future Guidance

공용 digest provider로 전환할 때 production과 test oracle을 동시에 같은 API로
바꾸지 않는다. Canonical encoding, malformed input, footprint와 인증 경계를 함께
검증한다.
