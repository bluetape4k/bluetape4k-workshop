#!/usr/bin/env bash
# Remove incomplete GraalVM reachability metadata repositories before Gradle starts.
# The native-build-tools service otherwise keeps an extracted repository without
# schemas and fails before the first metadata task can refresh it. Gradle can also
# reuse a malformed repository ZIP from its dependency cache, so validate that
# source archive before the service extracts it again.

set -euo pipefail

gradle_home="${GRADLE_USER_HOME:-${HOME:?}/.gradle}"
repository_root="$gradle_home/native-build-tools/repositories"

removed=0
if [[ -d "$repository_root" ]]; then
  for repository in "$repository_root"/*; do
    [[ -d "$repository" && ! -L "$repository" ]] || continue

    exploded="$repository/exploded"
    [[ -d "$exploded" ]] || continue
    [[ -d "$exploded/schemas" ]] && continue

    echo "Removing incomplete GraalVM reachability metadata repository: $repository"
    rm -rf -- "$repository"
    removed=$((removed + 1))
  done
else
  echo "GraalVM reachability metadata cache: no existing repository cache"
fi

metadata_artifact_root="$gradle_home/caches/modules-2/files-2.1/org.graalvm.buildtools/graalvm-reachability-metadata"
removed_archives=0
if [[ -d "$metadata_artifact_root" ]]; then
  while IFS= read -r -d '' archive; do
    [[ -f "$archive" && ! -L "$archive" ]] || continue
    if unzip -Z1 "$archive" 2>/dev/null | grep -Eq '(^|/)schemas(/|$)'; then
      continue
    fi

    archive_version_dir="$(dirname "$archive")"
    echo "Removing incomplete GraalVM reachability metadata archive: $archive"
    rm -rf -- "$archive_version_dir"
    removed_archives=$((removed_archives + 1))
  done < <(find "$metadata_artifact_root" -type f -name '*-repository.zip' -print0)

  echo "GraalVM reachability metadata dependency cache: removed $removed_archives incomplete repository archive$( ((removed_archives == 1)) && printf '' || printf 's' )"
fi

if [[ -d "$repository_root" ]]; then
  echo "GraalVM reachability metadata cache: removed $removed incomplete repository entr$( ((removed == 1)) && printf 'y' || printf 'ies' )"
fi
