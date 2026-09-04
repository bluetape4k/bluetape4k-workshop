# #885 privacy-safe derivative 설계 review

## 판정

**KEEP WITH REVISION** — 기존 profile moderation 흐름을 보존하면서 upstream strict
privacy contract를 연결하는 범위는 적절하다. 구현 전 아래 경계를 지키면 진행한다.

## 관점별 검토

- 기능/API: 기존 `process`와 upload/state URL 계약을 깨지 않고 suspend adapter와
  내부 report만 추가한다. report에는 raw payload·source path를 넣지 않는다.
- Kotlin/동시성: `suspendPrivacyDerivative`는 서비스의 timeout/dispatcher 경계 안에서
  호출하고 `CancellationException`은 재전파한다. 기존 `NonCancellable` cleanup을
  재사용한다.
- 보안/privacy: input strict metadata failure와 output remaining category를 모두
  fail-closed로 처리한다. public URL은 두 derivative 성공 뒤에만 state에 저장한다.
- 이미지 품질: orientation normalization을 blur/resize 결과의 최종 pipeline에 맡기고,
  normalized rectangle은 output dimensions로 재계산한다. JPEG fixture로 geometry를
  확인한다.
- 테스트: metadata-bearing fixture, preserving/malformed writer, parser failure,
  original-byte equality, cancellation과 기존 lifecycle 회귀를 분리한다.
- 운영/문서: 설정 기본값과 report 의미를 양국 README와 application.yml에 같은 순서로
  기록하고, validation matrix·workflow group·stale-check를 함께 갱신한다.

## 위험과 완화

| 위험 | 완화 |
|---|---|
| strict reader가 malformed source를 EMPTY로 축약 | `ImageMetadataReadResult`를 직접 검사하고 실패 시 중단 |
| pending blur가 redaction보다 먼저 적용되어 geometry가 어긋남 | pipeline option에 동일 redaction을 전달하고 report 좌표 검증 |
| report가 privacy metadata를 재노출 | 제한된 category/dimension/action만 저장, raw field 금지 |
| 기존 테스트가 동기 processor를 직접 호출 | 기존 `process` 유지, service만 suspend 경로 사용 |
| cumulative PR gate가 변경 경로를 놓침 | #884 scope에 이어 #885 follow-up manifest를 expected head로 등록 |

## 결론

P0/P1 blocker는 없고, P2는 위 API/실패 경계 테스트와 문서 가드로 닫는다. selective
metadata preservation, 얼굴 모델, 외부 DLP는 후속 이슈로 남긴다.
