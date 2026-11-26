# MutableClock 공통 테스트 fixture 승격 설계

## 1. 문제와 목표

Workshop의 여러 주제에서 `java.time.Clock`을 상속한 mutable test clock이
반복된다. 현재 구현은 AWS Kinesis, promotion voucher, pre-generated voucher
pool, usage metering, operations job console, optimization field service에
흩어져 있으며, `instant`, `withZone`, `advance(Duration)` 또는
`advanceSeconds(Long)`의 의미가 파일마다 조금씩 다르다.

이번 단계의 목표는 공통 wall-clock 계약만 `shared/src/testFixtures`로
단일화하는 것이다. consumer가 가진 기본 시각, 기존 클래스 이름, 업무별
테스트 fixture 경계는 보존한다. 이 단계에서는 production API나
`bluetape4k-*` release artifact를 만들지 않는다.

## 2. 현재 근거

| 주제 | 현재 구현 | 차이와 보존 조건 |
| --- | --- | --- |
| AWS Kinesis | `AtomicReference<Instant>`, UTC 고정, `advanceSeconds` | 기존 호출부는 초 단위 API를 사용한다. |
| promotion voucher admission | mutable `Instant`, UTC 고정, `advance(Duration)` | 기본 시각과 테스트-local 이름을 보존한다. |
| promotion voucher reconciliation | mutable `Instant`, UTC 고정, `advance(Duration)` | `MutableClock` 이름과 `Clock` 주입 계약을 보존한다. |
| pre-generated voucher pool | mutable `Instant`, UTC 고정, `advance(Duration)` | `Instant.now()` 기본값은 consumer에서 명시적으로 유지한다. |
| usage metering | mutable `Instant` + `ZoneId`, `withZone`이 새 view 생성 | zone-aware view와 timeline을 보존한다. |
| operations job console | `AtomicReference<Instant>`, UTC 고정, `advance`가 새 시각 반환 | `JobConsoleFixtureClock` public test-fixture 이름과 default를 보존한다. |
| optimization field service | mutable `Instant`, UTC 고정, `advanceSeconds` | field-service 전용 `Clock` 주입은 유지하고 fixture만 교체한다. |
| operations idempotency | `VirtualClock`이 monotonic elapsed-time/업무 상태를 제어 | wall-clock fixture와 계약이 달라 이번 단계에서 제외한다. |

## 3. 선택지

### 선택지 A — 공통 `MutableClock` + consumer wrapper (권장)

`shared` test fixture에 thread-safe `MutableClock`을 추가하고, 기존 이름이나
기본값이 외부 테스트에 보이는 모듈은 얇은 wrapper를 유지한다. 다른 테스트는
공통 타입을 직접 사용한다.

- 장점: 시간 계산·zone view·초/Duration API의 계약을 한 곳에서 검증한다.
- 단점: 각 모듈의 중복 fixture를 제거하고 import/dependency를 조정해야 한다.

### 선택지 B — 모든 consumer를 typealias로 치환

기존 fixture 이름을 typealias로 유지한다.

- 장점: 코드가 짧다.
- 단점: JVM class/file-facade와 public test-fixture 생성자 계약을 바꾸며,
  consumer별 기본값을 표현하기 어렵다. `JobConsoleFixtureClock`에는
  적합하지 않다.

### 선택지 C — 모듈별 구현을 유지하고 shared에는 contract test만 추가

중복을 당장 제거하지 않고 동일성만 검사한다.

- 장점: 변경 위험이 가장 작다.
- 단점: 동일한 시간 계산 버그와 `withZone` 불일치를 계속 복제한다. 이번
  issue의 재사용 목표를 달성하지 못한다.

권장안은 선택지 A다. 공통 타입의 capability를 늘리지 않고 현재 소비자가
실제로 쓰는 두 advance 형태만 제공한다.

## 4. 공통 계약

새 fixture는 다음 계약을 제공한다.

```kotlin
class MutableClock(
    initial: Instant,
    zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId
    override fun withZone(zone: ZoneId): MutableClock
    override fun instant(): Instant
    fun advance(duration: Duration): Instant
    fun advanceSeconds(seconds: Long): Instant
}
```

- `Instant`는 `AtomicReference`로 보관해 concurrent test setup에서도 읽기와
  증가를 원자적으로 처리한다.
- `withZone`은 동일 timeline을 공유하는 view를 반환한다. zone view를
  만들었다고 instant가 복제되거나 고정되지 않는다.
- 음수 `Duration`은 기존 구현이 허용하므로 막지 않는다. 이 fixture는
  caller validation API가 아니라 테스트 시간 조절기다.
- monotonic elapsed-time, scheduler tick, retry budget, persistence clock
  이상의 의미를 공통 타입에 추가하지 않는다.

## 5. consumer 경계

- operations는 `JobConsoleFixtureClock` wrapper를 유지하고 shared 구현을
  상속한다. 기존 default initial instant와 반환 타입을 보존한다.
- AWS, promotion admission/reconciliation, pre-generated pool, metering,
  optimization은 private/local clock 구현을 제거하고 shared fixture를
  직접 사용한다. `MutableTestClock` 이름이 테스트 내부에만 존재하는
  경우에는 local typealias로 호출부를 단계적으로 교체한다.
- 각 consumer는 `testImplementation(testFixtures(project(":shared")))`만
  추가한다. production `implementation`이나 개별 library BOM은 변경하지
  않는다.
- `VirtualClock`, field-service planner의 `advance` helper, 업무별 retry 및
  persistence fixture는 이 단계의 대상이 아니다.

## 6. 실패 모드와 회귀 방지

1. `withZone` view가 원본 timeline을 공유하지 않으면 zone을 바꾼 뒤
   advance 결과가 달라진다. shared contract test에서 view와 원본의 instant를
   함께 검증한다.
2. `advance`가 읽기 후 쓰기로 구현되면 병렬 테스트에서 시간이 되돌아갈 수
   있다. concurrent increments를 포함한 atomic update test를 추가한다.
3. consumer의 기본 시각이나 API 이름이 사라지면 업무 테스트가 조용히 다른
   시점에서 실행된다. wrapper/default 회귀 테스트와 consumer compile/test로
   고정한다.
4. `testImplementation(project(":shared"))`만 남으면 test fixture가
   consumer compile classpath에 없다. 각 module의 resolved dependency와
   targeted compile을 확인한다.

## 7. 호환성·롤백

이번 변경은 production source와 published artifact를 건드리지 않는 test
fixture migration이다. 실패하면 consumer 파일을 원래 local clock으로
되돌리고 shared fixture dependency만 제거하면 된다. 각 모듈은 독립 commit
단위로 되돌릴 수 있도록 유지한다.

## 8. 수용 기준과 DoD

- shared `MutableClock` direct contract test가 통과한다.
- 대상 consumer의 기존 targeted tests 또는 compile proof가 통과한다.
- `withZone`, `advance(Duration)`, `advanceSeconds(Long)`, negative duration,
  concurrent update를 검증한다.
- `VirtualClock`과 업무별 clock은 변경하지 않는다.
- `git diff --check`, 변경 모듈 compile/test, dependency boundary 검사가
  통과한다.
- issue #1069에 변경 파일, 테스트 결과, 남은 gap, 다음 `WorkerPid` 단계가
  live comment로 기록된다.

## 9. Writer gate 기록

- SPW-01: 모듈별 source anchor와 현재 차이를 읽고 API/업무 경계를 고정했다.
- SPW-02: 대안, 선택 기준, 계약, failure mode, 호환성, 수용 기준을
  포함했다.
- SPW-03: Korean technical register와 기존 식별자를 유지했다.
- SPW-04: source inventory와 previous Phase A 설계/issue comment를
  대조했다.
- SPW-05: 구현 전에 전체 문서를 다시 읽고 다음 plan과의 traceability를
  확인한다.
