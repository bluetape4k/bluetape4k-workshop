# Profile Image Moderation

[English](README.md) | 한국어

이 예제는 운영 환경에 가까운 사용자 프로필 이미지 흐름을 보여줍니다.

1. 사용자 프로필 이미지를 업로드합니다.
2. 원본은 private object key 아래에 저장합니다.
3. moderation이 끝나기 전에는 작은 blurred JPEG를 임시로 공개합니다.
4. 승인되면 공개 프로필 이미지로 전환하고, 반려되거나 실패하면 기본 이미지로 되돌립니다.

![Profile image moderation architecture](../../docs/images/readme-diagrams/profile-image-moderation-readme-architecture-01.png)

![Profile image moderation sequence](../../docs/images/readme-diagrams/profile-image-moderation-readme-sequence-01.png)

## Bluetape4k 기능

| 관심사 | Bluetape4k 기능 | 위치 |
|---|---|---|
| Object storage | `ImageStorage`, `ImageObjectKey`, `UploadOptions` | `ProfileImageService`, `ProfileImageKeyFactory` |
| 안전한 검증 | `require*` helper와 안정적인 ProblemDetail mapping | `UploadImageValidator`, `ProfileImageExceptionHandler` |
| 코루틴 | 제한된 background moderation runner | `ProfileImageModerationRunner` |
| Moderation policy | profile content policy adapter | `ProfileImageModerationPolicyProvider` |
| Metrics | 낮은 cardinality의 Micrometer counter/timer | `ProfileImageMetrics` |
| ID 생성 | Base58 upload id | `ProfileImageService` |

## API

앱을 실행합니다.

```bash
./gradlew :image-processing-profile-image-moderation:bootRun
```

안전한 이미지를 업로드합니다. API는 moderation 완료 전에 `202 Accepted`와 pending blurred URL을 반환합니다.

```bash
curl -i -F file=@safe.jpg http://localhost:8080/api/users/alice/profile-image
```

Pending 응답:

```json
{
  "userId": "alice",
  "status": "PENDING_MODERATION",
  "uploadId": "upload-a",
  "effectiveUrl": "http://localhost:8080/public-images/profile-images/alice/upload-a/pending/blurred.jpg",
  "pendingUrl": "http://localhost:8080/public-images/profile-images/alice/upload-a/pending/blurred.jpg",
  "approvedUrl": null,
  "defaultImageUrl": "http://localhost:8080/public-images/profile-images/default/default-profile.jpg",
  "reason": null
}
```

현재 프로필 이미지를 조회합니다.

```bash
curl http://localhost:8080/api/users/alice/profile-image
```

승인 응답:

```json
{
  "status": "APPROVED",
  "effectiveUrl": "http://localhost:8080/public-images/profile-images/alice/upload-a/public/approved.jpg"
}
```

반려된 업로드는 기본 프로필 이미지를 사용합니다. 데모 fake moderator는 파일명에 `reject`가 포함되면 반려합니다.

```bash
curl -i -F file=@reject.jpg http://localhost:8080/api/users/alice/profile-image
```

```json
{
  "status": "REJECTED",
  "effectiveUrl": "http://localhost:8080/public-images/profile-images/default/default-profile.jpg",
  "reason": "demo filename marker matched"
}
```

Moderation provider가 timeout 또는 실패를 내면 상태는 `MODERATION_FAILED`가 되고 effective URL도 기본 이미지가 됩니다. 업로드가 없는 사용자는 `NO_IMAGE`와 같은 기본 URL을 반환합니다.

잘못된 업로드는 RFC 9457 ProblemDetail JSON을 반환합니다.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "unsupported image contentType: text/plain"
}
```

## Privacy and Storage Boundaries

- `private/original/*` key는 effective URL로 반환하지 않으며 local public controller도 private path에 `404`를 반환합니다.
- `pending/blurred.jpg`는 workshop UX를 위해 의도적으로 공개하지만, 여전히 사용자 콘텐츠에서 파생된 이미지입니다. Local mode에서는 `Cache-Control: no-store`로 제공합니다.
- `public/approved.jpg`는 현재 `userId + uploadId` 상태가 승인된 뒤에만 노출합니다.
- S3/CDN 배포에서는 `profile-images/*/*/private/**` public read를 차단하고 pending cache TTL을 짧게 하거나 비활성화하세요.

## Moderation Provider

기본 provider는 cloud credential 없이 실행되는 deterministic local 구현입니다. `workshop.profile-image-moderation.decision-delay`만큼 대기하고(기본 1초), `nazi`, `rising-sun`, `hate-text`, `reject` 같은 파일명 marker를 금지된 profile-content detection으로 변환합니다. 이후 local policy가 금지된 hate symbol 또는 hate-expression text를 reject합니다. 운영 코드에서는 demo detection 단계를 [AWS Rekognition `DetectModerationLabels`](https://docs.aws.amazon.com/rekognition/latest/dg/moderation-api.html)가 제공하는 Nazi Party 같은 hate-symbol label, 필요 시 욱일기 등 로컬 정책 상징을 위한 Custom Labels model, 이미지 내 문구를 위한 OCR/text moderation으로 교체해야 합니다. 이후 dependency train에 최신 bluetape4k-image moderation policy artifact가 들어오면 이 adapter가 backend detection을 공유 policy model로 매핑하는 seam이 됩니다. 파일명 marker는 데모 전용이며 실제 안전 판정에 사용하면 안 됩니다.

## TODO

- bluetape4k-images release train에서 최신 moderation policy artifact가 배포되면 `ProfileImageModerationPolicyProvider`의 local demo policy mapping을 공유 bluetape4k-image moderation policy model 기반으로 교체합니다. S3/Rekognition/Custom Labels/OCR adapter seam과 금지 profile-content 테스트는 유지합니다.

## Operations

Health와 metrics를 확인합니다.

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics/workshop.profile.images.upload.accepted
curl http://localhost:8080/actuator/metrics/workshop.profile.images.moderation.duration
```

Local storage 기본 경로는 `${java.io.tmpdir}/bluetape4k-profile-images`입니다. Workshop object를 초기화하려면 이 디렉터리를 삭제하세요. 기본 이미지 URL은 사용자 업로드가 없어도 안정적으로 반환됩니다.

## Tests

```bash
./gradlew :image-processing-profile-image-moderation:test
```

테스트는 pending/approved/rejected/failed/no-image 상태, stale moderation completion, private URL denial, storage failure cleanup, JPEG derivative signature, pending 응답의 no-store cache, low-cardinality metrics를 검증합니다.
