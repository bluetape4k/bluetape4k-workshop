# Issue #967 Protobuf caller-owned ByteBuffer 구현 검토

## 범위와 판정

- 대상: `feat/issue-967-protobuf-byte-buffer` →
  `feat/issue-966-text-matches-flow`
- 범위: `spring-boot/protobuf-mvc`의 caller-owned `ByteBuffer` 직렬화 확장,
  회귀 테스트, 영어·한국어 README, stale guard
- 최종 판정: **APPROVE — P0=0, P1=0, P2=0, P3=0**

## 확인 결과

- 새 확장은 안정판 `bluetape4k-protobuf:2.0.0`의 공개
  `ProtobufSerializer.serializeTo`에 직접 위임하며 별도 codec을 만들지 않는다.
- heap/direct buffer 모두 allocating `serialize` 결과와 같은 Protobuf `Any`
  envelope bytes를 기록하고, 성공 시 기존 position부터 쓴 byte 수만큼 position을
  전진시킨다.
- `clear()` 뒤 같은 direct buffer를 반복 재사용할 수 있다.
- capacity 부족과 read-only target 실패는 원래 예외를 보존하고 호출 전 position을
  복원한다.
- README는 caller가 `flip`/`clear`와 buffer lifecycle을 소유함을 명시하고 raw
  `Message.toByteArray()`와 wire format이 다름을 경고한다.
- root `bluetape4k-dependencies:2.0.0` BOM이 versionless
  `bluetape4k-protobuf` alias를 해석하며 개별 module version/BOM은 추가하지 않았다.

## 독립 리뷰 처분

첫 리뷰는 실패 테스트가 position만 확인하고 기존 target content 보존 계약을
고정하지 않은 P2 한 건을 보고했다. overflow/read-only target을 sentinel byte로
채운 뒤 실패 전후 전체 content와 position을 함께 비교하도록 보강했다. 재리뷰
결과는 P0=0, P1=0, P2=0, P3=0이며 최종 verdict는 APPROVE다.

## 검증 증거

- focused `ProtobufByteBufferConverterTest`: 4/4 PASS
- `:spring-boot-protobuf-mvc:test`: 13/13 PASS
- root `detekt` lifecycle: PASS (`:detekt`는 `NO-SOURCE`이며 이 module에는 별도
  Detekt task가 없음)
- `bash scripts/smoke-validate.sh stale-check`: PASS
- dependency insight: `bluetape4k-protobuf:2.0.0` via
  `bluetape4k-dependencies:2.0.0`
- `git diff --check`: PASS

## 잔여 범위

- buffer capacity 선정, pooling, thread confinement은 caller 책임이다.
- benchmark나 allocation/throughput 개선 수치는 이 예제 범위에서 주장하지 않는다.
- merge는 다섯 개 stacked PR의 exact-head CI와 최종 사용자 승인 뒤에만 수행한다.
