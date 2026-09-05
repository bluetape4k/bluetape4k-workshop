# #891 NFKC offset·normalization 설계 리뷰

## 판정

**KEEP** — upstream `AhoCorasickAutomaton`에 normalization 정책만 전달하고 원문 range
복원은 라이브러리에 맡기는 방식이 workshop 소비자 경계에 맞는다.

## Six-lens 사전 검토

| 관점 | 판정 | 설계 경계 |
|---|---|---|
| 기능 | PASS | NFKC compatibility expansion, 원문 range, same-length mask를 fixture로 고정한다. |
| API/호환성 | PASS | 새 option은 NFC default argument이며 기존 call site를 깨지 않는다. |
| 성능/안정성 | PASS | upstream 1,024 segment bound를 직접 통과시키고 중복 normalization 구현을 만들지 않는다. |
| 보안/운영 | PASS | raw 입력·keyword를 오류와 로그에 추가하지 않고 public mutation endpoint를 만들지 않는다. |
| Kotlin/Spring | PASS | public enum을 immutable policy/property로 전달하며 Spring enum binding을 context test로 검증한다. |
| 사용자/문서 | PASS | EN/KO README에 NFC/NFKC 선택과 `㈜` 원문 offset 예제를 동등하게 제공한다. |

## 구현 지시

- `SensitiveRedactionPolicy.toString()`에는 normalization 이름만 추가할 수 있으며 raw
  rule 값은 계속 숨긴다.
- bound 초과 테스트는 오류 type, 안전한 고정 문구와 숫자만 검사한다.
- module이 이미 versionless `text-search` alias를 직접 사용하므로 개별 version이나
  별도 BOM을 추가하지 않는다.
- workflow matrix의 기존 module job을 중복 등록하지 말고 coverage/manifest/stale
  contract만 이번 issue 범위로 갱신한다.

## 종료 기준

P0/P1 0건, targeted clean tests·detekt·README parity·stale/ecosystem/actionlint와
hosted exact-head CI가 모두 통과하면 완료한다.
