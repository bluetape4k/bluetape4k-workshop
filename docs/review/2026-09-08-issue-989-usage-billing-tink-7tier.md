# Issue #989 usage-billing Tink digest 구현 검토

## 범위

- Billing, Invoice, Meter, Query, Usage의 payload digest 생성·검증
- 중앙 `bluetape4k-dependencies` BOM을 통한 `bluetape4k-tink` 해석
- 기존 UTF-8 lowercase 64자 hex wire contract와 예외·quarantine contract

## 7-Tier 검토

| 관점 | P0 | P1 | 판정 | 근거 |
| --- | ---: | ---: | --- | --- |
| architecture | 0 | 0 | PASS | 다섯 service가 공용 `TinkDigesters.SHA256` API를 사용하고 local DTO 소유권은 유지한다. |
| performance | 0 | 0 | PASS | bootJar 증가는 module당 4,971,165~4,971,325 bytes로 5 MiB gate 이내이며 성능 개선을 주장하지 않는다. |
| stability | 0 | 0 | PASS | 여섯 module test와 독립 JDK oracle이 기존 digest·예외·quarantine 결과를 고정한다. |
| security | 0 | 0 | PASS | checksum을 인증으로 설명하지 않으며 적대적 변조에는 MAC 또는 signature가 필요함을 명시한다. |
| operations | 0 | 0 | PASS | schema·DB migration이 없고 runtime/test dependency graph와 bootJar를 검증한다. |
| developer/API | 0 | 0 | PASS | versionless alias와 중앙 BOM만 사용하고 production raw SHA-256 중복을 제거한다. |
| caller/user | 0 | 0 | PASS | payload digest wire 값과 실패 의미가 변하지 않아 호출자 migration이 필요 없다. |

## 결론

P0/P1 미해결 항목은 없다. Google Tink가 transitively 포함되는 footprint 증가는
허용 범위 안이지만, 이 변경은 throughput 또는 latency 개선을 주장하지 않는다.
