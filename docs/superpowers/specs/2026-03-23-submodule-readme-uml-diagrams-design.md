# 서브모듈 README UML 다이어그램 추가 설계

**날짜**: 2026-03-23
**상태**: 승인됨

## 목적

bluetape4k-workshop 68개 서브모듈 README에 Mermaid UML 다이어그램을 추가하여 각 모듈의 아키텍처, 도메인 모델, 데이터 흐름을 시각적으로 이해할 수 있게 한다.

## 참조 스타일

- `/Users/debop/work/bluetape4k/exposed-workshop` 서브모듈 README
- Mermaid 형식: `flowchart LR`, `classDiagram`, `sequenceDiagram`
- 레이블: **한국어**, 서브그래프 적극 활용
- 노드 수: 최소 4개 ~ 최대 15개 (복잡도 적절히 유지)

## 범위 (총 68개)

> **제외**: `src/` 경로 하위 README (6개) — `kotlin/design-patterns/src/`, `spring-data/redis-examples/src/`, `virtualthreads/rules/src/` 등은 이번 작업 대상이 아닙니다.


| 도메인 | README 수 |
|--------|-----------|
| `spring-boot/` | 12 |
| `spring-data/` | 10 |
| `exposed/` | 5 |
| `vertx/` | 4 |
| `ratelimit/` | 4 |
| `virtualthreads/` | 3 |
| `quarkus/` | 3 |
| `kotlin/` | 3 |
| `redis/` | 2 |
| `observability/` | 2 |
| `messaging/` | 2 |
| `json/` | 2 |
| `gatling/` | 2 |
| `gateway/` | 2 |
| `docker/` | 2 |
| `aws/` | 2 |
| `spring-security/` | 1 |
| `spring-modulith/` | 1 |
| `spring-cloud/` | 1 |
| `shared/` | 1 |
| `reactive/` | 1 |
| `mapping/` | 1 |
| `io/` | 1 |
| 루트 `README.md` | 1 |
| **합계** | **68** |

## 실행 전략

병렬 에이전트 7개를 동시 실행하여 도메인별 분산 처리 (각 에이전트 7~12개).

### 에이전트 할당 (균형 조정)

| 에이전트 | 담당 도메인 | README 수 |
|---------|-----------|----------|
| Agent-1 | `exposed/`(5), `spring-modulith/`(1), `spring-security/`(1) | 7 |
| Agent-2 | `spring-boot/`(12) | 12 |
| Agent-3 | `spring-data/`(10) | 10 |
| Agent-4 | `messaging/`(2), `observability/`(2), `ratelimit/`(4), `gateway/`(2), `spring-cloud/`(1) | 11 |
| Agent-5 | `vertx/`(4), `redis/`(2), `virtualthreads/`(3), `kotlin/`(3) | 12 |
| Agent-6 | `gatling/`(2), `json/`(2), `aws/`(2), `docker/`(2) | 8 |
| Agent-7 | `quarkus/`(3), `reactive/`(1), `io/`(1), `mapping/`(1), `shared/`(1), 루트(1) | 8 |
| **합계** | | **68** |

## 각 에이전트 워크플로우

1. 서브모듈 소스 코드 탐색 (`src/main/kotlin`, `build.gradle.kts`)
2. 모듈 목적 파악 → 다이어그램 타입 결정
3. Mermaid 다이어그램 작성
4. 기존 README에 섹션 추가 (내용 삭제 금지)

## 다이어그램 선택 기준

| 상황 | 다이어그램 타입 |
|------|-------------|
| 엔티티/데이터클래스 관계 있음 | `classDiagram` |
| HTTP 요청→처리→응답 흐름 | `sequenceDiagram` |
| 데이터 파이프라인/변환 흐름 | `flowchart LR` |
| 이벤트/메시지 처리 | `sequenceDiagram` + `flowchart LR` |
| 설정/인프라 중심 (docker, shared) | `flowchart LR` (컴포넌트 구성도) |
| 소스 코드 없음/극히 최소 | 아키텍처 개요 `flowchart LR` 1개만 |

## README 섹션 추가 형식

기존 내용을 유지하면서 아래 섹션을 **제목/소개 또는 첫 번째 기능 섹션 직후**에 삽입 (독자가 구조를 빠르게 파악할 수 있도록):

```markdown
## 아키텍처 흐름

```mermaid
flowchart LR
    ...
```

## 도메인 모델

```mermaid
classDiagram
    ...
```

## 요청 처리 흐름

```mermaid
sequenceDiagram
    ...
```
```

- 모듈에 적합한 타입만 선택적 추가 (모든 타입 강제 아님)
- 섹션 이름은 모듈 특성에 맞게 조정 가능
- 다이어그램 순서: 아키텍처 흐름 → 도메인 모델 → 요청 처리 흐름

## 스타일 일관성 규칙

- 레이블: **한국어** 사용 (예: `클라이언트`, `서비스`, `DB`)
- 노드 최소 4개, 최대 15개
- 서브그래프로 계층 구분 (`subgraph 레이어명`)
- 클래스 다이어그램: 주요 필드 + 관계(`-->`, `*--`, `o--`) 표시
- 시퀀스 다이어그램: 주요 참가자만 (`participant`)

## 특수 상황 처리

| 상황 | 처리 방법 |
|------|---------|
| README가 5줄 이하 | 개요 섹션 보강 후 다이어그램 추가 |
| Kotlin 소스 없음 (infra only) | 컴포넌트 구성 `flowchart LR` 1개 |
| `quarkus/` (빌드 비활성) | 다이어그램 추가하되 "빌드 비활성" 주석 유지 |
| 이미 Mermaid 다이어그램 존재 | 기존 다이어그램 아래에 누락 타입만 추가 |

## 품질 기준

- 다이어그램이 실제 코드를 정확히 반영할 것
- Mermaid 문법 오류 없을 것 (backtick 4개 중첩 주의)
- 기존 README 내용을 삭제하거나 덮어쓰지 않을 것
- 각 다이어그램은 독립적으로 이해 가능할 것
