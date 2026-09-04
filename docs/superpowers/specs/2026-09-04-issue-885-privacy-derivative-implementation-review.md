# #885 privacy-safe derivative 구현 review

## 검토 범위

Issue #885의 `ProfileImageProcessor`, privacy configuration/model, service upload
경계, processor/service 회귀 테스트, 양국 README와 validation guard를 설계·수용
기준과 대조했다. 소비자 BOM은 bluetape4k-images `2.0.0`을 사용한다.

## 판정

**APPROVE — P0/P1/P2 blocker 0**

## 확인 결과

- `processPrivacySafe`는 strict source metadata reader와 immutable image decode를
  거친 뒤 approved/pending 모두 `suspendPrivacyDerivative`를 호출한다.
- `stripMetadata`, `removeGps`, `normalizeOrientation`와 bounded metadata bytes가
  configuration으로 고정되고, `PrivacyDerivativeReport`는 transient 내부 처리 결과로
  노출되며 raw metadata/payload는 저장하지 않는다.
- output verification이 성공하기 전에는 `storage.upload`와 public state 저장을
  호출하지 않는다. 기존 original key와 moderation state machine, cancellation cleanup은
  그대로 유지한다.
- normalized rectangle redaction이 pending/approved output dimensions에 맞춰 같은
  geometry 의미로 report되는지 테스트했다.
- malformed source, private original byte equality, strict output category 재읽기와 기존
  lifecycle 테스트가 포함되어 있다.

## 검증 증거

- privacy processor/service targeted tests: 5 passing
- `:image-processing-profile-image-moderation:test`: 27 passing
- `detekt`: PASS
- README language/parity, stale-check, `git diff --check`: 모두 PASS
- ecosystem follow-up scope canonical JSON SHA-256: `e22a60cbf72f97a695cbd19fb036e45278a26d8f6978521f2a6eba327211d15e`
  (fresh coordinator receipt `20260905T-issue-885-privacy-derivative-scope`)
- 첫 hosted Smoke matrix에서 누적 #884의 `status().is(422)` Kotlin parser 오류를
  확인해 backtick 호출로 교정했고, OCR/profile 모듈 재검증이 통과했다.

## 잔여 위험과 후속 범위

- Java ImageIO가 읽지 못하는 원본은 기존 validator/metadata 경계에서 거부된다. native
  codec 추가는 이 예제 범위가 아니다.
- selective ICC preservation, face model, external DLP는 별도 upstream capability
  이슈로 남긴다.
