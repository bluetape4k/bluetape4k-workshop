# Issue #992 Reservation fingerprint 구현 검토

## 7-Tier 검토

| 관점 | P0 | P1 | 판정 | 근거 |
| --- | ---: | ---: | --- | --- |
| architecture | 0 | 0 | PASS | production keyDigest HMAC과 request checksum 책임을 분리하고 framing 소유권을 유지한다. |
| performance | 0 | 0 | PASS | 동일 SHA-256 계산이며 성능 개선을 주장하지 않는다. |
| stability | 0 | 0 | PASS | domain, NUL separator, field order, UTF-8, lowercase 64자 golden vector를 고정한다. |
| security | 0 | 0 | PASS | raw key는 HMAC 경계를 유지하고 unkeyed request digest의 offline enumeration 위험을 기록한다. |
| operations | 0 | 0 | PASS | persisted column과 backfill, key rotation이 필요 없는 호환 전환이다. |
| developer/API | 0 | 0 | PASS | #989의 versionless alias와 공용 digest API를 재사용한다. |
| caller/user | 0 | 0 | PASS | replay/conflict 결과와 HTTP status/body 계약이 변하지 않는다. |

## 결론

P0/P1 미해결 항목은 없다. Guessable secret을 fingerprint에 추가해야 하는 미래 변경은
이 PR 범위가 아니라 keyed migration과 key lifecycle 검토가 필요하다.
