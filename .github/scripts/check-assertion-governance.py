#!/usr/bin/env python3
"""Guard consumer Kotlin tests against legacy and ambiguous assertions."""

from __future__ import annotations

import argparse
import importlib.util
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List


LEGACY_IMPORT_RE = re.compile(
    r"(?m)^[ \t]*import[ \t]+"
    r"(?:kotlin\.test\.(?:assert[A-Za-z0-9_]*|fail)\b"
    r"|org\.junit(?:\.jupiter\.api)?\.Assertions(?:\.[A-Za-z0-9_*]+)?"
    r"|org\.junit\.Assert(?:\.[A-Za-z0-9_*]+)?)"
)
GENERIC_BOOLEAN_OR_NULL_RE = re.compile(
    r"\bshouldBeEqualTo\s*\(\s*(?P<value>true|false|null)\s*\)"
)
TEST_SOURCE_DIRECTORIES = {"test", "testFixtures"}
IGNORED_DIRECTORIES = {".git", ".gradle", "build", ".worktrees"}


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    rule: str
    detail: str


@dataclass(frozen=True)
class ScanResult:
    findings: List[Finding]
    scanned_files: int
    allowlisted_legacy_imports: int


_CHECKER = None


def _checker_mask(source: str) -> str:
    """Reuse the ecosystem gate's literal/comment masking contract."""
    global _CHECKER
    if _CHECKER is None:
        checker_path = Path(__file__).with_name("check-ecosystem-reuse.py")
        spec = importlib.util.spec_from_file_location("check_ecosystem_reuse_for_assertions", checker_path)
        if spec is None or spec.loader is None:
            raise RuntimeError("ecosystem checker masking helper is unavailable")
        checker = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = checker
        spec.loader.exec_module(checker)
        _CHECKER = checker
    return _CHECKER._strip_comments_and_strings(source)


def _is_test_source(path: Path) -> bool:
    parts = path.parts
    return any(
        parts[index] == "src" and index + 1 < len(parts) and parts[index + 1] in TEST_SOURCE_DIRECTORIES
        for index in range(len(parts) - 1)
    )


def _iter_test_files(root: Path) -> Iterable[Path]:
    for path in root.rglob("*.kt"):
        relative = path.relative_to(root)
        if any(part in IGNORED_DIRECTORIES for part in relative.parts):
            continue
        if _is_test_source(relative):
            yield path


def _is_build_logic_allowlisted(relative: Path) -> bool:
    """Keep build-logic's own Kotlin Test contract explicit and narrow."""
    return relative.parts[:1] == ("build-logic",)


def _line_number(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def scan_repository(root: Path) -> ScanResult:
    root = root.resolve()
    findings: List[Finding] = []
    scanned_files = 0
    allowlisted_legacy_imports = 0
    for path in sorted(_iter_test_files(root)):
        scanned_files += 1
        relative = path.relative_to(root).as_posix()
        source = path.read_text(encoding="utf-8")
        masked = _checker_mask(source)
        allowlisted = _is_build_logic_allowlisted(path.relative_to(root))

        for match in LEGACY_IMPORT_RE.finditer(masked):
            if allowlisted:
                allowlisted_legacy_imports += 1
            else:
                findings.append(
                    Finding(
                        relative,
                        _line_number(masked, match.start()),
                        "legacy-assertion-import",
                        "consumer test imports a legacy Kotlin/JUnit assertion",
                    )
                )

        for match in GENERIC_BOOLEAN_OR_NULL_RE.finditer(masked):
            findings.append(
                Finding(
                    relative,
                    _line_number(masked, match.start()),
                    "generic-boolean-or-null-matcher",
                    "use the intent matcher for %s instead of shouldBeEqualTo" % match.group("value"),
                )
            )

    findings.sort(key=lambda finding: (finding.path, finding.line, finding.rule, finding.detail))
    return ScanResult(findings, scanned_files, allowlisted_legacy_imports)


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args(argv)
    result = scan_repository(args.root)
    for finding in result.findings:
        print("FAIL %s:%d: %s: %s" % (finding.path, finding.line, finding.rule, finding.detail))
    if result.findings:
        print(
            "FAIL assertion governance: %d finding(s), scanned=%d, allowlisted_build_logic_legacy_imports=%d"
            % (len(result.findings), result.scanned_files, result.allowlisted_legacy_imports)
        )
        return 1
    print(
        "PASS assertion governance: scanned=%d, allowlisted_build_logic_legacy_imports=%d"
        % (result.scanned_files, result.allowlisted_legacy_imports)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
