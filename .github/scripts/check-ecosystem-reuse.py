#!/usr/bin/env python3
"""Validate the ecosystem-reuse inventory and its bounded train contract."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


CLASSIFICATIONS = {
    "released-bluetape4k",
    "behavior-under-test",
    "provider-gap",
    "shared-candidate",
    "documented-raw-fallback",
}
STATUS_VALUES = {"pending", "verified"}
REQUIRED_COLUMNS = (
    "issue",
    "module",
    "capability",
    "dependency_alias",
    "resolved_module",
    "actual_import",
    "capability_api",
    "source_anchor",
    "test_anchor",
    "bluetape_source_anchor",
    "bluetape_test_anchor",
    "classification",
    "fallback_reason",
    "status",
)
STATE_VALUES = {"PLANNED", "READY", "INVALID", "MERGE_READY", "MERGED"}
RECEIPT_VALUES = {"PENDING", "PASS", "FAIL", "CANCELLED", "TIMEOUT", "CLEANUP_FAILED"}
RECEIPT_TRANSITIONS = {
    "PENDING": ["PENDING", "PASS", "FAIL", "CANCELLED", "TIMEOUT", "CLEANUP_FAILED"],
    "PASS": ["PENDING", "PASS"],
    "FAIL": ["PENDING", "FAIL"],
    "CANCELLED": ["PENDING", "CANCELLED"],
    "TIMEOUT": ["PENDING", "TIMEOUT"],
    "CLEANUP_FAILED": ["PENDING", "CLEANUP_FAILED"],
}
STATE_TRANSITIONS = {
    "PLANNED": ["PLANNED", "READY", "INVALID"],
    "READY": ["READY", "INVALID", "MERGE_READY"],
    "INVALID": ["INVALID", "PLANNED"],
    "MERGE_READY": ["MERGE_READY", "MERGED", "INVALID"],
    "MERGED": ["MERGED"],
}
ACTIVE_STATES = {"READY", "MERGE_READY"}
CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM_RE = re.compile(r"^[0-9a-f]{64}$")
FOLLOW_UP_SCOPE_KINDS = {"child", "coordinator"}
OID_POLICIES = {"exact", "rebase-aware"}
FIXED_NODE_OID_POLICIES = {"reviewed-ancestor"}
REVIEWED_MARKER_LABEL_RE = re.compile(r"(?im)\breviewed_implementation_oid\s*:")
REVIEWED_MARKER_RE = re.compile(r"(?im)\breviewed_implementation_oid\s*:\s*([0-9a-f]{40})\b")
FOLLOW_UP_SCOPE_FIELDS = {
    "scope_id", "scope_kind", "parent_track", "expected_head_ref", "expected_base_ref",
    "oid_policy", "head_oid", "base_oid", "issue_numbers", "allowed_paths", "review_artifact",
}
DEPENDENCY_DECLARATION_NAMES = {"build.gradle", "build.gradle.kts", "libs.versions.toml"}
DEPENDENCY_INSIGHT_RE = re.compile(
    r"^(?:\./gradlew|gradlew)\s+"
    r"(?P<task>(?::[A-Za-z0-9_-]+:)?dependencyInsight)\s+"
    r"--dependency\s+(?P<dependency>\S+)\s+"
    r"--configuration\s+(?P<configuration>\S+)\s*$"
)


def clean_cell(value: str) -> str:
    return value.strip().strip("`").strip()


def escaped(value: object) -> str:
    return str(value).encode("unicode_escape", "backslashreplace").decode("ascii")


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def parse_table(path: Path) -> Tuple[List[str], List[Dict[str, str]], List[str]]:
    errors: List[str] = []
    lines = path.read_text(encoding="utf-8").splitlines()
    header: Optional[List[str]] = None
    rows: List[Dict[str, str]] = []
    for line_number, line in enumerate(lines, 1):
        if not line.startswith("|"):
            continue
        cells = [clean_cell(cell) for cell in line.strip().strip("|").split("|")]
        if header is None and "issue" in cells and "module" in cells:
            header = cells
            continue
        if header is None or not cells or all(set(cell) <= {"-", ":"} for cell in cells):
            continue
        if len(cells) != len(header):
            errors.append("line %d: table column count mismatch" % line_number)
            continue
        rows.append(dict(zip(header, cells)))
    if header is None:
        errors.append("inventory table header is missing")
        return [], [], errors
    missing = [column for column in REQUIRED_COLUMNS if column not in header]
    if missing:
        errors.append("missing required columns: %s" % ", ".join(missing))
    return header, rows, errors


def safe_relative_path(root: Path, raw: str, *, allow_na: bool = False, must_exist: bool = True) -> Optional[Path]:
    value = clean_cell(raw)
    if allow_na and value == "N/A":
        return None
    if not value:
        raise ValueError("blank path")
    if CONTROL_RE.search(value):
        raise ValueError("control character in path")
    candidate = Path(value)
    if candidate.is_absolute() or ".." in candidate.parts:
        raise ValueError("absolute or traversal path")
    resolved_root = root.resolve()
    resolved = (root / candidate).resolve(strict=False)
    try:
        resolved.relative_to(resolved_root)
    except ValueError as exc:
        raise ValueError("path escapes repository") from exc
    if must_exist and not resolved.exists():
        raise ValueError("path does not exist")
    return resolved


def parse_catalog(root: Path) -> Dict[str, str]:
    catalog = root / "gradle" / "libs.versions.toml"
    modules: Dict[str, str] = {}
    pattern = re.compile(r"^\s*([A-Za-z0-9_.-]+)\s*=\s*\{\s*module\s*=\s*\"([^\"]+)\"")
    for line in catalog.read_text(encoding="utf-8").splitlines():
        match = pattern.match(line)
        if match:
            modules[match.group(1)] = match.group(2)
    return modules


def _candidate_import_tokens(alias: str, resolved_module: str, capability_api: str) -> List[str]:
    """Return exact-ish tokens that can prove the inventory anchor is meaningful.

    The inventory is intentionally checked before a child migration lands.  A
    released capability may therefore be represented by a Gradle alias in a
    build file, by an already imported API, or by an explicit ``candidate:``
    marker when the issue is about adding a missing dependency.  Descriptive
    prose alone is not accepted as import evidence.
    """
    tokens: List[str] = []
    artifact = resolved_module.rsplit(":", 1)[-1]
    alias_variants = {
        alias,
        alias.replace("-", "."),
        alias.replace("-", ""),
        alias.removeprefix("bluetape4k-").replace("-", "."),
        alias.removeprefix("bluetape4k-").replace("-", ""),
    }
    artifact_variants = {
        artifact,
        artifact.replace("-", "."),
        artifact.replace("bluetape4k-", "").replace("-", "."),
        artifact.replace("bluetape4k-", "").replace("-", ""),
    }
    tokens.extend(value for value in alias_variants | artifact_variants if len(value) >= 4)
    if not capability_api.startswith("candidate:"):
        for value in re.findall(r"[A-Za-z_][A-Za-z0-9_.-]{3,}", capability_api):
            if value.lower() not in {"value", "collection", "matcher", "candidate", "policy", "boundary", "primitive", "declaration", "runtime", "domain", "and", "or"}:
                tokens.append(value)
                tokens.extend(part for part in re.split(r"[.\-/ ]+", value) if len(part) >= 4)
    return sorted(set(tokens), key=len, reverse=True)


def _capability_api_tokens(capability_api: str) -> List[str]:
    if capability_api.startswith("candidate:"):
        return []
    ignored = {"value", "collection", "matcher", "candidate", "policy", "boundary", "primitive", "declaration", "runtime", "domain", "and", "or", "or", "test"}
    tokens: List[str] = []
    for value in re.findall(r"[A-Za-z_][A-Za-z0-9_.-]{3,}", capability_api):
        if value.lower() in ignored:
            continue
        tokens.append(value)
        tokens.extend(part for part in re.split(r"[.\-/ ]+", value) if len(part) >= 4 and part.lower() not in ignored)
    return sorted(set(tokens), key=len, reverse=True)


def _is_dependency_declaration_path(raw: str) -> bool:
    value = clean_cell(raw)
    return Path(value).name in DEPENDENCY_DECLARATION_NAMES


def _gradle_project_for_row(row: Dict[str, str]) -> str:
    """Resolve the Gradle project from the inventory module path."""
    parts = Path(row.get("module", "")).parts
    if not parts:
        return ""
    if parts[0] == "shared":
        return ":shared"
    if len(parts) < 2:
        return ":" + parts[0]
    return ":" + "-".join(parts[:2])


def _dependency_insight_key(command: str) -> Optional[Tuple[str, str, str]]:
    match = DEPENDENCY_INSIGHT_RE.fullmatch(command.strip())
    if not match:
        return None
    task = match.group("task")
    project = task.removesuffix(":dependencyInsight")
    if project == "dependencyInsight":
        project = ""
    return project, match.group("dependency"), match.group("configuration")


def _file_anchor_symbol(root: Path, raw: str, label: str) -> List[str]:
    """Validate a repository-relative ``path#symbol`` anchor and its symbol."""
    errors: List[str] = []
    value = clean_cell(raw)
    path, separator, symbol = value.partition("#")
    if not separator or not symbol.strip():
        return ["R2: parent_evidence %s must include a file#symbol anchor" % label]
    try:
        resolved = safe_relative_path(root, path)
    except ValueError as exc:
        return ["R2: parent_evidence %s path %s" % (label, escaped(exc))]
    try:
        text = resolved.read_text(encoding="utf-8")
    except OSError as exc:
        return ["R2: parent_evidence %s unreadable %s" % (label, escaped(exc))]
    if not re.search(r"\b%s\b" % re.escape(symbol.strip()), text):
        errors.append("R2: parent_evidence %s symbol is absent from its file" % label)
    return errors


def validate_inventory(root: Path, inventory_path: Path, manifest: Optional[Dict[str, object]] = None) -> List[str]:
    errors: List[str] = []
    header, rows, parse_errors = parse_table(inventory_path)
    errors.extend(parse_errors)
    if not header:
        return errors
    catalog = parse_catalog(root)
    seen = set()
    expected_dependency_keys: Dict[str, set] = {}
    path_columns = ("actual_import", "source_anchor", "test_anchor", "bluetape_source_anchor", "bluetape_test_anchor")
    for index, row in enumerate(rows, 1):
        prefix = "row %d" % index
        for column in REQUIRED_COLUMNS:
            if not row.get(column, "").strip():
                errors.append("%s: blank %s" % (prefix, column))
        key = (row.get("issue", ""), row.get("module", ""))
        if key in seen:
            errors.append("%s: duplicate issue/module key" % prefix)
        seen.add(key)
        if not re.fullmatch(r"[0-9]+", row.get("issue", "")):
            errors.append("%s: issue must be numeric" % prefix)
        classification = row.get("classification", "")
        if classification not in CLASSIFICATIONS:
            errors.append("%s: unknown classification %s" % (prefix, escaped(classification)))
        if classification != "released-bluetape4k" and not row.get("fallback_reason", "").strip():
            errors.append("%s: fallback_reason required for %s" % (prefix, classification))
        status = row.get("status", "")
        if status not in STATUS_VALUES:
            errors.append("%s: status must be pending or verified" % prefix)
        if status == "verified":
            if manifest is None:
                errors.append("%s: verified status requires manifest receipt evidence" % prefix)
            else:
                issue_number = int(row.get("issue", "0")) if row.get("issue", "").isdigit() else -1
                matching_nodes = [
                    node for node in manifest_nodes(manifest).values()
                    if issue_number in node.get("issue_numbers", [])
                ]
                if not any(
                    node.get("state") in {"READY", "MERGE_READY", "MERGED"}
                    and node.get("receipt_status") == "PASS"
                    and node.get("receipt_id")
                    and node.get("checksum")
                    for node in matching_nodes
                ):
                    errors.append("%s: verified status requires a PASS receipt for its issue node" % prefix)
        if manifest is not None:
            issue_number = int(row.get("issue", "0")) if row.get("issue", "").isdigit() else -1
            matching_nodes = [
                node for node in manifest_nodes(manifest).values()
                if issue_number in node.get("issue_numbers", [])
            ]
            if len(matching_nodes) != 1:
                errors.append(
                    "%s: issue must map to exactly one manifest track (found %d)" %
                    (prefix, len(matching_nodes))
                )
            for node in matching_nodes:
                track = str(node.get("track"))
                expected_key = (
                    _gradle_project_for_row(row),
                    row.get("resolved_module", ""),
                    "testRuntimeClasspath",
                )
                expected_dependency_keys.setdefault(track, set()).add(expected_key)
                command_keys = {
                    key for command in node.get("dependency_insight_commands", [])
                    if (key := _dependency_insight_key(command)) is not None
                }
                if expected_key not in command_keys:
                    errors.append(
                        "%s: dependencyInsight lacks exact project/dependency/configuration %s" %
                        (prefix, escaped(expected_key))
                    )
        for field in ("issue", "module", "capability", "dependency_alias", "resolved_module", "capability_api", "fallback_reason", "status"):
            if CONTROL_RE.search(row.get(field, "")):
                errors.append("%s: control character in %s" % (prefix, field))
        alias = row.get("dependency_alias", "")
        resolved_module = row.get("resolved_module", "")
        if alias not in catalog:
            errors.append("%s: unknown dependency alias %s" % (prefix, escaped(alias)))
        elif catalog[alias] != resolved_module:
            errors.append("%s: resolved_module does not match dependency alias" % prefix)
        actual_import = row.get("actual_import", "")
        capability_api = row.get("capability_api", "")
        if actual_import != "N/A" and _is_dependency_declaration_path(actual_import):
            errors.append(
                "%s: actual_import must point to a source/test file, not a dependency declaration" % prefix
            )
        if not capability_api.startswith("candidate:") and capability_api.startswith("libs."):
            errors.append("%s: capability_api must name an imported API, not a catalog alias" % prefix)
        for column in path_columns:
            try:
                allow_na = (
                    column.startswith("bluetape_") and classification != "released-bluetape4k"
                ) or (
                    column == "actual_import"
                    and classification in {"provider-gap", "shared-candidate", "documented-raw-fallback"}
                )
                resolved = safe_relative_path(root, row.get(column, ""), allow_na=allow_na)
                if column == "actual_import" and resolved is not None:
                    try:
                        source_text = resolved.read_text(encoding="utf-8")
                    except OSError as exc:
                        errors.append("%s: actual_import unreadable %s" % (prefix, escaped(exc)))
                    else:
                        if "bluetape4k" not in source_text:
                            errors.append("%s: actual_import lacks Bluetape import or dependency anchor" % prefix)
                        else:
                            import_tokens = _candidate_import_tokens(alias, resolved_module, capability_api)
                            if not any(token in source_text for token in import_tokens):
                                errors.append("%s: actual_import lacks the declared dependency/API token" % prefix)
                            api_tokens = _capability_api_tokens(capability_api)
                            if api_tokens and not any(token in source_text for token in api_tokens):
                                errors.append("%s: actual_import lacks the exact capability_api token" % prefix)
                elif column == "actual_import" and resolved is None and not row.get("capability_api", "").startswith("candidate:"):
                    errors.append("%s: N/A actual_import requires a candidate: capability_api marker" % prefix)
            except ValueError as exc:
                errors.append("%s: %s %s" % (prefix, column, escaped(exc)))
    if manifest is not None:
        nodes = manifest_nodes(manifest)
        for track, expected in expected_dependency_keys.items():
            commands = nodes.get(track, {}).get("dependency_insight_commands", [])
            parsed_keys = [
                key for command in commands
                if (key := _dependency_insight_key(command)) is not None
            ]
            command_keys = set(parsed_keys)
            for key in sorted(expected - command_keys):
                errors.append("%s: dependencyInsight coverage missing %s" % (track, escaped(key)))
            for key in sorted(command_keys - expected):
                errors.append("%s: dependencyInsight command has an unregistered key %s" % (track, escaped(key)))
            if len(parsed_keys) != len(command_keys):
                errors.append("%s: dependencyInsight commands must not contain duplicate keys" % track)
            if len(command_keys) != len(expected):
                errors.append(
                    "%s: dependencyInsight coverage must be one-to-one (%d expected, %d commands)" %
                    (track, len(expected), len(parsed_keys))
                )
    return errors


def manifest_nodes(manifest: Dict[str, object]) -> Dict[str, Dict[str, object]]:
    return {str(node.get("track")): node for node in manifest.get("nodes", []) if isinstance(node, dict)}


def manifest_follow_up_scopes(manifest: Dict[str, object]) -> List[Dict[str, object]]:
    scopes = manifest.get("follow_up_scopes", [])
    if not isinstance(scopes, list):
        return []
    return [scope for scope in scopes if isinstance(scope, dict)]


def _scope_path_prefix(raw: object) -> str:
    return clean_cell(str(raw)).removesuffix("/**")


def _validate_follow_up_scopes(
    root: Path,
    manifest: Dict[str, object],
    fixed_tracks: Sequence[str],
) -> List[str]:
    errors: List[str] = []
    raw_scopes = manifest.get("follow_up_scopes", [])
    if not isinstance(raw_scopes, list):
        return ["follow_up_scopes must be a list"]
    scopes = [scope for scope in raw_scopes if isinstance(scope, dict)]
    if len(scopes) != len(raw_scopes):
        errors.append("follow_up_scopes entries must be objects")
    scope_ids = set()
    for index, scope in enumerate(scopes):
        prefix = "follow_up_scopes[%d]" % index
        missing = sorted(FOLLOW_UP_SCOPE_FIELDS - set(scope))
        unknown = sorted(set(scope) - FOLLOW_UP_SCOPE_FIELDS)
        if missing:
            errors.append("%s: missing fields %s" % (prefix, ", ".join(missing)))
        if unknown:
            errors.append("%s: unknown fields %s" % (prefix, ", ".join(unknown)))
        scope_id = scope.get("scope_id")
        if not isinstance(scope_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", scope_id):
            errors.append("%s: scope_id must be a safe identifier" % prefix)
        elif scope_id in scope_ids:
            errors.append("%s: duplicate scope_id %s" % (prefix, escaped(scope_id)))
        else:
            scope_ids.add(scope_id)
        scope_kind = scope.get("scope_kind")
        if scope_kind not in FOLLOW_UP_SCOPE_KINDS:
            errors.append("%s: invalid scope_kind %s" % (prefix, escaped(scope_kind)))
        oid_policy = scope.get("oid_policy")
        if oid_policy not in OID_POLICIES:
            errors.append("%s: invalid oid_policy %s" % (prefix, escaped(oid_policy)))
        parent_track = scope.get("parent_track")
        if parent_track not in fixed_tracks:
            errors.append("%s: parent_track must name a fixed track" % prefix)
        for field in ("expected_head_ref", "expected_base_ref"):
            value = scope.get(field)
            if not isinstance(value, str) or not value.strip() or CONTROL_RE.search(value):
                errors.append("%s: %s must be a non-empty ref" % (prefix, field))
        for field in ("head_oid", "base_oid"):
            value = scope.get(field)
            if oid_policy == "rebase-aware":
                if value is not None:
                    errors.append("%s: rebase-aware scope requires %s to be null" % (prefix, field))
            elif oid_policy == "exact":
                if value in (None, ""):
                    errors.append("%s: exact scope requires %s" % (prefix, field))
                elif not SHA_RE.fullmatch(str(value)):
                    errors.append("%s: %s must be null or a 40-hex SHA" % (prefix, field))
        issue_numbers = scope.get("issue_numbers")
        if not isinstance(issue_numbers, list) or not issue_numbers:
            errors.append("%s: issue_numbers must be non-empty" % prefix)
        elif any(isinstance(issue, bool) or not isinstance(issue, int) or issue <= 0 for issue in issue_numbers):
            errors.append("%s: issue_numbers must contain positive integers" % prefix)
        elif len(set(issue_numbers)) != len(issue_numbers):
            errors.append("%s: issue_numbers must not contain duplicates" % prefix)
        allowed_paths = scope.get("allowed_paths")
        if not isinstance(allowed_paths, list) or not allowed_paths:
            errors.append("%s: allowed_paths must be non-empty" % prefix)
            allowed_paths = []
        else:
            for value in allowed_paths:
                try:
                    safe_relative_path(root, value, must_exist=False)
                except (TypeError, AttributeError, ValueError) as exc:
                    errors.append("%s: invalid allowed path %s" % (prefix, escaped(exc)))
        review_artifact = scope.get("review_artifact")
        if review_artifact is None:
            errors.append("%s: invalid review_artifact blank path" % prefix)
        else:
            try:
                safe_relative_path(root, review_artifact, must_exist=False)
            except (TypeError, AttributeError, ValueError) as exc:
                errors.append("%s: invalid review_artifact %s" % (prefix, escaped(exc)))
        if review_artifact not in allowed_paths:
            errors.append("%s: review_artifact must be in allowed_paths" % prefix)
        if parent_track in fixed_tracks:
            parent = manifest_nodes(manifest).get(str(parent_track), {})
            if _normalise_ref(str(scope.get("expected_base_ref", ""))) != _normalise_ref(str(parent.get("expected_head_ref", ""))):
                errors.append("%s: expected_base_ref must equal parent track expected_head_ref" % prefix)
    for index, left in enumerate(scopes):
        left_paths = left.get("allowed_paths", []) if isinstance(left.get("allowed_paths"), list) else []
        for right in scopes[index + 1 :]:
            right_paths = right.get("allowed_paths", []) if isinstance(right.get("allowed_paths"), list) else []
            if any(
                _scope_path_prefix(left_path) == _scope_path_prefix(right_path)
                or _scope_path_prefix(left_path).startswith(_scope_path_prefix(right_path) + "/")
                or _scope_path_prefix(right_path).startswith(_scope_path_prefix(left_path) + "/")
                for left_path in left_paths
                for right_path in right_paths
            ):
                errors.append(
                    "follow_up_scopes overlap: %s and %s" %
                    (escaped(left.get("scope_id")), escaped(right.get("scope_id")))
                )
    receipt = manifest.get("coordinator_scope_receipt")
    if scopes:
        if not isinstance(receipt, dict):
            errors.append("follow_up_scopes require coordinator_scope_receipt")
        else:
            receipt_id = receipt.get("receipt_id")
            checksum = receipt.get("checksum")
            if not isinstance(receipt_id, str) or not receipt_id.strip() or CONTROL_RE.search(receipt_id):
                errors.append("coordinator_scope_receipt receipt_id must be non-empty")
            if not isinstance(checksum, str) or not CHECKSUM_RE.fullmatch(checksum):
                errors.append("coordinator_scope_receipt checksum must be a 64-hex SHA")
    elif receipt not in (None, {}):
        errors.append("coordinator_scope_receipt requires follow_up_scopes")
    return errors


def validate_manifest(root: Path, manifest_path: Path, bootstrap: bool = False, trusted_path: Optional[Path] = None) -> List[str]:
    errors: List[str] = []
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return ["manifest unreadable: %s" % escaped(exc)]
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1:
        errors.append("manifest schema_version must be 1")
        return errors
    fixed_tracks = ["P0", "A1", "A2", "F1", "F2", "R1", "R2", "T1", "I1"]
    if manifest.get("fixed_tracks") != fixed_tracks:
        errors.append("manifest fixed_tracks must be the exact nine-track allowlist")
    if manifest.get("receipt_transitions") != RECEIPT_TRANSITIONS:
        errors.append("manifest receipt_transitions must match the fixed contract")
    if manifest.get("state_transitions") != STATE_TRANSITIONS:
        errors.append("manifest state_transitions must match the fixed contract")
    nodes = manifest.get("nodes")
    if not isinstance(nodes, list) or len(nodes) != len(fixed_tracks):
        errors.append("manifest nodes must contain exactly nine entries")
        return errors
    node_map = manifest_nodes(manifest)
    if sorted(node_map) != sorted(fixed_tracks):
        errors.append("manifest node tracks do not match fixed allowlist")
    required = {
        "track", "expected_head_ref", "expected_base_ref", "parent_track", "oid_policy", "head_oid", "base_oid",
        "parent_oid", "merge_base_oid", "state", "issue_numbers", "allowed_paths", "gradle_tasks",
        "test_selectors", "gradle_flags", "timeout_seconds", "docker_required", "review_artifact",
        "receipt_id", "receipt_status", "checksum", "dependency_insight_commands", "reviewed_implementation_oid",
    }
    for track in fixed_tracks:
        node = node_map.get(track)
        if not node:
            continue
        missing = sorted(required - set(node))
        if missing:
            errors.append("%s: missing manifest fields %s" % (track, ", ".join(missing)))
            continue
        state = node.get("state")
        receipt_status = node.get("receipt_status")
        oid_policy = node.get("oid_policy")
        if state not in STATE_VALUES:
            errors.append("%s: invalid state %s" % (track, escaped(state)))
        if receipt_status not in RECEIPT_VALUES:
            errors.append("%s: invalid receipt_status %s" % (track, escaped(receipt_status)))
        if oid_policy not in FIXED_NODE_OID_POLICIES:
            errors.append("%s: invalid fixed-node oid_policy %s" % (track, escaped(oid_policy)))
        elif oid_policy == "reviewed-ancestor":
            if any(node.get(field) not in (None, "") for field in ("head_oid", "base_oid", "merge_base_oid")):
                errors.append("%s: reviewed-ancestor oid_policy requires null legacy OIDs" % track)
            reviewed_oid = node.get("reviewed_implementation_oid")
            if reviewed_oid not in (None, "") and not SHA_RE.fullmatch(str(reviewed_oid)):
                errors.append("%s: reviewed_implementation_oid must be null or a 40-hex SHA" % track)
            if state != "PLANNED" and not SHA_RE.fullmatch(str(reviewed_oid or "")):
                errors.append("%s: active reviewed-ancestor node requires reviewed_implementation_oid" % track)
        if state in {"READY", "MERGE_READY", "MERGED"} and receipt_status != "PASS":
            errors.append("%s: ready state requires receipt_status PASS" % track)
        required_non_planned_fields = ("receipt_id", "checksum")
        if oid_policy != "reviewed-ancestor":
            required_non_planned_fields += ("head_oid", "base_oid", "merge_base_oid")
        if state != "PLANNED" and any(node.get(field) in (None, "") for field in required_non_planned_fields):
            errors.append("%s: non-PLANNED node has incomplete receipt/OID fields" % track)
        parent_track = node.get("parent_track")
        if parent_track is not None:
            parent_node = node_map.get(str(parent_track), {})
            if state in ACTIVE_STATES | {"MERGED"}:
                if parent_node.get("state") not in ACTIVE_STATES | {"MERGED"}:
                    errors.append("%s: active child requires an active parent" % track)
                if not SHA_RE.fullmatch(str(node.get("parent_oid", ""))):
                    errors.append("%s: active child node requires a 40-hex parent_oid" % track)
                parent_oid_field = (
                    "reviewed_implementation_oid"
                    if parent_node.get("oid_policy") == "reviewed-ancestor"
                    else "head_oid"
                )
                parent_oid = parent_node.get(parent_oid_field)
                if not SHA_RE.fullmatch(str(parent_oid or "")):
                    errors.append(
                        "%s: active child requires a recorded parent %s" %
                        (track, parent_oid_field)
                    )
                elif node.get("parent_oid") != parent_oid:
                    errors.append(
                        "%s: parent_oid must equal the parent node %s" %
                        (track, parent_oid_field)
                    )
        if not isinstance(node.get("issue_numbers"), list) or not node.get("issue_numbers"):
            errors.append("%s: issue_numbers must be non-empty" % track)
        if not isinstance(node.get("allowed_paths"), list) or not node.get("allowed_paths"):
            errors.append("%s: allowed_paths must be non-empty" % track)
        if not isinstance(node.get("gradle_tasks"), list) or not node.get("gradle_tasks"):
            errors.append("%s: gradle_tasks must be non-empty" % track)
        if not isinstance(node.get("test_selectors"), list) or not node.get("test_selectors"):
            errors.append("%s: test_selectors must be non-empty" % track)
        if not isinstance(node.get("gradle_flags"), list) or "--no-build-cache" not in node.get("gradle_flags", []):
            errors.append("%s: gradle_flags must include --no-build-cache" % track)
        if not isinstance(node.get("timeout_seconds"), int) or node.get("timeout_seconds", 0) <= 0:
            errors.append("%s: timeout_seconds must be positive" % track)
        if not isinstance(node.get("docker_required"), bool):
            errors.append("%s: docker_required must be boolean" % track)
        insight_commands = node.get("dependency_insight_commands")
        if not isinstance(insight_commands, list) or (track != "P0" and not insight_commands):
            errors.append("%s: dependency_insight_commands must be a list and non-empty outside P0" % track)
        else:
            for command in insight_commands:
                if not isinstance(command, str) or not command.strip() or CONTROL_RE.search(command):
                    errors.append("%s: invalid dependency insight command" % track)
                elif _dependency_insight_key(command) is None:
                    errors.append("%s: dependency insight command must pin project, dependency, and configuration" % track)
        for value in node.get("allowed_paths", []):
            try:
                safe_relative_path(root, value, must_exist=False)
            except ValueError as exc:
                errors.append("%s: invalid allowed path %s" % (track, escaped(exc)))
        try:
            safe_relative_path(root, node.get("review_artifact", ""), must_exist=False)
        except ValueError as exc:
            errors.append("%s: invalid review_artifact %s" % (track, escaped(exc)))
        if node.get("review_artifact") not in node.get("allowed_paths", []):
            errors.append("%s: review_artifact must be in allowed_paths" % track)
        if track != "P0" and not re.search(r"(?:-|/)%s-7tier\.md$" % re.escape(track), str(node.get("review_artifact", ""))):
            errors.append("%s: review_artifact filename must include its track" % track)
        for selector in node.get("test_selectors", []):
            if not isinstance(selector, str) or not selector.strip() or CONTROL_RE.search(selector):
                errors.append("%s: invalid test selector" % track)
    errors.extend(_validate_follow_up_scopes(root, manifest, fixed_tracks))
    active = {track: node for track, node in node_map.items() if node.get("state") in ACTIVE_STATES}
    active_paths = [(track, clean_cell(path).removesuffix("/**")) for track, node in active.items() for path in node.get("allowed_paths", [])]
    for index, (left_track, left_path) in enumerate(active_paths):
        for right_track, right_path in active_paths[index + 1 :]:
            if left_track != right_track and (left_path == right_path or left_path.startswith(right_path + "/") or right_path.startswith(left_path + "/")):
                errors.append("active allowed_paths overlap: %s and %s" % (left_track, right_track))
    r2 = node_map.get("R2", {})
    parent_evidence = r2.get("parent_evidence")
    if r2.get("parent_track") == "R1":
        if not isinstance(parent_evidence, dict) or parent_evidence.get("required") is not True:
            errors.append("R2: parent_evidence is required for R1 stacking")
        else:
            parent_paths = [clean_cell(path).removesuffix("/**") for path in node_map.get("R1", {}).get("allowed_paths", [])]
            required_evidence = ("r1_api_anchor", "r1_allowed_path", "r2_consumer_anchor", "r2_test_anchor")
            missing_evidence = [key for key in required_evidence if not str(parent_evidence.get(key, "")).strip()]
            if missing_evidence:
                errors.append("R2: parent_evidence missing %s" % ", ".join(missing_evidence))
            parent_path = clean_cell(str(parent_evidence.get("r1_allowed_path", parent_evidence.get("parent_allowed_path", ""))))
            consumer_anchor = clean_cell(str(parent_evidence.get("r2_consumer_anchor", "")))
            test_anchor = clean_cell(str(parent_evidence.get("r2_test_anchor", "")))
            consumer_path = consumer_anchor.split("#", 1)[0]
            test_path = test_anchor.split("#", 1)[0]
            if parent_path not in parent_paths:
                errors.append("R2: parent_evidence r1_allowed_path is outside R1 allowlist")
            r1_api_anchor = clean_cell(str(parent_evidence.get("r1_api_anchor", "")))
            r1_api_path = r1_api_anchor.split("#", 1)[0]
            if not any(r1_api_path == path or r1_api_path.startswith(path + "/") for path in parent_paths):
                errors.append("R2: parent_evidence r1_api_anchor is outside R1 allowlist")
            r2_paths = [clean_cell(path).removesuffix("/**") for path in r2.get("allowed_paths", [])]
            if not any(consumer_path == path or consumer_path.startswith(path + "/") for path in r2_paths):
                errors.append("R2: parent_evidence r2_consumer_anchor is outside R2 allowlist")
            if not any(test_path == path or test_path.startswith(path + "/") for path in r2_paths):
                errors.append("R2: parent_evidence r2_test_anchor is outside R2 allowlist")
            for key in ("r1_api_anchor", "r2_consumer_anchor", "r2_test_anchor"):
                anchor_path = clean_cell(str(parent_evidence.get(key, ""))).split("#", 1)[0]
                if anchor_path and (anchor_path == "N/A" or not Path(anchor_path).suffix):
                    errors.append("R2: parent_evidence %s must include a file anchor" % key)
                errors.extend(_file_anchor_symbol(root, str(parent_evidence.get(key, "")), key))
    elif r2.get("parent_track") == "P0":
        if parent_evidence not in (None, {}):
            errors.append("R2: reparented P0 node must clear stale parent_evidence")
        p0 = node_map.get("P0", {})
        if r2.get("expected_base_ref") != p0.get("expected_head_ref"):
            errors.append("R2: P0 reparent must use the frozen P0 expected_head_ref")
        parent_oid_field = (
            "reviewed_implementation_oid"
            if p0.get("oid_policy") == "reviewed-ancestor"
            else "head_oid"
        )
        parent_oid = p0.get(parent_oid_field)
        if not SHA_RE.fullmatch(str(parent_oid or "")):
            errors.append("R2: P0 reparent requires recorded P0 %s" % parent_oid_field)
        elif r2.get("parent_oid") != parent_oid:
            errors.append("R2: P0 reparent parent_oid must equal P0 %s" % parent_oid_field)
    elif parent_evidence not in (None, {}):
        errors.append("R2: parent_evidence is only valid while parent_track is R1")
    if bootstrap:
        if trusted_path is not None:
            errors.append("--bootstrap cannot be combined with a trusted manifest")
        if manifest.get("base_ref") != "origin/develop":
            errors.append("P0 bootstrap requires base_ref origin/develop")
        for track in fixed_tracks:
            node = node_map.get(track, {})
            if node.get("oid_policy") != "reviewed-ancestor":
                errors.append("%s: bootstrap context permits only reviewed-ancestor fixed nodes" % track)
            if node.get("state") != "PLANNED":
                errors.append("%s: bootstrap context requires every fixed node to remain PLANNED" % track)
            if node.get("reviewed_implementation_oid") not in (None, ""):
                errors.append("%s: bootstrap context requires null reviewed_implementation_oid" % track)
    elif trusted_path is not None:
        try:
            trusted = json.loads(trusted_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            errors.append("trusted manifest unreadable: %s" % escaped(exc))
        else:
            for top_level in ("repository", "base_ref", "fixed_tracks", "state_values", "receipt_status_values", "receipt_transitions", "state_transitions"):
                if manifest.get(top_level) != trusted.get(top_level):
                    errors.append("manifest top-level contract changed without a trusted coordinator update: %s" % top_level)
            current_scopes = manifest.get("follow_up_scopes", [])
            trusted_scopes = trusted.get("follow_up_scopes", [])
            current_scope_receipt = manifest.get("coordinator_scope_receipt")
            trusted_scope_receipt = trusted.get("coordinator_scope_receipt")
            if current_scopes != trusted_scopes:
                current_receipt_id = current_scope_receipt.get("receipt_id") if isinstance(current_scope_receipt, dict) else None
                current_receipt_checksum = current_scope_receipt.get("checksum") if isinstance(current_scope_receipt, dict) else None
                trusted_receipt_id = trusted_scope_receipt.get("receipt_id") if isinstance(trusted_scope_receipt, dict) else None
                trusted_receipt_checksum = trusted_scope_receipt.get("checksum") if isinstance(trusted_scope_receipt, dict) else None
                if (
                    not isinstance(current_scope_receipt, dict)
                    or not current_receipt_id
                    or not current_receipt_checksum
                    or current_receipt_id == trusted_receipt_id
                    or current_receipt_checksum == trusted_receipt_checksum
                ):
                    errors.append("follow_up_scopes changed without a fresh coordinator receipt")
            elif current_scope_receipt != trusted_scope_receipt:
                errors.append("coordinator_scope_receipt changed without follow_up_scopes update")
            current_nodes = manifest_nodes(manifest)
            trusted_nodes = manifest_nodes(trusted)
            for track in fixed_tracks:
                current = current_nodes.get(track, {})
                baseline = trusted_nodes.get(track, {})
                graph_changed = any(current.get(field) != baseline.get(field) for field in (
                    "expected_head_ref", "expected_base_ref", "parent_track", "oid_policy", "issue_numbers",
                    "allowed_paths", "gradle_tasks", "test_selectors", "gradle_flags",
                    "timeout_seconds", "docker_required", "dependency_insight_commands",
                    "review_artifact", "parent_evidence", "reviewed_implementation_oid",
                ))
                changed = graph_changed
                if changed:
                    if baseline.get("state") == "MERGED":
                        errors.append("%s: MERGED execution scope is immutable" % track)
                    elif (
                        current.get("state") not in {"READY", "MERGE_READY", "MERGED"}
                        or not current.get("receipt_id")
                        or current.get("receipt_id") == baseline.get("receipt_id")
                        or current.get("checksum") == baseline.get("checksum")
                    ):
                        errors.append("%s: execution scope changed without a fresh coordinator receipt" % track)
                previous_status = baseline.get("receipt_status")
                current_status = current.get("receipt_status")
                if previous_status in RECEIPT_TRANSITIONS and current_status != previous_status:
                    if current_status not in RECEIPT_TRANSITIONS[previous_status]:
                        errors.append("%s: receipt_status transition %s -> %s is not allowed" % (track, previous_status, current_status))
                    if current_status == "PENDING":
                        if current.get("state") != "PLANNED" or current.get("receipt_id") is not None or current.get("checksum") is not None:
                            errors.append("%s: resetting receipt_status to PENDING must clear receipt and keep PLANNED" % track)
                    elif not current.get("receipt_id") or not current.get("checksum"):
                        errors.append("%s: receipt transition requires receipt_id and checksum" % track)
                if previous_status in {"PASS", "FAIL", "CANCELLED", "TIMEOUT", "CLEANUP_FAILED"} and current_status == previous_status:
                    if any(current.get(field) != baseline.get(field) for field in ("receipt_id", "checksum")):
                        errors.append("%s: terminal receipt is immutable; rerun must reset to PENDING" % track)
                previous_state = baseline.get("state")
                current_state = current.get("state")
                if previous_state in STATE_TRANSITIONS and current_state not in STATE_TRANSITIONS[previous_state]:
                    errors.append("%s: state transition %s -> %s is not allowed" % (track, previous_state, current_state))
                receipt_fields_changed = any(current.get(field) != baseline.get(field) for field in ("head_oid", "base_oid", "parent_oid", "merge_base_oid", "receipt_id", "checksum"))
                if receipt_fields_changed and not current.get("receipt_id") and current_status != "PENDING":
                    errors.append("%s: changed OID/checksum fields require a receipt_id" % track)
    return errors


def validate_workflow(path: Path, pins_path: Optional[Path] = None) -> List[str]:
    errors: List[str] = []
    text = path.read_text(encoding="utf-8")
    refs = re.findall(r"uses:\s*([^\s#]+)", text)
    if not pins_path or not pins_path.exists():
        return ["workflow action pins file is required"]
    try:
        pins = json.loads(pins_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return ["workflow action pins file is unreadable: %s" % escaped(exc)]
    if not isinstance(pins, dict) or not pins:
        errors.append("workflow action pins file must be a non-empty object")
    for ref in refs:
        if "@" not in ref:
            errors.append("workflow action reference has no ref: %s" % escaped(ref))
            continue
        action, commit = ref.rsplit("@", 1)
        if not SHA_RE.fullmatch(commit):
            errors.append("workflow action is not pinned to a commit SHA: %s" % escaped(ref))
        elif action not in pins or not isinstance(pins.get(action), dict) or pins[action].get("ref") != commit:
            errors.append("workflow action pin is not recorded: %s" % escaped(ref))
        elif not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", str(pins[action].get("release_tag", ""))):
            errors.append("workflow action pin is missing a release tag: %s" % escaped(action))
        elif not re.fullmatch(r"https://github\.com/[^/]+/[^/]+/(?:releases/tag|commit)/v?[0-9A-Za-z._-]+", str(pins[action].get("source", ""))):
            errors.append("workflow action pin is missing a source URL: %s" % escaped(action))
    if re.search(r"persist-credentials\s*:\s*true", text, re.IGNORECASE):
        errors.append("workflow enables persist-credentials")
    if re.search(r"secrets\.[A-Za-z0-9_]+|github\.token|ACTIONS_RUNTIME_TOKEN|GITHUB_TOKEN", text):
        errors.append("workflow contains a secret or token handoff")
    if re.search(r"permissions:\s*(?:read-all|write-all)", text, re.IGNORECASE):
        errors.append("workflow permissions must not use read-all or write-all")
    for permission, value in re.findall(r"^\s+(contents|issues|pull-requests|actions|id-token|packages):\s*([^\s#]+)", text, re.MULTILINE):
        if value.lower() != "read" or permission != "contents":
            errors.append("workflow permission is broader than contents: read")
    if not re.search(r"permissions:[ \t]*\n(?:[ \t]+[^\n]+\n)*[ \t]+contents:[ \t]*read", text):
        errors.append("workflow must grant only contents: read")
    if "retention-days: 7" not in text:
        errors.append("workflow artifact retention must be 7 days")
    if "timeout-minutes: 10" not in text:
        errors.append("workflow timeout-minutes must be 10")
    if "cancel-in-progress: true" not in text:
        errors.append("workflow must cancel duplicate runs")
    for required_path in (
        "optimization/field-service-dispatch/src/main/kotlin/**",
        "optimization/field-service-dispatch/src/test/kotlin/**",
    ):
        if required_path not in text:
            errors.append("workflow pull_request paths must include %s" % required_path)
    return errors


def validate_changed_files(root: Path, paths: Sequence[str]) -> List[str]:
    errors: List[str] = []
    for raw in paths:
        try:
            file_path = safe_relative_path(root, raw, must_exist=False)
        except ValueError as exc:
            errors.append("changed file rejected: %s" % escaped(exc))
            continue
        if not file_path or not file_path.exists() or file_path.suffix not in {".kts", ".toml"}:
            continue
        text = file_path.read_text(encoding="utf-8")
        if re.search(r"platform\s*\(\s*libs\.bluetape4k\.(?!dependencies\b)[^)]*\)", text):
            errors.append("individual Bluetape BOM is forbidden: %s" % escaped(raw))
        if re.search(r"io\.github\.bluetape4k[^\"']*:[0-9]+\.[0-9]+(?:\.[0-9]+)?", text):
            errors.append("explicit Bluetape version pin is forbidden: %s" % escaped(raw))
    return errors


def changed_files_between_refs(root: Path, base_ref: str, head_ref: str) -> Tuple[List[str], List[str]]:
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", base_ref, head_ref],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        return [], ["changed-file diff could not be resolved: %s" % escaped(exc)]
    return [line for line in result.stdout.splitlines() if line.strip()], []


def _git_is_ancestor(root: Path, ancestor: str, descendant: str) -> bool:
    try:
        result = subprocess.run(
            ["git", "merge-base", "--is-ancestor", ancestor, descendant],
            cwd=root,
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError:
        return False
    return result.returncode == 0


def _read_reviewed_implementation_oid(
    root: Path,
    review_artifact: str,
    scope_label: str,
) -> Tuple[Optional[str], List[str]]:
    try:
        artifact_path = safe_relative_path(root, review_artifact)
    except (TypeError, AttributeError, ValueError) as exc:
        return None, ["%s: review artifact is invalid: %s" % (scope_label, escaped(exc))]
    try:
        text = artifact_path.read_text(encoding="utf-8")
    except OSError as exc:
        return None, ["%s: review artifact is unreadable: %s" % (scope_label, escaped(exc))]
    labels = REVIEWED_MARKER_LABEL_RE.findall(text)
    matches = REVIEWED_MARKER_RE.findall(text)
    if len(labels) != 1 or len(matches) != 1:
        return None, [
            "%s: review artifact must contain exactly one reviewed_implementation_oid marker" % scope_label
        ]
    return matches[0], []


def _normalise_ref(value: str) -> str:
    normalised = clean_cell(value)
    for prefix in ("refs/heads/", "origin/"):
        if normalised.startswith(prefix):
            normalised = normalised[len(prefix):]
    return normalised


def _path_matches_allowed(path: str, allowed_path: str) -> bool:
    path = clean_cell(path)
    allowed = clean_cell(allowed_path)
    if allowed.endswith("/**"):
        prefix = allowed[:-3].rstrip("/")
        return path == prefix or path.startswith(prefix + "/")
    return path == allowed


def validate_train_scope(
    manifest: Dict[str, object],
    changed_paths: Sequence[str],
    *,
    base_ref_name: str,
    head_ref_name: str,
    base_oid: str,
    head_oid: str,
    bootstrap: bool = False,
    repository_root: Optional[Path] = None,
) -> List[str]:
    """Bind a pull-request diff to one manifest node and its ref/OID policy."""
    errors: List[str] = []
    if not changed_paths:
        return ["PR scope requires at least one changed path"]
    for label, oid in (("base_oid", base_oid), ("head_oid", head_oid)):
        if not SHA_RE.fullmatch(str(oid or "")):
            errors.append("%s must be a 40-hex SHA" % label)
    if not clean_cell(base_ref_name):
        errors.append("base_ref_name is required for PR scope")
    if not clean_cell(head_ref_name):
        errors.append("head_ref_name is required for PR scope")
    nodes = manifest_nodes(manifest)
    scope_entries = list(nodes.items()) + [
        (str(scope.get("scope_id")), scope)
        for scope in manifest_follow_up_scopes(manifest)
    ]
    matching_scopes = []
    for label, node in scope_entries:
        allowed_paths = node.get("allowed_paths", [])
        if isinstance(allowed_paths, list) and all(
            any(_path_matches_allowed(path, str(allowed)) for allowed in allowed_paths)
            for path in changed_paths
        ):
            matching_scopes.append((label, node))
    if not matching_scopes:
        errors.append(
            "PR changed paths must map to exactly one manifest track (found 0)"
        )
        return errors
    actual_base = _normalise_ref(base_ref_name)
    actual_head = _normalise_ref(head_ref_name)
    if len(matching_scopes) > 1:
        ref_matches = [
            (label, node)
            for label, node in matching_scopes
            if actual_base == _normalise_ref(str(node.get("expected_base_ref", "")))
            and actual_head == _normalise_ref(str(node.get("expected_head_ref", "")))
        ]
        if len(ref_matches) == 1:
            matching_scopes = ref_matches
        else:
            errors.append(
                "PR changed paths must map to exactly one manifest track (found %d)" % len(matching_scopes)
            )
            return errors
    if len(matching_scopes) != 1:
        errors.append(
            "PR changed paths must map to exactly one manifest track (found %d)" % len(matching_scopes)
        )
        return errors
    scope_label, node = matching_scopes[0]
    expected_base = _normalise_ref(str(node.get("expected_base_ref", "")))
    expected_head = _normalise_ref(str(node.get("expected_head_ref", "")))
    if actual_base != expected_base:
        errors.append(
            "%s: expected_base_ref %s but PR base ref is %s" %
            (scope_label, escaped(expected_base), escaped(actual_base))
        )
    if actual_head != expected_head:
        errors.append(
            "%s: expected_head_ref %s but PR head ref is %s" %
            (scope_label, escaped(expected_head), escaped(actual_head))
        )
    oid_policy = node.get("oid_policy")
    if oid_policy not in FIXED_NODE_OID_POLICIES and oid_policy not in OID_POLICIES:
        errors.append("%s: invalid oid_policy %s" % (scope_label, escaped(oid_policy)))
    elif oid_policy == "bootstrap":
        if not bootstrap:
            errors.append("%s: bootstrap oid_policy requires bootstrap context" % scope_label)
        if node.get("state") != "PLANNED":
            errors.append("%s: bootstrap oid_policy requires PLANNED state" % scope_label)
        if any(node.get(field) not in (None, "") for field in ("base_oid", "head_oid")):
            errors.append("%s: bootstrap oid_policy requires null OIDs" % scope_label)
    elif oid_policy == "exact":
        for label, supplied in (("base_oid", base_oid), ("head_oid", head_oid)):
            recorded = node.get(label)
            if not SHA_RE.fullmatch(str(recorded or "")):
                errors.append("%s: exact scope requires recorded %s" % (scope_label, label))
            elif recorded != supplied:
                errors.append("%s: %s does not match the manifest recorded OID" % (scope_label, label))
    elif oid_policy == "reviewed-ancestor":
        if repository_root is None:
            errors.append("%s: reviewed-ancestor requires repository_root" % scope_label)
        else:
            marker_oid, marker_errors = _read_reviewed_implementation_oid(
                repository_root,
                str(node.get("review_artifact", "")),
                scope_label,
            )
            errors.extend(marker_errors)
            if marker_oid is not None:
                manifest_oid = node.get("reviewed_implementation_oid")
                if node.get("state") in ACTIVE_STATES | {"MERGED"}:
                    if not SHA_RE.fullmatch(str(manifest_oid or "")):
                        errors.append("%s: active reviewed-ancestor scope requires manifest reviewed_implementation_oid" % scope_label)
                    elif marker_oid != manifest_oid:
                        errors.append("%s: marker must match manifest reviewed_implementation_oid" % scope_label)
                if marker_oid == head_oid:
                    errors.append("%s: reviewed_implementation_oid must be a prior commit, not the PR head" % scope_label)
                elif marker_oid == base_oid:
                    errors.append("%s: reviewed_implementation_oid must be after the PR base" % scope_label)
                else:
                    base_is_ancestor = _git_is_ancestor(repository_root, base_oid, marker_oid)
                    marker_is_ancestor = _git_is_ancestor(repository_root, marker_oid, head_oid)
                    if not base_is_ancestor:
                        errors.append("%s: reviewed_implementation_oid must descend from the PR base" % scope_label)
                    if not marker_is_ancestor:
                        errors.append("%s: reviewed_implementation_oid must be an ancestor of the PR head" % scope_label)
                    if base_is_ancestor and marker_is_ancestor:
                        tail_paths, tail_errors = changed_files_between_refs(repository_root, marker_oid, head_oid)
                        errors.extend("%s: %s" % (scope_label, error) for error in tail_errors)
                        review_artifact = clean_cell(str(node.get("review_artifact", "")))
                        if not tail_paths:
                            errors.append("%s: reviewed-ancestor evidence tail must contain a review artifact change" % scope_label)
                        elif any(path != review_artifact for path in tail_paths) or review_artifact not in tail_paths:
                            errors.append("%s: reviewed-ancestor evidence tail may change only the review artifact" % scope_label)
    return errors


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", default="docs/ecosystem-reuse-inventory.md")
    parser.add_argument("--manifest")
    parser.add_argument("--trusted-manifest")
    parser.add_argument("--bootstrap", action="store_true")
    parser.add_argument("--workflow")
    parser.add_argument("--pins", default="docs/governance/github-action-pins.json")
    parser.add_argument("--changed-file", action="append", default=[])
    parser.add_argument("--base-ref")
    parser.add_argument("--head-ref")
    parser.add_argument("--pr-scope", action="store_true")
    parser.add_argument("--base-ref-name")
    parser.add_argument("--head-ref-name")
    args = parser.parse_args(argv)
    root = repo_root()
    errors: List[str] = []
    manifest_data: Optional[Dict[str, object]] = None
    if args.manifest:
        try:
            manifest_data = json.loads((root / args.manifest).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            manifest_data = None
    errors.extend(validate_inventory(root, root / args.inventory, manifest_data))
    if args.manifest:
        errors.extend(validate_manifest(root, root / args.manifest, args.bootstrap, root / args.trusted_manifest if args.trusted_manifest else None))
    elif args.bootstrap:
        errors.append("--bootstrap requires --manifest")
    if args.workflow:
        errors.extend(validate_workflow(root / args.workflow, root / args.pins if args.pins else None))
    if bool(args.base_ref) != bool(args.head_ref):
        errors.append("--base-ref and --head-ref must be provided together")
    changed_files = list(args.changed_file)
    if args.base_ref and args.head_ref:
        diff_paths, diff_errors = changed_files_between_refs(root, args.base_ref, args.head_ref)
        errors.extend(diff_errors)
        changed_files.extend(diff_paths)
    scope_args = (args.base_ref_name, args.head_ref_name, args.base_ref, args.head_ref)
    if args.pr_scope:
        if not args.manifest or manifest_data is None:
            errors.append("--pr-scope requires a readable --manifest")
        elif not all(scope_args):
            errors.append("--pr-scope requires base/head ref names and OIDs")
        else:
            errors.extend(validate_train_scope(
                manifest_data,
                changed_files,
                base_ref_name=args.base_ref_name,
                head_ref_name=args.head_ref_name,
                base_oid=args.base_ref,
                head_oid=args.head_ref,
                bootstrap=args.bootstrap,
                repository_root=root,
            ))
    errors.extend(validate_changed_files(root, changed_files))
    if errors:
        for error in sorted(set(errors)):
            print("FAIL %s" % escaped(error))
        return 1
    print("PASS ecosystem-reuse inventory and train contract")
    return 0


if __name__ == "__main__":
    sys.exit(main())
