# WorkerPid 공통 테스트 fixture 승격 설계

## 1. 문제와 목표

고경합 profile의 자식 프로세스가 부모에게 자신의 PID를 알릴 때 사용하는
`worker.pid` writer가 두 consumer에 중복되어 있다.

- `operations/job-console-core/src/testFixtures/.../HighContentionWorkerPid.kt`
- `commerce/concert-ticket-flash-sale/src/test/.../TicketHighContentionWorkerPid.kt`

두 구현은 property 이름, owner 검증, 신뢰할 수 있는 parent 검사, `CREATE_NEW`
생성, UTF-8 PID record, 파일 `force(true)`, directory `force(true)`의 동작이
같다. 차이는 object 이름과 KDoc, package뿐이다. 두 호출부는 각각
`JobConsoleLiveProfileRunner`와 `TicketHighContentionProfileTest` 한 곳이며,
두 consumer 모두 이미 `testFixtures(project(":shared"))`를 사용한다.

이번 단계의 목표는 이 동일한 파일 handoff 계약을 `shared` test fixture에
단일화하고 consumer별 object 이름과 호출 경계를 보존하는 것이다. production
source, BOM, version catalog, published `bluetape4k-*` artifact, PR, merge,
tag, publication은 범위에서 제외한다.

## 2. 현재 계약과 provenance

| 항목 | operations | commerce ticket | 공통 판단 |
| --- | --- | --- | --- |
| 설정 키 | `highContentionWorkerPidFile`, `highContentionProcessOwner` | 동일 | 공통 |
| owner 검증 | `requireNotBlank(PROCESS_OWNER_PROPERTY)` | 동일 | 공통 |
| parent 경계 | `Files.isDirectory(parent, NOFOLLOW_LINKS)` 및 symlink 거부 | 동일 | 공통 |
| record | `"${ProcessHandle.current().pid()}\\n".toUtf8Bytes()` | 동일 | 공통 |
| exclusive write | `FileChannel.open(path, CREATE_NEW, WRITE)` | 동일 | 공통 |
| durability | file `force(true)`, directory `force(true)`; directory provider가 지원하지 않으면 허용 | 동일 | 공통 |
| 호출 경계 | public test fixture object | test source internal object | local wrapper 유지 |

현재 source에는 이 두 구현 외에 같은 `highContentionWorkerPidFile` writer가
없다. `build-logic`은 PID 경로를 전달하고 검증하지만 writer를 직접 구현하지
않는다. 따라서 동일 계약을 두 consumer가 실제로 사용한다는 provenance는
충분하며, shared promotion 후보로 채택한다.

## 3. 선택지

### 선택지 A — shared `WorkerPidPublisher`와 consumer wrapper (권장)

`shared/src/testFixtures`에 property를 읽고 PID record를 원자적으로 생성하는
`WorkerPidPublisher`를 추가한다. 기존
`HighContentionWorkerPid`와 `TicketHighContentionWorkerPid`는 이름과 package를
유지하면서 shared writer를 위임한다.

- 장점: 실제 파일 계약은 한 곳에서 검증하고, 기존 test-fixture 호출부와
  package/API 이름은 보존한다.
- 단점: 두 개의 얇은 wrapper 파일은 남는다.

### 선택지 B — consumer 호출부를 shared object로 직접 교체

두 local object를 삭제하고 호출부가 `WorkerPidPublisher`를 직접 사용한다.

- 장점: 파일 수가 가장 적다.
- 단점: operations test fixture의 public symbol과 package 경계를 불필요하게
  바꾼다. 현재 검색에 잡히지 않는 downstream test consumer를 보호하지 못한다.

### 선택지 C — local 구현 유지, 공통 contract test만 추가

두 구현을 유지하고 동일성만 검사한다.

- 장점: migration 위험이 가장 작다.
- 단점: 다음 수정에서 두 writer가 다시 갈라질 수 있고 중복을 제거하지
  못한다.

선택지 A를 채택한다. 이 단계에서는 shared writer의 capability를 늘리지 않고
현재 consumer가 실제로 사용하는 property 기반 one-shot handoff만 옮긴다.

## 4. 공통 API와 동작 계약

```kotlin
object WorkerPidPublisher {
    fun publishIfConfigured()
}
```

동작은 다음과 같다.

1. `highContentionWorkerPidFile`이 없으면 아무것도 하지 않는다.
2. PID 경로가 설정되면 `highContentionProcessOwner`가 blank가 아닌지
   `requireNotBlank`로 검증한다.
3. 절대 경로의 parent가 존재하는 일반 디렉터리이고 symlink가 아닌지
   `NOFOLLOW_LINKS`로 확인한다. parent가 symlink이거나 디렉터리가 아니면
   `IllegalArgumentException`으로 거부한다.
4. `${ProcessHandle.current().pid()}\n`을 UTF-8 bytes로 기록한다.
5. `CREATE_NEW`로 기존 파일을 절대 교체하지 않는다. 파일이 이미 있으면
   `FileAlreadyExistsException`을 보존한다.
6. 파일 channel에 `force(true)`를 호출하고, parent directory에도
   `force(true)`를 시도한다. file provider가 directory fsync를 지원하지
   않아 `UnsupportedOperationException`이 발생하면 기존 계약대로 허용한다.

직접 contract test는 no-op, owner blank, parent symlink, exact bytes,
`CREATE_NEW` 재실행 거부를 검증한다. file/directory `force(true)`는 shared
writer의 source 계약으로 고정하고, test 결과에는 provider 차이를 명시한다.

## 5. 실패 모드와 회귀 방지

1. owner property가 없거나 blank이면 PID 파일을 만들기 전에 실패해야 한다.
   테스트는 예외와 target 미생성을 함께 확인한다.
2. parent가 symlink이면 실제 대상 디렉터리로 쓰지 않아야 한다. link 아래
   파일이 생기지 않는지 확인한다.
3. 이미 존재하는 target을 덮어쓰면 stale child의 PID를 오인할 수 있다.
   `CREATE_NEW` 재실행이 `FileAlreadyExistsException`을 내고 기존 bytes를
   보존하는지 확인한다.
4. 개행 또는 encoding이 달라지면 bounded PID reader와 process probe가
   실패한다. exact UTF-8 bytes를 비교한다.
5. directory fsync가 지원되지 않는 provider에서 정상 동작을 실패로
   처리하면 일부 CI/temporary filesystem이 깨진다. `UnsupportedOperationException`
   only fallback을 유지하고 다른 예외는 삼키지 않는다.

## 6. 호환성·롤백

이 변경은 `shared/src/testFixtures`와 consumer test/testFixtures source만
수정한다. operations의 `HighContentionWorkerPid.publishIfConfigured()` 및
commerce의 `TicketHighContentionWorkerPid.publishIfConfigured()` 호출 계약은
wrapper로 유지한다. 실패하면 두 wrapper를 기존 local implementation으로
복원하고 shared fixture와 direct contract test만 되돌리면 된다. production
classpath와 published ABI에는 영향을 주지 않는다.

## 7. 수용 기준과 DoD

- shared `WorkerPidPublisher` direct contract test가 RED 후 GREEN으로
  통과한다.
- owner blank, parent symlink, exact bytes, duplicate target rejection이
  실제 shared writer 경로에서 검증된다.
- operations와 commerce ticket wrapper가 shared writer를 사용하고,
  high-contention 호출부가 compile된다.
- operations `testFixturesClasses` 및 targeted high-contention contract/
  lifecycle tests, commerce ticket targeted high-contention contract test가
  통과한다.
- `VirtualClock`, Digesters, canonical JSON, Okio, Awaitility helper 및
  production source는 변경하지 않는다.
- `git diff --check`, dependency boundary, final receipt evidence가
  통과한다.
- Issue #1069에 exact head, 변경 파일, 테스트 결과, known gap, 다음
  upstream promotion boundary를 한국어 comment로 기록한다.

## 8. Writer gate 기록

- SPW-01: 두 실제 writer source, 두 호출부, Gradle test fixture dependency,
  Issue #1069의 후속 조건을 대조했다.
- SPW-02: 문제, provenance, 세 대안, API 계약, failure mode, 호환성,
  acceptance와 DoD를 포함했다.
- SPW-03: 한국어 기술 문체를 적용하고 code, property, class, command,
  exception 이름은 그대로 보존했다.
- SPW-04: source inventory와 Phase B issue comment를 대조해
  `VirtualClock` 및 upstream 후보를 범위에서 제외했다.
- SPW-05: 구현 전 문서를 다시 읽고 plan의 각 작업과 acceptance trace를
  확인한다.
