#!/usr/bin/env bash

# 프로세스와 worktree 간 Kafka failover fixture 실행을 직렬화합니다.
set -euo pipefail

lock_dir="${KAFKA_FAILOVER_LOCK_DIR:-/tmp/bluetape4k-kafka-failover.lock}"

usage() {
  cat <<'EOF'
Usage: with-kafka-failover-lock.sh [--] command [arg...]

Acquire the shared Kafka failover lock, run one command, and release only the
lock owned by this process. Live and stale locks fail closed; stale locks are
reported for operator disposition and are never deleted automatically.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
if [[ "${1:-}" == "--" ]]; then
  shift
fi
if [[ "$#" -eq 0 ]]; then
  usage >&2
  exit 2
fi

if ! mkdir "$lock_dir" 2>/dev/null; then
  lock_pid=""
  lock_worktree=""
  if [[ -f "$lock_dir/pid" ]]; then
    lock_pid="$(<"$lock_dir/pid")"
  fi
  if [[ -f "$lock_dir/worktree" ]]; then
    lock_worktree="$(<"$lock_dir/worktree")"
  fi
  if [[ "$lock_pid" =~ ^[0-9]+$ ]] && kill -0 "$lock_pid" 2>/dev/null; then
    echo "Kafka failover lock is held by live pid=$lock_pid worktree=${lock_worktree:-unknown}." >&2
  else
    echo "Kafka failover lock is stale or malformed; inspect $lock_dir and remove it explicitly." >&2
  fi
  exit 75
fi

lock_pid="${BASHPID:-$$}"
lock_worktree="$(git rev-parse --show-toplevel 2>/dev/null || pwd -P)"
lock_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
owner_token="${lock_pid}:${lock_started_at}"

printf '%s\n' "$owner_token" >"$lock_dir/owner"
printf '%s\n' "$lock_pid" >"$lock_dir/pid"
printf '%s\n' "$lock_worktree" >"$lock_dir/worktree"
printf '%s\n' "$lock_started_at" >"$lock_dir/started-at"

release_lock() {
  local current_owner=""
  if [[ -f "$lock_dir/owner" ]]; then
    current_owner="$(<"$lock_dir/owner")"
  fi
  if [[ "$current_owner" != "$owner_token" ]]; then
    return 0
  fi
  rm -f "$lock_dir/owner" "$lock_dir/pid" "$lock_dir/worktree" "$lock_dir/started-at"
  rmdir "$lock_dir" 2>/dev/null || true
}

trap release_lock EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "Kafka failover lock acquired: pid=$lock_pid worktree=$lock_worktree"
"$@"
