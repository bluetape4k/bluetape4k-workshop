# Issue #953 Okio BufferedSuspendedSink 공개 API 이전 설계

## 목표

`io/okio-examples`가 같은 package에 복제한 `BufferedSuspendedSink`, `RealBufferedSuspendedSink`,
`SuspendedSink.buffered()`를 제거하고 `bluetape4k-dependencies:2.0.0`이 관리하는
`bluetape4k-okio:2.0.0` 공개 API를 직접 사용한다.

## 확인된 계약

- `io/okio-examples/build.gradle.kts`는 이미 versionless `libs.bluetape4k.okio`를 사용한다.
- 배포 JAR에는 public `BufferedSuspendedSink`와 `SuspendedSink.buffered()`가 있고 구현 class는 consumer에서
  직접 생성하지 않는다.
- stable 구현은 `write(SuspendedSource, byteCount)`와 `writeAll(SuspendedSource)`에서 연속 0-byte read를
  8회로 제한하고 `IOException`을 던진다.
- `close()` 중 buffered write와 underlying close가 모두 실패하면 close를 시도한 뒤 최초 write 오류를 유지한다.
  stable JAR은 close 오류를 suppressed에 추가하지 않으므로 workshop도 그 동작을 주장하지 않는다.
- local `SuspendedSink`/`SuspendedSource`는 provider interface와 동일 FQCN을 shadowing하지만 이 이슈가 사용하는
  method ABI는 호환된다. source-side 전체 이전은 별도 범위다.

## 선택한 구조

1. local buffered sink interface와 implementation 두 파일을 삭제한다.
2. 모든 consumer/test는 `fakeSink.buffered()`를 통해 provider 공개 API를 받는다.
3. exact payload, 모든 write overload, complete segment/tail, flush와 idempotent close를 회귀로 고정한다.
4. delayed no-progress source로 무한 loop를 timeout 실패로 재현한 뒤 provider의 bounded `IOException`과 8회 read를
   검증한다.
5. 실패 fake sink로 buffered write failure가 최초 원인으로 유지되고 underlying close가 한 번 시도되는지 검증한다.
6. Socket/FileChannel 기존 통합 테스트를 함께 실행해 local adapter와 provider buffered sink ABI 호환을 확인한다.

## CI와 문서

- `.github/workflows/Examples.yml`의 push/pull request path에 `io/okio-examples/**`를 추가한다.
- credential-free smoke job에 `:okio-examples:test`를 추가하고 comment/report artifact 분류를 맞춘다.
- `scripts/smoke-validate.sh` stale guard는 local buffered duplicate 부재, public `.buffered()` 사용, no-progress와
  최초 오류 회귀, stable 2.0.0, 양 언어 README, lesson, review artifact와 manifest를 확인한다.
- coverage matrix의 Async/Reactive에 `bluetape4k-okio` 행을 추가한다.
- module README는 dependency가 BOM 관리 stable 2.0.0이고 source/sink lifecycle은 caller-owned임을 설명한다.

## 제외 범위

- local `SuspendedSource`, `SuspendedSink`, buffered source 계층 전체 제거
- upstream `close()` suppression 동작 변경
- 새 dependency, codec, storage adapter 또는 2.1.0 snapshot 사용

## 중단 조건

- 배포 artifact와 source tag의 공개 API/bytecode가 다르면 삭제를 중단한다.
- local adapter와 provider public API가 compile 또는 socket/file-channel 테스트에서 호환되지 않으면 범위를 넓히지
  않고 blocker를 기록한다.
- no-progress와 close failure 계약을 public API만으로 증명하지 못하면 완료하지 않는다.
