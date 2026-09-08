# Issue #988 Jackson3 공통 기준 7-Tier 구현 리뷰

| Tier | 판정 | 구현 근거 |
| --- | --- | --- |
| 의미·도메인 | PASS | 일반 JSON 직렬화 기준만 통일하고 strict/canonical 경계는 비범위로 유지했다. |
| 정확성·계약 | PASS | NDJSON exact output과 Spring unknown/trailing 동작을 회귀 테스트로 고정했다. |
| 수명주기·동시성 | PASS | Ktor는 공유 mapper를 읽기 전용으로 사용하고 Spring은 독립 mapper를 생성한다. |
| 보안·비밀 | PASS | permissive general mapper를 보안 경계로 주장하지 않으며 trailing token/comma 거부를 유지한다. |
| 성능·자원 | PASS | Ktor의 중복 mapper 초기화를 제거하고 Spring bean당 한 인스턴스만 생성한다. |
| 테스트·검증 | PASS | RED에서 raw mapper 차이를 재현하고 targeted/full test와 dependency graph를 검증한다. |
| 문서·운영 | PASS | 세 README와 KDoc에 공통 기준, singleton 비변경, strict/canonical 비범위를 기록했다. |

## 결론

기존 wire/input 계약을 바꾸지 않으면서 `bluetape4k-jackson3` 재사용을 높인 좁은 변경이다. 알려진 미해결 P0/P1 결함은 없다.

## 독립 리뷰 반영

- 실제 Spring HTTP converter에서 unknown property 허용과 trailing token/comma 거부를 검증했다.
- 두 outbox의 DB 저장 payload와 Kafka 전송 payload가 exact JSON 문자열로 동일함을 검증했다.
- predecessor scope의 `MERGED`는 GitHub PR merge 상태가 아니라 이 manifest의 단일 `ACTIVE` stacked-head 전이 상태다. 선행 PR이 열려 있어도 새 child head가 선행 scope를 포함하며, exact PR-scope checker가 이 전이를 검증한다.
