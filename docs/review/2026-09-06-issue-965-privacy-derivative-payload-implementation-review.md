# Issue #965 PrivacyDerivativePayload 구현 검토

## 범위와 판정

- 대상: `feat/issue-965-privacy-derivative-payload` →
  `feat/issue-964-graph-record-flow-reader`
- 범위: `image-processing/profile-image-moderation`의 privacy derivative payload
  영속화 경계, processor/service 호출, 회귀 테스트, 영어·한국어 README, stale guard
- 판정: **APPROVE — P0=0, P1=0, P2=0, P3=0**

## 확인 결과

- `ProcessedProfileImage`은 pending/approved payload만 영속화하고, 실행 중
  `PrivacyDerivativeReport`는 transient 결과로 유지한다.
- `ProfileImageProcessor.processPrivacySafe`는 업로드 식별자를 opaque `sourceId`로
  전달하고 `toPayload`를 통해 안정적인 schema/kind 경계를 만든다.
- Jackson round trip과 Java serialization 회귀 테스트가 payload의 방어적 복사,
  크기 제한, malformed/trailing 입력, caller-owned stream 계약을 고정한다.
- 실패 진단에는 원본 secret이나 raw payload가 포함되지 않는다.
- root `bluetape4k-dependencies:2.0.0` BOM이 `bluetape4k-images:1.0.0`을
  해석하며 개별 module version/BOM은 추가하지 않았다.

## 검증 증거

- focused privacy payload tests: 7/7 PASS
- `:image-processing-profile-image-moderation:test`: 30/30 PASS
- root `detekt`: PASS
- `bash scripts/smoke-validate.sh stale-check`: PASS
- dependency insight: `bluetape4k-images:1.0.0` via
  `bluetape4k-dependencies:2.0.0`
- 독립 코드 리뷰: P0=0, P1=0, P2=0, P3=0

첫 hosted ecosystem reuse 검사는 제품 변경 경로가 활성 manifest scope에 없어서
`PR changed paths must map to exactly one manifest track (found 0)`로 실패했다.
이번 review artifact와 exact stacked base/head를 가진 follow-up scope, fresh
coordinator receipt를 추가해 동일한 trusted-base 검사로 재검증한다.

## 잔여 범위

- 외부 object storage, database migration, cross-service schema negotiation은 이
  in-memory workshop 예제 범위가 아니다.
- merge는 다섯 개 stacked PR의 exact-head CI와 최종 사용자 승인 뒤에만 수행한다.
