# Issue #740 bounded concatMapEager backpressure 계약

## Context

`kotlin/flow-extensions-race-fallback`는 dynamic `concatMapEager`로 source 순서를 보존하는 eager fallback을 설명하고 있었습니다. 그러나 unbounded overload만으로는 동시에 수집하는 inner 수와 느린 앞선 source 뒤에 쌓이는 출력량을 설명하거나 검증할 수 없었습니다.

## Decision

기존 `RaceFallbackCatalog`에 `boundedEagerFallbackBySource(sources, maxConcurrency, bufferCapacity, sourceFactory)` thin wrapper를 추가했습니다. `maxConcurrency`는 active inner 상한, `bufferCapacity`는 inner별 출력 queue 상한으로 두고, `0`은 rendezvous queue로 문서화했습니다. 새 추상화나 persistence는 도입하지 않고 bluetape4k의 bounded `concatMapEager` overload를 그대로 위임했습니다.

## Outcome

- outer/source 순서는 유지됩니다.
- active inner peak가 `maxConcurrency`를 넘지 않습니다.
- 앞선 inner가 느릴 때 뒤 inner는 자신의 queue 용량까지만 값을 누적하고 producer가 suspend됩니다.
- downstream cancellation은 active inner를 정리하고, inner failure는 예외 의미를 유지합니다.
- README와 README.ko에 bounded overload 예제, 선택 기준, 제한 사항, 영어/한국어 sequence diagram을 추가했습니다.

## Verification

- `RaceFallbackCatalogTest` 13개를 fresh Gradle 실행으로 통과시켰습니다.
- active peak, per-inner queue boundary, cancellation, invalid arguments, inner failure를 deterministic `CompletableDeferred`/`withTimeout` 테스트로 검증했습니다.
- bounded sequence semantic ledger는 5 nodes, 9 edges, 1 branch로 sequence budget 안에서 통과했습니다.
- SVG XML, connector, arrowhead, sequence-style, geometry, endpoint, mixed-corner, opaque PNG 및 asset-pair 검사를 수행했습니다.

## Future Guidance

eager operator를 선택할 때는 source order만 확인하지 말고 active inner 수와 inner별 queue capacity를 함께 정해야 합니다. `bufferCapacity=0`은 가장 강한 backpressure인 rendezvous 계약이므로, producer가 suspend되는 지점을 `sleep` 기반 테스트 대신 `CompletableDeferred`로 관찰하세요. bounded operator도 전역 순서나 exactly-once 처리까지 보장하는 것은 아니므로 해당 요구는 별도 계약으로 남겨야 합니다.
