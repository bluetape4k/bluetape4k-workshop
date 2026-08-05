#!/usr/bin/env bash
# Remove incomplete GraalVM reachability metadata repositories before Gradle starts.
# The native-build-tools service otherwise keeps an extracted repository without
# schemas and fails before the first metadata task can refresh it.

set -euo pipefail

gradle_home="${GRADLE_USER_HOME:-${HOME:?}/.gradle}"
repository_root="$gradle_home/native-build-tools/repositories"

if [[ ! -d "$repository_root" ]]; then
  echo "GraalVM reachability metadata cache: no existing repository cache"
  exit 0
fi

removed=0
for repository in "$repository_root"/*; do
  [[ -d "$repository" && ! -L "$repository" ]] || continue

  exploded="$repository/exploded"
  [[ -d "$exploded" ]] || continue
  [[ -d "$exploded/schemas" ]] && continue

  echo "Removing incomplete GraalVM reachability metadata repository: $repository"
  rm -rf -- "$repository"
  removed=$((removed + 1))
done

echo "GraalVM reachability metadata cache: removed $removed incomplete repository entr$( ((removed == 1)) && printf 'y' || printf 'ies' )"
