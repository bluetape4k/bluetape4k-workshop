---
title: Claude Code 대화 자동 저장 (Stop hook)
tags: [ claude-code, hook, mempalace, icloud, chat, stop-hook ]
category: pattern
created: 2026-04-09
---

# Claude Code 대화 자동 저장 (Stop hook)

claude.ai UI를 긁지 않고 Claude Code가 직접 대화 요약을 마크다운으로 저장. Stop hook → Claude가 chats/ 에 파일 작성 → dot으로 palace 반영.

## 동작 원리

```
대화 종료 (Claude stop 시도)
  ↓
Stop hook → mempalace-save-hook.sh
  ↓ sentinel 없거나 5분 초과
저장 요청 프롬프트 출력
  ↓
Claude → chats/YYYY-MM-DD-{주제}.md 작성
  ↓
Claude 다시 stop 시도 → hook 재실행
  ↓ sentinel < 5분 → 무음 exit 0
정상 종료
```

## 루프 방지 sentinel

```bash
SENTINEL="$HOME/.mempalace/.save_hook_fired"
if [[ -f "$SENTINEL" ]]; then
    age=$(( $(date +%s) - $(stat -f %m "$SENTINEL") ))
    [[ $age -lt 300 ]] && exit 0   # 5분 이내면 무음 종료
fi
touch "$SENTINEL"
```

## 파일 위치

- 훅 스크립트: `~/.claude/hooks/mempalace-save-hook.sh`
- 저장 위치: `~/Library/Mobile Documents/com~apple~CloudDocs/chats/`
- settings.json Stop 섹션에 등록

## 저장 파일 형식

```markdown
# YYYY-MM-DD — {주제}

## 주요 결정사항 및 이유

## 설치·설정·변경한 것 (명령어 포함)

## 중요한 파일 경로

## 다음 세션에 기억해야 할 핵심 사항
```

## settings.json 설정

```json
"Stop": [{
"matcher": "",
"hooks": [
{"type": "command", "command": "CLAUDE_HOOK_EVENT=Stop bash ~/.claude/hooks/dotfiles-sync.sh"},
{"type": "command", "command": "bash ~/.claude/hooks/mempalace-save-hook.sh"}
]
}
]
```

## 주의사항

- wing 탐지는 `mempalace mine` 이 자동 처리 — 수동 wing_config 편집 불필요
- 저장 후 `dot` 실행해야 palace에 반영 (mine + git push)
- 공식 mempalace 훅(#110 shell injection)은 미사용

## 관련 페이지

- [[mempalace-setup]]
- [[mempalace-chezmoi-integration]]
