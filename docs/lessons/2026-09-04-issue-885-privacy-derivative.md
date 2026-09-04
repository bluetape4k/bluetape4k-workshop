# Issue #885 profile image privacy-safe derivative lesson

## Context

`image-processing/profile-image-moderation`은 원본을 private object에 두고 pending
blurred/approved JPEG를 public key에 저장했지만, ImageIO 재인코딩만으로 metadata가
제거되었다고 가정하고 있었다. bluetape4k-images `2.0.0`의
`PrivacyDerivativePipeline`과 strict metadata reader를 소비자 예제로 연결했다.

## Decision or Finding

- 원본 입력을 strict metadata report로 먼저 읽고, `Failure`는 metadata 부재가 아닌
  검증 불가로 처리한다.
- pending과 approved 모두 pipeline의 JPEG writer와 output re-read verification을
  통과한 경우에만 storage에 업로드한다.
- 기본 `stripMetadata`, `removeGps`, `normalizeOrientation` 정책과 redaction geometry를
  processor adapter에 전달하고, report에는 bounded category/dimension/action만 남긴다.
- private original bytes와 기존 moderation state machine은 변경하지 않는다.

## Outcome

`ProcessedProfileImage`가 pending/approved `PrivacyDerivativeReport`를 내부적으로
관찰할 수 있게 되었고, public derivative에 EXIF/GPS/XMP/IPTC/ICC가 남지 않는지 strict
re-read로 확인한다. parser failure와 output verification failure는 public URL 이전에
fail-closed 된다.

## Verification

- profile module 전체 테스트: 27 passing
- privacy processor/service targeted tests: 5 passing
- repository `detekt`: PASS
- README language/parity, stale-check, `git diff --check`: 모두 PASS
- ecosystem reuse follow-up scope canonical SHA-256 `e22a60cbf72f97a695cbd19fb036e45278a26d8f6978521f2a6eba327211d15e`, fresh coordinator receipt
  `20260905T-issue-885-privacy-derivative-scope`
- 첫 hosted Smoke matrix는 누적 #884 테스트의 Java `is` matcher 호출이 Kotlin
  예약어 문법으로 파싱되지 않는 문제를 발견했다. Java matcher를 백틱 호출 문법으로
  수정한 뒤 OCR 42개와 profile 27개 모듈 테스트가 다시 통과했다.

## Future Guidance

새 image output을 public key에 저장할 때는 ImageIO encode 성공만으로 충분하다고 보지
말고 strict metadata re-read와 bounded report를 함께 사용한다. selective ICC 보존,
얼굴 인식 모델, 외부 DLP 연동은 writer/backend capability가 정해진 뒤 별도 이슈로
추적한다.
