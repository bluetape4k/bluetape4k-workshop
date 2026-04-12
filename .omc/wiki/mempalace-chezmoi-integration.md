---
title: mempalace + chezmoi 통합 패턴
tags: [ mempalace, chezmoi, dotfiles, icloud, palace, sync ]
category: pattern
created: 2026-04-09
---

# mempalace + chezmoi 통합 패턴

채팅 파일은 iCloud, 설정은 chezmoi, palace 데이터는 재구축 방식으로 멀티 Mac 동기화.

## 구조

```
iCloud Drive (자동 동기화)
└── chats/                      ← 채팅 내보내기 / Claude가 직접 저장

chezmoi 관리 (~/.mempalace/)
├── config.json                 ← palace 경로, collection 이름
├── identity.txt                ← AI 신원 (L0, 직접 편집)
└── wing_config.json            ← wing 매핑 (mine 시 자동 갱신)

chezmoi 제외
└── palace/                     ← ChromaDB 바이너리, chezmoi apply 시 재구축
```

## .chezmoiignore 추가 항목

```
private_dot_mempalace/palace/
private_dot_mempalace/*.db
```

## palace 자동 재구축 (run_onchange)

`~/.local/share/chezmoi/run_onchange_10-rebuild-palace.sh.tmpl`

- 트리거: `wing_config.json`이 chezmoi git 소스에서 변경될 때
- 동작: `rm -rf palace` → `mempalace mine $CHATS_DIR --mode convos`
- 첫 설치: chezmoi 상태 DB 없으면 무조건 실행 → 새 Mac에서 자동 palace 구축

## dot 함수 통합

`~/.zshrc`의 `dot()`에 chezmoi re-add 전에 palace 재구축 로직 삽입:

```bash
# chezmoi re-add 전에 실행 → 갱신된 wing_config를 re-add가 포착
local _mp="$HOME/.local/bin/mempalace"
local _chats="$HOME/Library/Mobile Documents/com~apple~CloudDocs/chats"
local _palace="$HOME/.mempalace/palace"
if [[ -x "$_mp" && -d "$_chats" ]]; then
  if [[ ! -d "$_palace" ]] || \
     find "$_chats" -newer "$_palace" -type f \
       \( -name "*.json" -o -name "*.txt" -o -name "*.md" \) | grep -q .; then
    echo "🏛️  새 채팅 감지 → palace 재구축..."
    [[ -d "$_palace" ]] || "$_mp" init "$_chats" 2>/dev/null || true
    "$_mp" mine "$_chats" --mode convos && echo "✅ palace 재구축 완료"
  fi
fi
```

## 워크플로우

| 상황              | 명령               | 동작                             |
|-----------------|------------------|--------------------------------|
| 현재 Mac, 채팅 추가 후 | `dot`            | mine → re-add → git push       |
| 다른 Mac          | `chezmoi update` | wing_config 변경 감지 → palace 재구축 |
| 새 Mac 첫 설정      | `chezmoi apply`  | 설정 복원 + palace 자동 구축           |

## 관련 페이지

- [[mempalace-setup]]
- [[claude-code-chat-persistence]]
