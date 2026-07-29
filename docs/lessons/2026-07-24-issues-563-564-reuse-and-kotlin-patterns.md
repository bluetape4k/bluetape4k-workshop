# #563 / #564 Lessons

## 릴리스 artifact가 consumer의 기준이다

Workshop은 provider checkout이나 캐시의 snapshot이 아니라
`bluetape4k-dependencies` BOM이 실제로 해석한 artifact를 사용해야 한다. 이번에는
`bluetape4k-micrometer:1.11.0`과 `bluetape4k-graph-core:0.5.1`의 public signature를
확인해 local `observed` helper는 #561로, graph endpoint validation은 provider issue로
보냈다. 이 순서가 없으면 consumer가 아직 배포되지 않은 provider API에 결합할 수 있다.

## `shared`는 반복 코드 보관함이 아니다

HTTP extensions와 voucher black-box contract는 독립적인 module consumer와 contract
coverage가 있어 `shared`에 남긴다. 반면 Observability helper는 provider API가 이미
동등 계약을 제공하고, graph endpoint validation은 provider-level sync/suspend/virtual
thread parity가 필요하다. 재사용 후보마다 소비자 수, 계약 안정성, 릴리스 API 존재 여부를
같이 확인해야 한다.

## virtual thread lifecycle에서는 monitor를 남기지 않는다

짧은 critical section도 `synchronized`를 사용하면 virtual thread pinning 위험을 만든다.
`ReentrantLock.withLock`으로 lifecycle ownership을 명시하고, snapshot·emitter·poller의
IO는 lock 밖에 둔다. monitor 제거는 race/shutdown behavior를 보존하는 test와 함께
검증해야 한다.

## 스타일 리팩터링도 red/green 증거가 필요하다

explicit-lock과 released validation helper는 외부 API를 바꾸지 않는다. 그럼에도 각각
변경 전 실패하는 structural test를 먼저 관찰하고, 변경 뒤 focused suite와 full module
suite를 실행했다. 구조 규칙을 testable invariant으로 만들면 code review에서 쉽게
누락되는 Kotlin policy 위반을 재발 방지할 수 있다.
