# Issue #883 ImageStorage metadata capability 소비자 예제 교훈

## 배경

기존 advanced workflow는 upload 결과의 size/content type만 영속화했다. bluetape4k
2.0.0의 `ImageObjectMetadataReader`는 `ImageStorage`의 기존 구현체를 깨뜨리지 않고
body 없는 Local `stat`와 S3 `HEAD` 기준 정보를 제공하므로, consumer가 capability를
어떻게 선택적으로 사용하는지 보여줄 필요가 있었다.

## 결정과 결과

- 업로드 직후 object별 metadata 기준 정보를 한 번 읽고 key/size를 검증한다.
- 조회한 size/content type을 response와 persistence 입력에 사용하고, ETag·수정 시각은
  해석하지 않은 opaque 값으로 upload event payload에 기록한다.
- capability가 없는 backend는 upload 결과로 fallback하며 `metadataCapability=false`와
  unavailable counter를 기록한다.
- reader 오류나 metadata 기준 정보 불일치는 이미 업로드한 object를 역순 삭제하고 job을 실패시킨다.
- `MetricImageStorageWithMetadata`를 사용하면 metrics wrapper 뒤에서도 capability를
  보존할 수 있다. metadata 경계는 `download`를 호출하지 않는다.

## 검증

- 기준선: `image-processing-advanced-workflow` 기존 테스트 `44 passing, 1 pending`.
- targeted: metadata capability, fallback, fail-closed 테스트 `7 passing`.
- 모듈 전체, README/guard, hosted CI 결과는 PR의 `## DoD Status`에 exact head 기준으로
  기록한다.

## 다음 guard

새 storage decorator를 추가할 때는 `ImageStorage`에 metadata abstract method를 직접
넣지 말고 optional capability 보존 여부를 먼저 확인한다. ETag을 content hash로
사용하거나, metadata 오류를 성공 persistence로 숨기는 fallback을 추가하지 않는다.
