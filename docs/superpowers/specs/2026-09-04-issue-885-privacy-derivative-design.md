# #885 profile-image-moderation privacy-safe derivative 설계

## 목적

`image-processing/profile-image-moderation`의 공개 pending/approved 산출물을
`bluetape4k-images 2.0.0`의 `PrivacyDerivativePipeline`으로 다시 인코딩하고
strict metadata verification을 통과한 경우에만 업로드한다. private 원본 object와
moderation state machine은 그대로 유지하고, public derivative가 EXIF·GPS·XMP·IPTC·ICC
정보를 되살리지 못하도록 fail-closed 경계를 예제로 고정한다.

## 근거와 범위

- GitHub Issue #885와 upstream `bluetape4k-image` Issue #494/PR #495의 merged API를
  기준으로 한다.
- `PrivacyDerivativeOptions`의 기본 `stripMetadata`, `removeGps`,
  `normalizeOrientation`을 사용하고, 결과의 제한된 `PrivacyDerivativeReport`를
  처리 결과에 보존한다.
- `PrivacyRedaction`을 processor 입력으로 지원해 moderation detector가 제공한
  rectangle geometry를 resize 후에도 동일한 좌표 의미로 적용한다.
- 얼굴 인식, 원본 암호화, 외부 DLP, selective ICC 보존은 범위 밖이다.

## 처리 흐름

1. 기존 `UploadImageValidator`가 declared size/content type과 입력 bytes를 검증한다.
2. strict metadata reader로 원본 report를 읽는다. `Failure`이면 metadata 부재로
   간주하지 않고 원본 bytes를 로그하지 않은 `IllegalArgumentException`으로 중단한다.
3. `ProfileImageProcessor`가 원본 bytes를 immutable image로 읽고 픽셀/크기 제한을
   확인한다.
4. approved image는 `thumbnailSize=512x512 Fit`, pending image는 기존 blur transform
   후 `thumbnailSize=96x96 Fit`으로 각각 `suspendPrivacyDerivative`를 호출한다.
   두 결과 모두 JPEG writer의 output을 strict reader로 재검증한다.
5. detector redaction 목록은 두 derivative 옵션에 같이 전달한다. report에는 raw
   metadata나 payload가 아니라 dimensions, 적용 action, category, redaction geometry만
   남긴다.
6. 두 pipeline 결과가 모두 성공한 뒤에만 기존 `storage.upload(original/pending/approved)`
   순서를 실행한다. 어느 단계든 실패하면 기존 cleanup 경로가 업로드된 object를
   역순으로 삭제한다.

## API/호환성 결정

- `ProfileImageProcessor.process`는 기존 동기 호출을 깨지 않도록 유지하고,
  privacy pipeline을 실행하는 `suspendProcess`를 추가한다. 서비스는 suspend 경로를
  사용하며 기존 테스트 helper는 필요하면 동기 JPEG 검증만 계속 사용할 수 있다.
- `ProcessedProfileImage`에 `pendingPrivacyReport`와 `approvedPrivacyReport`를 추가해
  report를 내부 처리 결과에서 관찰할 수 있게 한다. 기본값은 `null`로 두어 기존 fixture
  생성 코드와 binary/source compatibility를 보존한다.
- `ProfileImageView`와 저장 state에는 report를 넣지 않는다. public URL 응답에 source
  식별자·parser 진단이 새어 나가지 않으며 moderation state machine의 직렬화 계약도
  변경하지 않는다.
- 기존 JPEG/PNG/WebP 입력 검증, object key, content type, URL과 cancellation 전파는
  유지한다. output은 기존처럼 `image/jpeg`이다.

## 설정

`workshop.profile-image-moderation.privacy` 아래에 다음 bounded policy를 둔다.

- `strip-metadata=true`
- `remove-gps=true`
- `normalize-orientation=true`
- `max-metadata-bytes=5242880`

`max-pixels`는 기존 upload 상한을 pipeline의 `maxPixels`로 재사용한다. 값은 양수이고
metadata byte limit은 `Int` 범위의 양수여야 한다.

## 실패·보안 경계

- metadata parser/reader failure, decode failure, output verification failure는 성공
  result나 public URL을 만들지 않는다.
- exception/log에는 raw metadata, 원본 bytes, parser message, source path를 넣지 않는다.
- pipeline cancellation은 catch 후 삼키지 않고 그대로 재전파한다. cleanup은 서비스의
  `NonCancellable` 역순 삭제를 재사용한다.
- report의 `metadataVerification.verified`가 true이고 requested category의 remaining이
  비어 있는 경우만 성공으로 간주한다.

## 수용 기준 추적

| 기준 | 증거 |
|---|---|
| EXIF/GPS/XMP/IPTC/ICC 제거 재검증 | metadata-bearing JPEG fixture + output strict report test |
| orientation/redaction geometry | orientation 6 및 normalized rectangle fixture test |
| parser failure/category 잔존 fail-closed | malformed input/writer와 preserving writer test |
| 원본 bytes/state machine 보존 | upload fixture에서 원본 equality와 기존 lifecycle test |
| BOM/versionless alias | Gradle catalog diff 없음, module dependency inspection |
| 문서/가드 | EN/KO README, matrix, workflow group, stale-check, lesson |

## 롤백

문제 발생 시 `suspendProcess` 호출과 privacy config/report field만 되돌리고, 기존
`process` 및 service upload/state 코드는 유지한다. 새 dependency나 storage schema
변경이 없으므로 rollback은 branch 단위로 국소적이다.
