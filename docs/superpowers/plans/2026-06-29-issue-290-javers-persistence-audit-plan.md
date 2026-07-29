# Issue #290 - JaVers 지속성 감사 워크숍 계획

**날짜**: 2026-06-29
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/290
**사양**: `docs/superpowers/specs/2026-06-29-issue-290-javers-persistence-audit-design.md`
**모듈**: `exposed/javers-persistence-audit` -> `:exposed-javers-persistence-audit`
**상태**: 사용자가 승인함

## T1 - TDD 빨간색

- 프로덕션 소스 앞에 모듈 빌드, 테스트 리소스 및 테스트를 추가합니다.
- Redis 지원 커밋 지속성, 기록 쿼리, 최신에 대한 잠금 동작
  스냅샷, 차이점 쿼리 및 감사 싱크 오류.
- `./gradlew :exposed-javers-persistence-audit:test`을 실행하고 실패했는지 확인합니다.
  생산 수업이 없기 때문입니다.

**DoD**: 빨간색 테스트 실패는 확인되지 않은 생산 기호로 인해 발생합니다.

## T2 - 구현

- 변경 불가능한 `Order` 집계, `OrderStatus`, `OrderTable`를 추가하고
  `OrderAuditService`.
- JaVers 인스턴스를 빌드하는 `RedisOrderAuditFactory`을 추가합니다.
  `RedissonCdoSnapshotRepository`.
- 발신자 입력에 bluetape4k 유효성 검사 도우미를 사용하세요.
- Exposed 테이블을 현재 상태 스토리지로 유지하고 Redis 지원 JaVers를 다음과 같이 유지합니다.
  스냅샷 저장소를 감사합니다.

**DoD**: 대상 모듈 테스트를 통과했습니다.

## T3 - 문서 및 다이어그램

- 백엔드 선택 지침에 따라 README.md 및 README.ko.md를 추가합니다.
  인메모리 대 Redis 대 Kafka.
- SVG+PNG 아키텍처와 쓰기 순서 흐름도를 한 번에 하나씩 생성합니다.
- official/catalog Redis, Kafka 및 데이터베이스 아이콘을 직접 사용하세요.
- `$bluetape4k-diagram` 빠른 실패 체크리스트 실행, 도우미 감사 CairoSVG
  렌더링, 밀착 시트 검사 및 전체 크기 PNG 육안 검사.

**DoD**: README 검증자가 통과하고 모든 터치된 PNG이(가) 육안으로 검사됩니다.

## T4 - 등록 및 CI

- 루트 README/README.ko 인덱스 행을 추가합니다.
- 워크플로 경로 필터 및 컨테이너 지원 레인 적용 범위를 추가합니다.
- `scripts/smoke-validate.sh` 오래된 모듈 수 및 데이터 액세스 전체 업데이트
  관련 없는 연기 사례를 늦추지 않고 적용 범위를 확장합니다.

**DoD**: `./gradlew projects`, `actionlint` 및 오래된 검사 통과.

## T5 - 검토, 커밋, PR

- 대상 compile/tests, 종속성 해결, README/diagram 유효성 검사기 실행
  `git diff --check` 및 로컬 7단계 검토.
- Lore 프로토콜로 커밋합니다.
- `debop`에 할당된 PR 생성, 마일스톤 `1.2.0`, 이슈에서 미러링된 라벨
  #290 및 마지막 `## DoD Status` 섹션입니다.

**DoD**: 실시간 PR metadata/body 확인 및 CI 확인이 통과되었습니다.
