---
title: mempalace 설치 및 MCP 등록
tags: [mempalace, mcp, uv, python, memory]
category: environment
created: 2026-04-09
---

# mempalace 설치 및 MCP 등록

로컬 AI 메모리 시스템. ChromaDB 기반, 로컬 전용, 무료. 저장소: `milla-jovovich/mempalace`

## 설치

Homebrew Python은 PEP 668로 pip 차단 → `uv tool install` 사용:

```bash
uv tool install mempalace
# 실행 파일: ~/.local/bin/mempalace
# Python:   ~/.local/share/uv/tools/mempalace/bin/python3
```

## MCP 등록

```bash
# 프로젝트 로컬
claude mcp add mempalace -- \
  /Users/debop/.local/share/uv/tools/mempalace/bin/python3 -m mempalace.mcp_server

# 전역 (모든 프로젝트)
claude mcp add mempalace --scope user -- \
  /Users/debop/.local/share/uv/tools/mempalace/bin/python3 -m mempalace.mcp_server
```

## 주요 명령어

```bash
mempalace init <dir>           # 최초 초기화 (palace 디렉토리 생성)
mempalace mine <dir> --mode convos  # 대화 파일 마이닝 (wing 자동 탐지)
mempalace search "쿼리"        # 검색
mempalace wake-up              # L0+L1 컨텍스트 출력 (~170 토큰)
mempalace status               # palace 상태
```

## 알려진 이슈 (2026-04-09 기준)

| 이슈     | 설명                            | 대응                |
|--------|-------------------------------|-------------------|
| `#110` | 공식 훅 shell injection 취약점      | 공식 훅 미설치, 직접 작성   |
| `#74`  | macOS ARM64 (M-chip) segfault | ChromaDB 관련, 모니터링 |
| 버전 불일치 | PyPI 2.0.0 vs GitHub 3.0.0    | PyPI 기준으로 사용      |

## 관련 페이지

- [[mempalace-chezmoi-integration]]
- [[claude-code-chat-persistence]]
