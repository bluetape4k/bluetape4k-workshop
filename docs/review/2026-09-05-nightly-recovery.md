# Nightly 2.0.0 복구 검토

## 범위와 기준

- PR #951, 기준 `75a5280102ae1de9709aeafea47151901be0fe70`.
- 공식 `bluetape4k-dependencies:2.0.0` 유지. 라이브러리 코드·개별 BOM·버전 override 변경 없음.
- 이미지 버전 계약, graph 테스트 ID, job-core 일시 정지 worker의 연결 측정, 정확한 PR 범위 등록.
- graph의 숫자 ID 수정은 PR #945 / #888에도 존재한다. 새 기능은 가져오지 않았으며 해당 PR의
  rebase 시 동일한 수정은 중복으로 적용하지 않는다.

## 설계 검토

- 독립 `architect` 검토: `CLEAR`, 설계 차단 사항 없음.
- BOM에서 checkout ref와 두 이미지 태그를 함께 결정하고 테스트 전에 실제 로컬 이미지를 검사한다.
- job-core는 Spring/Ktor와 동일하게 stale worker 전용 풀을 소유하며 executor 종료 후 풀을 닫는다.
  다른 정상 worker의 연결을 누수로 계산하지 않고 `LEASE_LOST`와 추가 기록 0 불변식은 유지한다.
- 범위 등록의 `stacked-parent-head`는 기존 검사기의 겹치는 정책 파일 등록을 위한 호환성 제약이다.
  실제 base는 `develop`이며 정확한 head/base와 파일 목록을 그대로 검사한다. 실제 stacked PR이라는
  뜻으로 사용하지 않는다. 과거 범위 전체의 상태 관리 개편은 이번 수정에 포함하지 않는다.

## 검증

- graph: 수정 전 45건 중 4건 실패, 수정 후 전체 45건 통과.
- job-core 일반 테스트: 98건 통과.
- 기존 ecosystem 검사 단위 테스트: 113건 통과.
- Nightly 이미지·범위 계약 테스트: 6건 통과. 잘못된 branch와 추가 파일은 거부한다.
- 실제 변경 경로를 넣은 ecosystem 검사, `actionlint`, `git diff --check`: 통과.
- Python 3.13 기준 `ruff check`와 `ruff format --check`: 통과. 추가 정적 검사에서 발견한
  테스트 import·중첩 context manager·dict 생성 구문을 정리하고 계약 테스트 6건을 다시 통과했다.
  해당 후속 diff는 inline으로 재검토했으며 동작·예외·외부 호출 인자 변경은 없다.
- 구현 커밋 `2408448daedcca5f904735d71d215e1e03acda6d`의 `slow-provider` 단독 실행과
  job-core 전체 local-reference 프로필 실행: 통과. 후자는 `nightly-949-all-20260905` 보고서로
  `worker-restart`를 포함하며, 종료 시 남은 토폴로지는 0이다.
- full Nightly: 최종 PR head에서 추가 검증 필요.

## 판정

- 독립 코드 리뷰는 5분 내 결과가 없어 중단했다. 부분 발견 사항은 전달되지 않았다.
  저장소의 승인된 `inline fallback review`로 주 세션이 같은 diff를 검토했다. 독립 코드 검토라고
  표기하지 않으며, 별도로 완료한 독립 설계 검토 결과는 유지한다.
- 코드 검토: `P0=0 P1=0`. catalog 값은 안정 버전 형식을 검증한 후 사용하고, Docker 호출은
  shell 보간 없이 인자 배열로 실행하며 실패를 전파한다. 전용 풀은 executor보다 바깥 `use`가
  소유한다. 기존 예외·fencing·불변식 검사는 약화하지 않았다.
- 단위 테스트·정적 검사·job-core 부하 검증은 통과했다. full Nightly는 아직 남아 있으므로 현재
  문서는 Nightly 성공이나 병합 준비 완료를 뜻하지 않는다.
