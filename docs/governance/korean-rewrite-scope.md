# 한국어 문서/주석 재작성 범위

## 목적

이 문서는 이슈 #587 하위 작업에서 공통으로 사용할 한국어 재작성 범위와
검증 방법을 정의한다. 목표는 `README*`와 운영 문서를 제외한 단일 언어 문서,
KDoc, 코드 주석, 속성 주석, 함수 인자 주석을 자연스럽고 검토 가능한 한국어로
정리하는 것이다.

## 기본 원칙

- 공개 GitHub 이슈, PR 제목/본문, commit message는 영어로 유지한다.
- `README.md`, `README.ko.md`, 모듈별 `README*`는 이번 재작성 범위에서 제외한다.
- `AGENTS.md`, `CLAUDE.md`, `SKILL.md`, prompts, workflow guidance 같은
  LLM-facing 운영 문서는 영어 유지 대상이며 제외한다.
- `docs/manual/en/**`와 `docs/manual/ko/**`처럼 언어 쌍으로 관리되는 manual은
  primary rewrite 대상이 아니며, parity 검증 대상으로만 둔다.
- `build/**`, `.gradle/**`, `.worktrees/**`, `.omx/**` 같은 생성물과 작업 상태
  디렉터리는 항상 제외한다.
- 코드 식별자, 패키지명, API 이름, Gradle task, shell command, 경로, URL, 외부
  제품명, 정확한 오류 메시지는 번역하지 않는다.
- 동작 변경은 금지한다. 이번 Epic은 문서/주석 maintenance이며 production behavior
  변경이 발견되면 별도 이슈로 분리한다.

## 문서 범위

`scripts/validate-korean-rewrite-scope.mjs inventory` 기준 초기 source-only 범위는
다음과 같다.

| 이슈 | 범위 | 초기 문서 수 |
|---|---:|---:|
| #589 | `docs/coverage-matrix.md`, `docs/assets/**`, `docs/governance/**`, `docs/images/readme-diagrams/*.md`, `docs/lessons/**`, `docs/review/**`, `docs/superpowers/**` | 389 |
| #590 | `CHANGELOG.md`, `WIP.md` | 2 |
| #591 | `commerce/**` | 0 |
| #592 | `exposed/**`, `spring-data/**`, `spring-modulith/**` | 2 |
| #593 | `spring-boot/**`, `gateway/**`, `spring-security/**`, `virtualthreads/**` | 1 |
| #594 | `kotlin/**`, `ktor/**`, `vertx/**`, `observability/**` | 0 |
| #595 | `messaging/**`, `redis/**`, `json/**`, `io/**`, `ratelimit/**` | 0 |
| #596 | `graph/**`, `leader/**` | 1 |
| #597 | `aws/**`, `image-processing/**`, `spring-cloud/**` | 1 |
| #598 | `gatling/**`, `docker/**`, `shared/**`, `build-logic/**` | 0 |

전체 primary 문서 후보는 396개다. 이 숫자는 생성물과 worktree를 제외한 source tree
기준이며, 이후 PR에서 파일이 추가되면 각 PR의 DoD에 새 inventory 결과를 기록한다.

## Kotlin 주석 범위

초기 `*.kt` source-only 주석 후보는 comment-like line 기준으로 다음과 같이 나뉜다.

| 이슈 | Kotlin 파일 수 | 주석 후보 line |
|---|---:|---:|
| #591 | 714 | 903 |
| #592 | 401 | 1,068 |
| #593 | 329 | 1,497 |
| #594 | 196 | 1,463 |
| #595 | 269 | 2,339 |
| #596 | 206 | 2,965 |
| #597 | 146 | 1,009 |
| #598 | 37 | 189 |

주석 후보 line은 `//`, `/*`, `*/`, `* ...` 패턴을 기준으로 잡은 보수적 근사치다.
최종 PR에서는 실제 diff를 확인해 의미 없는 boilerplate, license, code block,
정확한 외부 인용을 구분한다.

## 상세 주석 기준

- KDoc은 한국어 문장으로 작성하되 code identifier와 API 이름은 원문을 유지한다.
- 속성 주석은 목적, 허용 값/범위, 단위, null 가능성, 기본값, 직렬화/영속화 의미,
  동시성/트랜잭션 주의점, 실패 경계를 가능한 한 구체적으로 설명한다.
- 함수 인자 주석은 호출자 책임, 허용 값, 순서/트랜잭션 가정, 부작용, 검증 실패
  동작, async/coroutine 경계를 설명한다.
- inline comment는 코드가 명백히 말하지 않는 의도와 제약만 설명한다.
- 번역투보다 자연스러운 기술 문장을 우선한다.

## 검증 명령

```bash
node scripts/validate-korean-rewrite-scope.mjs inventory
node scripts/validate-korean-rewrite-scope.mjs manual-parity
node scripts/validate-korean-rewrite-scope.mjs changed --issue 588
node scripts/validate-korean-rewrite-scope.mjs changed --issue 589 --base docs/issue-588-korean-rewrite-inventory
git diff --check
```

각 child PR은 자기 issue 번호로 `changed --issue <number>`를 실행해야 한다. 이 명령은
현재 diff가 해당 issue의 owned path 안에 있는지, 그리고 `README*`, 운영 문서,
manual bilingual pair, 생성물이 변경되지 않았는지를 확인한다. Stacked PR에서는
반드시 직전 stack branch를 `--base`로 지정해 이전 PR의 변경분이 현재 issue의 범위
위반으로 계산되지 않게 한다.
