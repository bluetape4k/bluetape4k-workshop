# Issue #883 ImageStorage metadata capability 소비자 예제 설계

- Issue: [#883](https://github.com/bluetape4k/bluetape4k-workshop/issues/883)
- Branch: `feat/issue-883-image-storage-metadata`
- 대상 독자: `ImageStorage`를 사용하는 Spring Boot 이미지 처리 개발자
- 문서 언어: 한국어 (코드·API 이름·명령은 원문 유지)

## 목표

`image-processing/advanced-workflow`가 원본과 파생 이미지를 업로드한 뒤
`ImageObjectMetadataReader` capability를 탐색하고, body를 다시 다운로드하지 않고
provider-neutral metadata를 기록하는 경계를 보여준다. metadata를 제공하는 Local/S3
backend에서는 한 번의 `stat`/`HEAD` 기준 정보를 사용하고, capability가 없는 기존
구현체는 upload 결과로 계속 동작해야 한다.

## 근거와 현재 경계

| 근거 | 확인한 사실 | 설계 결정 |
| --- | --- | --- |
| upstream `ImageStorage.kt` | `readMetadata`는 optional capability이며 `ImageStorage` abstract API에 추가되지 않음 | consumer는 `storage as? ImageObjectMetadataReader`로만 탐색 |
| upstream `ImageObjectMetadata.kt` | size는 필수, ETag·content type·lastModified는 nullable이며 ETag은 opaque | size/content type은 기준 정보 우선, 나머지는 event payload에 원문 의미를 보존 |
| upstream `LocalImageStorage`·`S3ImageStorage` | Local은 secure attributes, S3는 단일 `HEAD` 기준 정보를 사용 | workshop은 backend를 재구현하지 않고 호출·fallback 경계만 검증 |
| 현재 `ImageDerivativeWorkflowService` | upload 결과의 size/content type으로 persistence 입력을 만듦 | 업로드 직후 metadata를 한 번 읽어 동일 입력을 정규화 |
| 현재 테스트 `RecordingImageStorage` | 외부 구현체처럼 optional capability가 없음 | 기존 fixture가 그대로 성공하는 회귀 테스트 유지 |

## 선택지

### A — 업로드 후 metadata 기준 정보 정규화 (권고)

각 object upload 직후 capability가 있으면 `readMetadata`를 한 번 호출하고 key/size를
검증한다. 기준 정보 오류나 불일치는 cleanup 경로로 보내며, 미지원 backend는 upload
결과를 사용한다. 기존 workflow와 ABI를 가장 작게 확장하면서 metadata 사용 지점을
reader가 관찰할 수 있다.

### B — API 응답에 ETag·수정 시각 추가

외부 응답 모델을 확장하면 값이 보이지만 DB schema, cached response, API 문서와
호환성 범위가 커진다. 이 issue의 목적은 storage capability 소비 예제이지 public API
변경이 아니므로 거부한다.

### C — upload 전에 metadata 조회

새 object가 아직 존재하지 않아 정상적인 Local/S3 `stat`/`HEAD`를 수행할 수 없다.
존재하지 않는 object를 fallback으로 숨기는 경계가 되어 거부한다.

## 계약과 흐름

```text
upload(key, bytes)
  ├─ capability 있음  -> readMetadata(key) 1회
  │    ├─ key/size 일치 -> 기준 정보 size/contentType 사용
  │    └─ 오류/불일치   -> 이미 업로드한 object cleanup + job 실패
  └─ capability 없음  -> upload result 사용 + metadataCapability=false
        └─ S3_UPLOAD event에 available/size/contentType/etag/lastModified 기록
```

- `ImageStorage`의 기존 구현체와 source/ABI 호환성을 유지한다.
- metadata reader의 `CancellationException`과 provider 예외를 삼키지 않는다.
- ETag은 hash나 MD5로 해석하지 않고 opaque 문자열로만 기록한다.
- metrics decorator는 `MetricImageStorageWithMetadata`로 capability를 보존한다.
- metadata를 위해 `download`를 호출하지 않는다.

## 실패 모드

| 실패 모드 | 관찰 가능한 결과 | 대응 |
| --- | --- | --- |
| reader가 `NotFound`/transport 오류 반환 | S3 upload event와 성공 persistence가 생기지 않음 | 기존 업로드 object를 역순으로 삭제하고 job을 실패시킴 |
| metadata key 또는 size가 upload 결과와 불일치 | 부분 object가 성공으로 저장되지 않음 | `require` 실패를 saga 예외로 전파하고 cleanup 수행 |
| backend가 capability를 제공하지 않음 | `metadataCapability=false`, 기존 upload size/content type 사용 | reader cast를 반복하지 않고 명시적 fallback counter 기록 |
| metrics wrapper가 capability를 잃음 | wrapper에서 metadata reader를 찾을 수 없음 | `MetricImageStorageWithMetadata`를 사용하고 capability read를 한 번만 수행 |

## 수용 기준과 DoD

- [ ] capability 구현체에서 object마다 `readMetadata`를 정확히 한 번 호출하고 body
  download는 호출하지 않는다.
- [ ] metadata 기준 정보의 size/content type을 persistence/response에 사용하고 ETag·수정 시각을
  opaque event payload로 기록한다.
- [ ] capability가 없는 기존 `RecordingImageStorage`가 fallback으로 계속 통과한다.
- [ ] reader 오류와 key/size 불일치가 cleanup과 실패 상태로 이어진다.
- [ ] README 양국, root coverage matrix, Examples workflow, smoke stale-check와 lesson을
  같은 변경에서 갱신한다.
- [ ] `bluetape4k-dependencies:2.0.0` BOM과 versionless image aliases만 사용하며
  개별 bluetape 버전을 추가하지 않는다.
