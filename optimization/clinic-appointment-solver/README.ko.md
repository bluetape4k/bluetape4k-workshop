# Clinic Appointment Solver

[English](README.md) | 한국어

이 Spring Boot 참조 애플리케이션은 `ai.timefold.solver:timefold-solver-core`를
애플리케이션 내부에 임베드해 synthetic clinic appointment 일정을 최적화합니다.
provider, room, equipment, 운영 시간, 요청 slot과 Timefold가 고정하는 confirmed
appointment를 planning model로 다룹니다.

## 경계

- `demo` profile은 deterministic in-memory fixture로 로컬에서 실행됩니다. Timefold
  Platform, tenant/API key, webhook을 호출하지 않습니다.
- Solver는 `HardSoftScore`, assignment, 닫힌 reason code를 포함한 읽기 전용 proposal을
  반환합니다. 예약을 확정하거나 DB 행을 쓰거나 외부 command를 publish하지 않습니다.
- hard constraint는 provider qualification/availability, clinic·requested window,
  provider/room/equipment 호환성, resource overlap, 필수 assignment를 검사합니다.
  soft constraint는 요청 provider·slot을 우선하고 같은 날 provider 부하 집중을 줄입니다.
- fixture는 synthetic 데이터만 사용하며 PHI, EHR, 환자명, 진단, 보험, 의료 조언을
  포함하지 않습니다.
- PostgreSQL/CAS, slot hold·expiry, waitlist 전이, browser 인증, Timefold Platform
  연동은 Issue #528의 후속 범위입니다.

## 실행과 검증

```bash
./gradlew :optimization-clinic-appointment-solver:test --max-workers=1 --console=plain
./gradlew :optimization-clinic-appointment-solver:bootRun
curl -s http://127.0.0.1:8080/api/clinic-appointments/demo
```

Solver는 고정 step-count termination과 stable entity difficulty comparator를 사용하므로
같은 fixture는 같은 정렬 proposal과 score로 수렴합니다. `ConstraintVerifier` 테스트는
Docker나 외부 credential 없이 각 hard/soft rule을 검증합니다.

이 모듈은 root `bluetape4k-dependencies` BOM을 사용합니다. Timefold alias에는 버전을
기록하지 않고 BOM이 resolved version을 선택합니다. 개별 Timefold BOM이나 Bluetape
모듈 버전은 고정하지 않습니다.
