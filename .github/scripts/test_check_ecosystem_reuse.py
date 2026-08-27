import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-ecosystem-reuse.py")
SPEC = importlib.util.spec_from_file_location("check_ecosystem_reuse", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(CHECKER)
UNSET = object()


HEADERS = (
    "issue", "module", "capability", "dependency_alias", "resolved_module", "actual_import",
    "capability_api", "source_anchor", "test_anchor", "bluetape_source_anchor",
    "bluetape_test_anchor", "classification", "fallback_reason", "status",
)


class EcosystemReuseCheckerTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        (self.root / "gradle").mkdir()
        (self.root / "gradle/libs.versions.toml").write_text(
            'bluetape4k-assertions = { module = "io.github.bluetape4k:bluetape4k-assertions" }\n',
            encoding="utf-8",
        )
        (self.root / "src").mkdir()
        (self.root / "src/source.kt").write_text(
            "import io.bluetape4k.assertions.shouldBeEqualTo\n"
            "fun verify(actual: Int) = actual.shouldBeEqualTo(1)\n",
            encoding="utf-8",
        )
        (self.root / "src/test.kt").write_text("fun test() = Unit\n", encoding="utf-8")

        (self.root / "src/R1").mkdir()
        (self.root / "src/R2").mkdir()
        (self.root / "src/R1/Api.kt").write_text("class Factory", encoding="utf-8")
        (self.root / "src/R2/Consumer.kt").write_text("class Consumer", encoding="utf-8")
        (self.root / "src/R2/Test.kt").write_text("class FactoryTest", encoding="utf-8")

    def tearDown(self):
        self.temp_dir.cleanup()

    def row(self, **updates):
        values = {
            "issue": "1",
            "module": "sample",
            "capability": "assertion",
            "dependency_alias": "bluetape4k-assertions",
            "resolved_module": "io.github.bluetape4k:bluetape4k-assertions",
            "actual_import": "src/source.kt",
            "capability_api": "shouldBeEqualTo",
            "source_anchor": "src/source.kt",
            "test_anchor": "src/test.kt",
            "bluetape_source_anchor": "src/source.kt",
            "bluetape_test_anchor": "src/test.kt",
            "classification": "released-bluetape4k",
            "fallback_reason": "selected released matcher",
            "status": "pending",
        }
        values.update(updates)
        return values

    def inventory(self, rows, headers=HEADERS):
        path = self.root / "inventory.md"
        path.write_text(
            "| " + " | ".join(headers) + " |\n"
            + "|" + "|".join("---" for _ in headers) + "|\n"
            + "\n".join("| " + " | ".join(row.get(h, "") for h in headers) + " |" for row in rows)
            + "\n",
            encoding="utf-8",
        )
        return path

    def manifest(self, *, state="PLANNED", receipt_status="PENDING", overlap=False):
        tracks = ["P0", "A1", "A2", "F1", "F2", "R1", "R2", "T1", "I1"]
        nodes = []
        oid_policy = "reviewed-ancestor"
        for track in tracks:
            paths = ["src/%s/**" % track]
            if overlap and track == "A1":
                paths = ["src/P0/**"]
            review_path = "docs/review/%s-7tier.md" % track
            paths.append(review_path)
            nodes.append({
                "track": track,
                "expected_head_ref": "branch/%s" % track,
                "expected_base_ref": "origin/develop",
                "parent_track": None,
                "oid_policy": oid_policy,
                "head_oid": None,
                "base_oid": None,
                "parent_oid": None,
                "merge_base_oid": None,
                "reviewed_implementation_oid": None if state == "PLANNED" else "a" * 40,
                "state": state,
                "issue_numbers": [1],
                "allowed_paths": paths,
                "gradle_tasks": [":sample:test"],
                "test_selectors": [":sample:test"],
                "gradle_flags": ["--no-build-cache"],
                "timeout_seconds": 60,
                "docker_required": False,
                "dependency_insight_commands": [] if track == "P0" else [
                    "./gradlew :sample:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-assertions --configuration testRuntimeClasspath"
                ],
                "review_artifact": review_path,
                "receipt_id": None if state == "PLANNED" else "receipt-1",
                "receipt_status": receipt_status,
                "checksum": None if state == "PLANNED" else "d" * 64,
            })
        return {
            "schema_version": 1,
            "repository": "bluetape4k/bluetape4k-workshop",
            "base_ref": "origin/develop",
            "fixed_tracks": tracks,
            "state_values": ["PLANNED", "READY", "INVALID", "MERGE_READY", "MERGED"],
            "receipt_status_values": ["PENDING", "PASS", "FAIL", "CANCELLED", "TIMEOUT", "CLEANUP_FAILED"],
            "receipt_transitions": {
                "PENDING": ["PENDING", "PASS", "FAIL", "CANCELLED", "TIMEOUT", "CLEANUP_FAILED"],
                "PASS": ["PENDING", "PASS"],
                "FAIL": ["PENDING", "FAIL"],
                "CANCELLED": ["PENDING", "CANCELLED"],
                "TIMEOUT": ["PENDING", "TIMEOUT"],
                "CLEANUP_FAILED": ["PENDING", "CLEANUP_FAILED"],
            },
            "state_transitions": {
                "PLANNED": ["PLANNED", "READY", "INVALID"],
                "READY": ["READY", "INVALID", "MERGE_READY"],
                "INVALID": ["INVALID", "PLANNED"],
                "MERGE_READY": ["MERGE_READY", "MERGED", "INVALID"],
                "MERGED": ["MERGED"],
            },
            "nodes": nodes,
        }

    def follow_up_scope(
        self,
        *,
        scope_id="F1-child",
        scope_kind="child",
        oid_policy="exact",
        base_oid=UNSET,
        head_oid=UNSET,
    ):
        if base_oid is UNSET:
            base_oid = None if oid_policy == "rebase-aware" else "b" * 40
        if head_oid is UNSET:
            head_oid = None if oid_policy == "rebase-aware" else "a" * 40
        return {
            "scope_id": scope_id,
            "scope_kind": scope_kind,
            "parent_track": "F1",
            "expected_head_ref": "branch/F1-child",
            "expected_base_ref": "branch/F1",
            "oid_policy": oid_policy,
            "head_oid": head_oid,
            "base_oid": base_oid,
            "issue_numbers": [2],
            "allowed_paths": ["src/F1/**", "docs/review/F1-child-7tier.md"],
            "review_artifact": "docs/review/F1-child-7tier.md",
        }

    def manifest_with_follow_up_scope(self):
        manifest = self.manifest()
        manifest["follow_up_scopes"] = [self.follow_up_scope()]
        manifest["coordinator_scope_receipt"] = {
            "receipt_id": "run-1",
            "checksum": "c" * 64,
        }
        return manifest

    def _git(self, *args):
        return subprocess.run(
            ["git", *args],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def _reviewed_ancestor_history(self, *, marker_text=None, code_after_marker=False):
        self._git("init")
        self._git("config", "user.email", "test@example.com")
        self._git("config", "user.name", "Ecosystem Reuse Test")
        review = self.root / "docs/review/A1-7tier.md"
        review.parent.mkdir(parents=True)
        review.write_text("# A1 review\n", encoding="utf-8")
        self._git("add", ".")
        self._git("commit", "-m", "base")
        base_oid = self._git("rev-parse", "HEAD")

        changed = self.root / "src/A1/Changed.kt"
        changed.parent.mkdir(parents=True, exist_ok=True)
        changed.write_text("class Changed\n", encoding="utf-8")
        self._git("add", str(changed.relative_to(self.root)))
        self._git("commit", "-m", "implementation")
        implementation_oid = self._git("rev-parse", "HEAD")

        marker = marker_text or implementation_oid
        review.write_text(
            "# A1 review\n\n<!-- reviewed_implementation_oid: %s -->\n" % marker,
            encoding="utf-8",
        )
        if code_after_marker:
            changed.write_text("class Changed\nclass TailChange\n", encoding="utf-8")
            self._git("add", str(changed.relative_to(self.root)))
        self._git("add", str(review.relative_to(self.root)))
        self._git("commit", "-m", "review evidence tail")
        head_oid = self._git("rev-parse", "HEAD")
        return base_oid, implementation_oid, head_oid

    def _rebased_reviewed_ancestor_history(self):
        self._git("init")
        self._git("config", "user.email", "test@example.com")
        self._git("config", "user.name", "Ecosystem Reuse Test")
        review = self.root / "docs/review/A1-7tier.md"
        review.parent.mkdir(parents=True)
        review.write_text("# A1 review\n", encoding="utf-8")
        self._git("add", ".")
        self._git("commit", "-m", "base")
        original_base_oid = self._git("rev-parse", "HEAD")

        original_changed = self.root / "src/A1/Changed.kt"
        original_changed.parent.mkdir(parents=True, exist_ok=True)
        original_changed.write_text("class Changed\n", encoding="utf-8")
        self._git("add", str(original_changed.relative_to(self.root)))
        self._git("commit", "-m", "original implementation")
        stale_marker_oid = self._git("rev-parse", "HEAD")

        self._git("checkout", "-b", "rebased-child", original_base_oid)
        (self.root / "coordinator.txt").write_text("coordinator transition\n", encoding="utf-8")
        self._git("add", "coordinator.txt")
        self._git("commit", "-m", "coordinator transition")
        current_base_oid = self._git("rev-parse", "HEAD")

        rebased_changed = self.root / "src/A1/Changed.kt"
        rebased_changed.parent.mkdir(parents=True, exist_ok=True)
        rebased_changed.write_text("class Changed\n", encoding="utf-8")
        self._git("add", str(rebased_changed.relative_to(self.root)))
        self._git("commit", "-m", "rebased implementation")
        current_marker_oid = self._git("rev-parse", "HEAD")

        review.write_text(
            "# A1 review\n\n<!-- reviewed_implementation_oid: %s -->\n" % current_marker_oid,
            encoding="utf-8",
        )
        self._git("add", str(review.relative_to(self.root)))
        self._git("commit", "-m", "review evidence tail")
        head_oid = self._git("rev-parse", "HEAD")
        return current_base_oid, stale_marker_oid, current_marker_oid, head_oid

    def _reviewed_manifest(self):
        manifest = self.manifest()
        manifest["nodes"][1]["oid_policy"] = "reviewed-ancestor"
        return manifest

    def test_valid_row_with_existing_source_and_test_paths(self):
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row()]))
        self.assertEqual([], errors)

    def test_actual_import_must_contain_exact_capability_api(self):
        errors = CHECKER.validate_inventory(
            self.root,
            self.inventory([self.row(capability_api="missingBluetapeMatcher")]),
        )
        self.assertTrue(any("exact capability_api token" in error for error in errors))

    def test_actual_import_rejects_comment_only_import(self):
        (self.root / "src/source.kt").write_text(
            "// import io.github.bluetape4k.assertions.shouldBeEqualTo\n",
            encoding="utf-8",
        )
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row()]))
        self.assertTrue(any("Bluetape import declaration" in error for error in errors))

    def test_actual_import_rejects_string_only_import(self):
        (self.root / "src/source.kt").write_text(
            'val evidence = "import io.github.bluetape4k.assertions.shouldBeEqualTo"\n',
            encoding="utf-8",
        )
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row()]))
        self.assertTrue(any("Bluetape import declaration" in error for error in errors))

    def test_actual_import_rejects_import_only_capability(self):
        (self.root / "src/source.kt").write_text(
            "import io.github.bluetape4k.assertions.shouldBeEqualTo\n",
            encoding="utf-8",
        )
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row()]))
        self.assertTrue(any("exact capability_api token" in error for error in errors))

    def test_actual_import_rejects_non_source_document(self):
        (self.root / "README.md").write_text(
            "import io.github.bluetape4k.assertions.shouldBeEqualTo\n",
            encoding="utf-8",
        )
        errors = CHECKER.validate_inventory(
            self.root,
            self.inventory([self.row(actual_import="README.md")]),
        )
        self.assertTrue(any("source/test file" in error for error in errors))

    def test_released_actual_import_must_not_be_a_dependency_declaration(self):
        build_path = self.root / "src/build.gradle.kts"
        build_path.write_text(
            "dependencies { implementation(libs.bluetape4k.assertions) }\n",
            encoding="utf-8",
        )
        errors = CHECKER.validate_inventory(
            self.root,
            self.inventory([
                self.row(
                    actual_import="src/build.gradle.kts",
                    capability_api="libs.bluetape4k.assertions",
                )
            ]),
        )
        self.assertTrue(any("source/test file" in error for error in errors))

    def test_released_capability_api_must_not_be_a_catalog_alias(self):
        errors = CHECKER.validate_inventory(
            self.root,
            self.inventory([self.row(capability_api="libs.bluetape4k.assertions")]),
        )
        self.assertTrue(any("catalog alias" in error for error in errors))

    def test_missing_current_import_is_explicit_candidate_only(self):
        errors = CHECKER.validate_inventory(
            self.root,
            self.inventory([
                self.row(
                    actual_import="N/A",
                    capability_api="candidate: io.github.bluetape4k:bluetape4k-money",
                    classification="shared-candidate",
                    fallback_reason="dependency is not present in the current module",
                )
            ]),
        )
        self.assertEqual([], errors)

    def test_missing_required_column(self):
        headers = tuple(h for h in HEADERS if h != "capability_api")
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row()], headers))
        self.assertTrue(any("missing required columns" in error for error in errors))

    def test_unknown_classification(self):
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row(classification="unknown")] ))
        self.assertTrue(any("unknown classification" in error for error in errors))

    def test_missing_source_anchor(self):
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row(source_anchor="src/missing.kt")]))
        self.assertTrue(any("source_anchor" in error for error in errors))

    def test_raw_fallback_requires_reason(self):
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row(classification="documented-raw-fallback", fallback_reason="")]))
        self.assertTrue(any("fallback_reason required" in error for error in errors))

    def test_non_released_upstream_anchor_may_be_na(self):
        errors = CHECKER.validate_inventory(self.root, self.inventory([
            self.row(classification="provider-gap", bluetape_source_anchor="N/A", bluetape_test_anchor="N/A", fallback_reason="upstream API is not released")
        ]))
        self.assertEqual([], errors)

    def test_verified_inventory_row_requires_pass_receipt(self):
        row = self.row(status="verified")
        inventory = self.inventory([row])
        errors = CHECKER.validate_inventory(self.root, inventory, self.manifest())
        self.assertTrue(any("verified status requires a PASS receipt" in error for error in errors))

    def test_duplicate_issue_module_key(self):
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row(), self.row()]))
        self.assertTrue(any("duplicate issue/module" in error for error in errors))

    def test_path_traversal_absolute_and_control_character_are_rejected(self):
        for value in ("../outside", "/tmp/outside", "src/evil\nfile"):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    CHECKER.safe_relative_path(self.root, value, must_exist=False)

    def test_external_symlink_is_rejected(self):
        outside = Path(self.temp_dir.name).parent / "ecosystem-reuse-outside"
        outside.write_text("outside", encoding="utf-8")
        try:
            link = self.root / "src/link.kt"
            link.symlink_to(outside)
            with self.assertRaises(ValueError):
                CHECKER.safe_relative_path(self.root, "src/link.kt")
        finally:
            outside.unlink(missing_ok=True)

    def test_report_escapes_control_characters(self):
        report = CHECKER.escaped("bad\nvalue\x00")
        self.assertEqual("bad\\nvalue\\x00", report)

    def test_manifest_scope_change_requires_coordinator_transition(self):
        trusted = self.manifest()
        current = self.manifest()
        current["nodes"][1]["test_selectors"] = [":widened:test"]
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path)
        self.assertTrue(any("execution scope changed" in error for error in errors))

    def test_manifest_topology_change_requires_coordinator_transition(self):
        trusted = self.manifest()
        current = self.manifest()
        current["nodes"][1]["expected_base_ref"] = "unexpected/base"
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path)
        self.assertTrue(any("execution scope changed" in error for error in errors))

    def test_trusted_manifest_accepts_fresh_planned_ref_replan(self):
        trusted = self.manifest()
        current = self.manifest()
        current["nodes"][3]["expected_base_ref"] = "develop"
        current["nodes"][3]["receipt_status"] = "PASS"
        current["nodes"][3]["receipt_id"] = "replan-f1"
        current["nodes"][3]["checksum"] = "e" * 64
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        self.assertEqual([], CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path))

    def test_planned_scope_replan_rejects_non_ref_graph_change(self):
        trusted = self.manifest()
        current = self.manifest()
        current["nodes"][3]["test_selectors"] = [":widened:test"]
        current["nodes"][3]["receipt_status"] = "PASS"
        current["nodes"][3]["receipt_id"] = "replan-f1"
        current["nodes"][3]["checksum"] = "e" * 64
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path)
        self.assertTrue(any("PLANNED scope replan may change only ref/parent fields" in error for error in errors))

    def test_manifest_rejects_unknown_reviewed_marker_binding(self):
        manifest = self.manifest()
        manifest["nodes"][1]["reviewed_marker_binding"] = "floating"
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("invalid reviewed_marker_binding" in error for error in errors))

    def test_manifest_marker_binding_change_requires_coordinator_transition(self):
        trusted = self.manifest(state="READY", receipt_status="PASS")
        current = self.manifest(state="READY", receipt_status="PASS")
        current["nodes"][1]["reviewed_marker_binding"] = "lineage"
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path)
        self.assertTrue(any("without a fresh coordinator transition" in error for error in errors))

    def test_trusted_manifest_accepts_lineage_binding_with_fresh_coordinator_receipt(self):
        trusted = self.manifest(state="READY", receipt_status="PASS")
        current = self.manifest(state="READY", receipt_status="PASS")
        current["nodes"][1]["reviewed_marker_binding"] = "lineage"
        current["reviewed_marker_transitions"] = {
            "A1": {
                "from": "manifest",
                "to": "lineage",
                "receipt_id": "receipt-lineage",
                "checksum": "e" * 64,
            }
        }
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        self.assertEqual([], CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path))

    def test_trusted_manifest_rejects_reused_marker_transition_receipt(self):
        trusted = self.manifest(state="READY", receipt_status="PASS")
        trusted["nodes"][1]["reviewed_marker_binding"] = "manifest"
        trusted["reviewed_marker_transitions"] = {
            "A1": {
                "from": "lineage",
                "to": "manifest",
                "receipt_id": "receipt-reused",
                "checksum": "f" * 64,
            }
        }
        current = self.manifest(state="READY", receipt_status="PASS")
        current["nodes"][1]["reviewed_marker_binding"] = "lineage"
        current["reviewed_marker_transitions"] = {
            "A1": {
                "from": "manifest",
                "to": "lineage",
                "receipt_id": "receipt-reused",
                "checksum": "f" * 64,
            }
        }
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path)
        self.assertTrue(any("requires a fresh coordinator receipt" in error for error in errors))

    def test_manifest_active_path_overlap_is_rejected(self):
        manifest = self.manifest(state="READY", receipt_status="PASS", overlap=True)
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("overlap" in error for error in errors))

    def test_manifest_ready_state_requires_pass_receipt(self):
        manifest = self.manifest(state="READY", receipt_status="PENDING")
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("requires receipt_status PASS" in error for error in errors))

    def test_manifest_requires_structured_execution_fields(self):
        manifest = self.manifest()
        del manifest["nodes"][0]["gradle_tasks"]
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("missing manifest fields" in error for error in errors))

    def test_manifest_requires_explicit_fixed_oid_policy(self):
        manifest = self.manifest()
        del manifest["nodes"][0]["oid_policy"]
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("oid_policy" in error for error in errors))

    def test_manifest_rejects_exact_fixed_node_policy(self):
        manifest = self.manifest()
        manifest["nodes"][0]["oid_policy"] = "exact"
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("invalid fixed-node oid_policy exact" in error for error in errors))

    def test_manifest_requires_reviewed_implementation_oid_field(self):
        manifest = self.manifest()
        del manifest["nodes"][0]["reviewed_implementation_oid"]
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("reviewed_implementation_oid" in error for error in errors))

    def test_manifest_accepts_planned_reviewed_ancestor_without_oid(self):
        manifest = self._reviewed_manifest()
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertEqual([], CHECKER.validate_manifest(self.root, path))

    def test_manifest_accepts_active_reviewed_parent_child_transition(self):
        manifest = self.manifest()
        parent = manifest["nodes"][0]
        child = manifest["nodes"][1]
        parent.update({
            "state": "READY",
            "receipt_status": "PASS",
            "receipt_id": "receipt-p0",
            "checksum": "a" * 64,
            "reviewed_implementation_oid": "b" * 40,
        })
        child.update({
            "parent_track": "P0",
            "expected_base_ref": parent["expected_head_ref"],
            "state": "READY",
            "receipt_status": "PASS",
            "receipt_id": "receipt-a1",
            "checksum": "c" * 64,
            "parent_oid": parent["reviewed_implementation_oid"],
            "reviewed_implementation_oid": "d" * 40,
        })
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertEqual([], CHECKER.validate_manifest(self.root, path))

    def test_manifest_rejects_active_child_parent_reviewed_oid_mismatch(self):
        manifest = self.manifest()
        parent = manifest["nodes"][0]
        child = manifest["nodes"][1]
        parent.update({
            "state": "READY",
            "receipt_status": "PASS",
            "receipt_id": "receipt-p0",
            "checksum": "a" * 64,
            "reviewed_implementation_oid": "b" * 40,
        })
        child.update({
            "parent_track": "P0",
            "expected_base_ref": parent["expected_head_ref"],
            "state": "READY",
            "receipt_status": "PASS",
            "receipt_id": "receipt-a1",
            "checksum": "c" * 64,
            "parent_oid": "e" * 40,
            "reviewed_implementation_oid": "d" * 40,
        })
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("parent_oid must equal the parent node reviewed_implementation_oid" in error for error in errors))

    def test_manifest_rejects_active_child_with_planned_parent(self):
        manifest = self.manifest()
        child = manifest["nodes"][1]
        child.update({
            "parent_track": "P0",
            "expected_base_ref": manifest["nodes"][0]["expected_head_ref"],
            "state": "READY",
            "receipt_status": "PASS",
            "receipt_id": "receipt-a1",
            "checksum": "c" * 64,
            "parent_oid": "b" * 40,
            "reviewed_implementation_oid": "d" * 40,
        })
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("active child requires an active parent" in error for error in errors))

    def test_manifest_rejects_bootstrap_fixed_node_outside_planned_state(self):
        manifest = self.manifest(state="READY", receipt_status="PASS")
        manifest["nodes"][0]["oid_policy"] = "bootstrap"
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("invalid fixed-node oid_policy bootstrap" in error for error in errors))

    def test_bootstrap_context_rejects_fixed_node_policy_downgrade(self):
        manifest = self.manifest()
        manifest["nodes"][1]["oid_policy"] = "bootstrap"
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path, bootstrap=True)
        self.assertTrue(any("invalid fixed-node oid_policy bootstrap" in error for error in errors))

    def test_manifest_rejects_invalid_receipt_transition_contract(self):
        manifest = self.manifest()
        manifest["receipt_transitions"]["PASS"] = ["PASS"]
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("receipt_transitions" in error for error in errors))

    def test_manifest_r2_parent_evidence_must_match_allowlists(self):
        manifest = self.manifest()
        r2 = manifest["nodes"][6]
        r2["parent_track"] = "R1"
        r2["parent_evidence"] = {
            "parent_track": "R1",
            "parent_allowed_path": "src/R1",
            "consumer_allowed_path": "src/R2",
            "api_anchor": "fixture factory",
            "r1_api_anchor": "src/R1/Api.kt#Factory",
            "r1_allowed_path": "src/R1",
            "r2_consumer_anchor": "src/R2/Consumer.kt#Consumer",
            "r2_test_anchor": "src/R2/Test.kt#FactoryTest",
            "required": True,
        }
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertEqual([], CHECKER.validate_manifest(self.root, path))
        r2["parent_evidence"]["r1_allowed_path"] = "src/unknown"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertTrue(any("r1_allowed_path" in error for error in CHECKER.validate_manifest(self.root, path)))

    def test_manifest_accepts_coordinator_owned_follow_up_scope(self):
        manifest = self.manifest_with_follow_up_scope()
        manifest["follow_up_scopes"][0] = self.follow_up_scope(
            scope_id="coordinator-child",
            scope_kind="coordinator",
            oid_policy="rebase-aware",
        )
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertEqual([], CHECKER.validate_manifest(self.root, path))

    def test_manifest_accepts_rebase_aware_child_scope_without_oids(self):
        manifest = self.manifest_with_follow_up_scope()
        manifest["follow_up_scopes"][0] = self.follow_up_scope(oid_policy="rebase-aware")
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertEqual([], CHECKER.validate_manifest(self.root, path))

    def test_manifest_rejects_rebase_aware_child_scope_with_stale_oid(self):
        manifest = self.manifest_with_follow_up_scope()
        manifest["follow_up_scopes"][0] = self.follow_up_scope(
            oid_policy="rebase-aware",
            head_oid="a" * 40,
        )
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("rebase-aware scope requires head_oid to be null" in error for error in errors))

    def test_manifest_rejects_unknown_follow_up_oid_policy(self):
        manifest = self.manifest_with_follow_up_scope()
        manifest["follow_up_scopes"][0]["oid_policy"] = "floating"
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("invalid oid_policy" in error for error in errors))

    def test_manifest_rejects_follow_up_scope_overlap(self):
        manifest = self.manifest_with_follow_up_scope()
        manifest["follow_up_scopes"].append(
            self.follow_up_scope(scope_id="F1-child-overlap", base_oid="d" * 40, head_oid="e" * 40)
        )
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("follow_up_scopes overlap" in error for error in errors))

    def test_trusted_manifest_allows_follow_up_scope_with_fresh_coordinator_receipt(self):
        trusted = self.manifest()
        current = self.manifest_with_follow_up_scope()
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "manifest.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        self.assertEqual([], CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path))

    def test_trusted_manifest_rejects_follow_up_scope_without_fresh_coordinator_receipt(self):
        trusted = self.manifest()
        current = self.manifest_with_follow_up_scope()
        trusted["coordinator_scope_receipt"] = current["coordinator_scope_receipt"]
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "manifest.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path)
        self.assertTrue(any("follow_up_scopes changed without a fresh coordinator receipt" in error for error in errors))

    def _single_track_manifest(self):
        manifest = self.manifest()
        for index, node in enumerate(manifest["nodes"]):
            node["issue_numbers"] = [1] if node["track"] == "A1" else [100 + index]
        return manifest

    def test_dependency_insight_requires_exact_project_coordinate_and_configuration(self):
        manifest = self._single_track_manifest()
        inventory = self.inventory([self.row()])
        self.assertEqual([], CHECKER.validate_inventory(self.root, inventory, manifest))
        manifest["nodes"][1]["dependency_insight_commands"] = [
            "./gradlew :other:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-assertions --configuration testRuntimeClasspath"
        ]
        errors = CHECKER.validate_inventory(self.root, inventory, manifest)
        self.assertTrue(any("exact project/dependency/configuration" in error for error in errors))

    def test_dependency_insight_rejects_duplicate_and_extra_keys(self):
        manifest = self._single_track_manifest()
        command = manifest["nodes"][1]["dependency_insight_commands"][0]
        manifest["nodes"][1]["dependency_insight_commands"] = [command, command]
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row()]), manifest)
        self.assertTrue(any("duplicate keys" in error for error in errors))
        manifest["nodes"][1]["dependency_insight_commands"].append(
            "./gradlew :sample:dependencyInsight --dependency io.github.bluetape4k:unknown --configuration testRuntimeClasspath"
        )
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row()]), manifest)
        self.assertTrue(any("unregistered key" in error for error in errors))

    def test_inventory_issue_must_map_to_exactly_one_track(self):
        manifest = self.manifest()
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row()]), manifest)
        self.assertTrue(any("exactly one manifest track" in error for error in errors))
        row = self.row(issue="999")
        manifest = self._single_track_manifest()
        errors = CHECKER.validate_inventory(self.root, self.inventory([row]), manifest)
        self.assertTrue(any("exactly one manifest track (found 0)" in error for error in errors))

    def test_manifest_r2_parent_evidence_requires_matching_symbols(self):
        manifest = self.manifest()
        r2 = manifest["nodes"][6]
        r2["parent_track"] = "R1"
        r2["parent_evidence"] = {
            "parent_track": "R1",
            "r1_api_anchor": "src/R1/Api.kt#MissingFactory",
            "r1_allowed_path": "src/R1",
            "r2_consumer_anchor": "src/R2/Consumer.kt#Consumer",
            "r2_test_anchor": "src/R2/Test.kt#FactoryTest",
            "required": True,
        }
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("symbol is absent" in error for error in errors))

    def test_manifest_reparent_must_clear_stale_r1_evidence(self):
        manifest = self.manifest()
        r2 = manifest["nodes"][6]
        r2["parent_track"] = "P0"
        r2["parent_evidence"] = {"required": True}
        r2["expected_base_ref"] = manifest["nodes"][0]["expected_head_ref"]
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("clear stale parent_evidence" in error for error in errors))

    def test_manifest_reparent_uses_reviewed_ancestor_parent_oid(self):
        manifest = self.manifest(state="READY", receipt_status="PASS")
        p0 = manifest["nodes"][0]
        p0["reviewed_implementation_oid"] = "a" * 40
        r2 = manifest["nodes"][6]
        r2["parent_track"] = "P0"
        r2["expected_base_ref"] = p0["expected_head_ref"]
        r2["parent_oid"] = "b" * 40
        r2["state"] = "PLANNED"
        r2["receipt_status"] = "PENDING"
        r2["receipt_id"] = None
        r2["checksum"] = None
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("P0 reparent parent_oid must equal P0 reviewed_implementation_oid" in error for error in errors))

    def test_manifest_reparent_rejects_missing_reviewed_ancestor_parent_oid(self):
        manifest = self.manifest()
        p0 = manifest["nodes"][0]
        r2 = manifest["nodes"][6]
        r2["parent_track"] = "P0"
        r2["expected_base_ref"] = p0["expected_head_ref"]
        r2["parent_oid"] = "b" * 40
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, path)
        self.assertTrue(any("P0 reparent requires recorded P0 reviewed_implementation_oid" in error for error in errors))

    def test_manifest_rejects_invalid_state_transition(self):
        trusted = self.manifest()
        current = self.manifest()
        node = current["nodes"][1]
        node.update({"state": "MERGED", "receipt_status": "PASS", "head_oid": "a" * 40,
                     "base_oid": "b" * 40, "merge_base_oid": "c" * 40,
                     "receipt_id": "receipt-2", "checksum": "e" * 64})
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path)
        self.assertTrue(any("state transition PLANNED -> MERGED" in error for error in errors))

    def test_manifest_rejects_terminal_receipt_mutation(self):
        trusted = self.manifest(state="READY", receipt_status="PASS")
        current = self.manifest(state="READY", receipt_status="PASS")
        current["nodes"][1]["receipt_id"] = "receipt-mutated"
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path)
        self.assertTrue(any("terminal receipt is immutable" in error for error in errors))

    def test_manifest_rejects_merged_scope_change(self):
        trusted = self.manifest(state="MERGED", receipt_status="PASS")
        current = self.manifest(state="MERGED", receipt_status="PASS")
        current["nodes"][1]["test_selectors"] = [":changed:test"]
        trusted_path = self.root / "trusted.json"
        current_path = self.root / "current.json"
        trusted_path.write_text(json.dumps(trusted), encoding="utf-8")
        current_path.write_text(json.dumps(current), encoding="utf-8")
        errors = CHECKER.validate_manifest(self.root, current_path, trusted_path=trusted_path)
        self.assertTrue(any("MERGED execution scope is immutable" in error for error in errors))

    def test_workflow_rejects_tag_pin_and_secret_handoff(self):
        workflow = self.root / "workflow.yml"
        workflow.write_text(
            "permissions:\n  contents: read\n"
            "steps:\n  - uses: actions/checkout@v4\n"
            "    with:\n      persist-credentials: true\n"
            "    env:\n      TOKEN: ${{ secrets.TOKEN }}\n"
            "  - uses: actions/upload-artifact@v4\n"
            "    with:\n      retention-days: 7\n"
            "timeout-minutes: 10\ncancel-in-progress: true\n",
            encoding="utf-8",
        )
        pins = self.root / "pins.json"
        pins.write_text("{}", encoding="utf-8")
        errors = CHECKER.validate_workflow(workflow, pins)
        self.assertTrue(any("not pinned" in error for error in errors))
        self.assertTrue(any("secret" in error for error in errors))

    def test_workflow_requires_nonempty_pins_and_read_only_permissions(self):
        workflow = self.root / "workflow.yml"
        workflow.write_text(
            "permissions:\n  contents: write\n"
            "steps:\n  - uses: actions/checkout@" + "a" * 40 + "\n"
            "timeout-minutes: 10\nretention-days: 7\ncancel-in-progress: true\n",
            encoding="utf-8",
        )
        pins = self.root / "pins.json"
        pins.write_text("{}", encoding="utf-8")
        errors = CHECKER.validate_workflow(workflow, pins)
        self.assertTrue(any("non-empty object" in error for error in errors))
        self.assertTrue(any("broader than contents" in error for error in errors))

    def test_workflow_requires_pin_metadata(self):
        workflow = self.root / "workflow.yml"
        commit = "a" * 40
        workflow.write_text(
            "permissions:\n  contents: read\n"
            "steps:\n  - uses: actions/checkout@" + commit + "\n"
            "timeout-minutes: 10\nretention-days: 7\ncancel-in-progress: true\n",
            encoding="utf-8",
        )
        pins = self.root / "pins.json"
        pins.write_text(json.dumps({"actions/checkout": {"ref": commit}}), encoding="utf-8")
        errors = CHECKER.validate_workflow(workflow, pins)
        self.assertTrue(any("release tag" in error for error in errors))

    def test_changed_build_file_rejects_individual_bluetape_bom(self):
        build = self.root / "sample.gradle.kts"
        build.write_text("dependencies { implementation(platform(libs.bluetape4k.graph.bom)) }\n", encoding="utf-8")
        errors = CHECKER.validate_changed_files(self.root, ["sample.gradle.kts"])
        self.assertTrue(any("individual Bluetape BOM" in error for error in errors))

    def test_train_scope_accepts_exact_node_and_refs(self):
        manifest = self.manifest()
        manifest["nodes"][1].update({
            "oid_policy": "exact",
            "base_oid": "b" * 40,
            "head_oid": "a" * 40,
        })
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="develop",
            head_ref_name="branch/A1",
            base_oid="b" * 40,
            head_oid="a" * 40,
        )
        self.assertEqual([], errors)

    def test_train_scope_accepts_reviewed_ancestor_with_real_git_history(self):
        base_oid, implementation_oid, head_oid = self._reviewed_ancestor_history()
        manifest = self._reviewed_manifest()
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="origin/develop",
            head_ref_name="branch/A1",
            base_oid=base_oid,
            head_oid=head_oid,
            repository_root=self.root,
        )
        self.assertEqual([], errors)
        self.assertNotEqual(implementation_oid, head_oid)

    def test_train_scope_accepts_active_reviewed_ancestor_bound_to_manifest_oid(self):
        base_oid, implementation_oid, head_oid = self._reviewed_ancestor_history()
        manifest = self._reviewed_manifest()
        node = manifest["nodes"][1]
        node.update({
            "state": "READY",
            "receipt_status": "PASS",
            "receipt_id": "receipt-a1",
            "checksum": "d" * 64,
            "reviewed_implementation_oid": implementation_oid,
        })
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="origin/develop",
            head_ref_name="branch/A1",
            base_oid=base_oid,
            head_oid=head_oid,
            repository_root=self.root,
        )
        self.assertEqual([], errors)

    def test_train_scope_accepts_lineage_marker_after_coordinator_rebase(self):
        base_oid, stale_marker_oid, current_marker_oid, head_oid = self._rebased_reviewed_ancestor_history()
        manifest = self._reviewed_manifest()
        node = manifest["nodes"][1]
        node.update({
            "state": "READY",
            "receipt_status": "PASS",
            "receipt_id": "receipt-a1-lineage",
            "checksum": "d" * 64,
            "reviewed_implementation_oid": stale_marker_oid,
            "reviewed_marker_binding": "lineage",
        })
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="origin/develop",
            head_ref_name="branch/A1",
            base_oid=base_oid,
            head_oid=head_oid,
            repository_root=self.root,
        )
        self.assertEqual([], errors)
        self.assertNotEqual(stale_marker_oid, current_marker_oid)

    def test_train_scope_rejects_manifest_marker_oid_mismatch(self):
        base_oid, _, head_oid = self._reviewed_ancestor_history()
        manifest = self._reviewed_manifest()
        node = manifest["nodes"][1]
        node.update({
            "state": "READY",
            "receipt_status": "PASS",
            "receipt_id": "receipt-a1",
            "checksum": "d" * 64,
            "reviewed_implementation_oid": "a" * 40,
        })
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="origin/develop",
            head_ref_name="branch/A1",
            base_oid=base_oid,
            head_oid=head_oid,
            repository_root=self.root,
        )
        self.assertTrue(any("must match manifest reviewed_implementation_oid" in error for error in errors))

    def test_train_scope_rejects_reviewed_ancestor_self_reference(self):
        base_oid, implementation_oid, _ = self._reviewed_ancestor_history()
        errors = CHECKER.validate_train_scope(
            self._reviewed_manifest(),
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="origin/develop",
            head_ref_name="branch/A1",
            base_oid=base_oid,
            head_oid=implementation_oid,
            repository_root=self.root,
        )
        self.assertTrue(any("must be a prior commit, not the PR head" in error for error in errors))

    def test_train_scope_rejects_reviewed_ancestor_outside_history(self):
        base_oid, _, head_oid = self._reviewed_ancestor_history(marker_text="a" * 40)
        errors = CHECKER.validate_train_scope(
            self._reviewed_manifest(),
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="origin/develop",
            head_ref_name="branch/A1",
            base_oid=base_oid,
            head_oid=head_oid,
            repository_root=self.root,
        )
        self.assertTrue(any("must be an ancestor of the PR head" in error for error in errors))

    def test_train_scope_rejects_code_changes_after_reviewed_ancestor(self):
        base_oid, _, head_oid = self._reviewed_ancestor_history(code_after_marker=True)
        errors = CHECKER.validate_train_scope(
            self._reviewed_manifest(),
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="origin/develop",
            head_ref_name="branch/A1",
            base_oid=base_oid,
            head_oid=head_oid,
            repository_root=self.root,
        )
        self.assertTrue(any("evidence tail may change only the review artifact" in error for error in errors))

    def test_train_scope_rejects_missing_reviewed_ancestor_marker(self):
        base_oid, _, head_oid = self._reviewed_ancestor_history(marker_text="not-a-sha")
        errors = CHECKER.validate_train_scope(
            self._reviewed_manifest(),
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="origin/develop",
            head_ref_name="branch/A1",
            base_oid=base_oid,
            head_oid=head_oid,
            repository_root=self.root,
        )
        self.assertTrue(any("reviewed_implementation_oid marker" in error for error in errors))

    def test_train_scope_rejects_bootstrap_node_without_bootstrap_context(self):
        manifest = self.manifest()
        manifest["nodes"][1]["oid_policy"] = "bootstrap"
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/A1/Changed.kt"],
            base_ref_name="develop",
            head_ref_name="branch/A1",
            base_oid="b" * 40,
            head_oid="a" * 40,
        )
        self.assertTrue(any("invalid oid_policy bootstrap" in error for error in errors))

    def test_workflow_includes_follow_up_kotlin_paths(self):
        workflow = SCRIPT.parent.parent / "workflows/ecosystem-reuse-gate.yml"
        text = workflow.read_text(encoding="utf-8")
        self.assertIn("optimization/field-service-dispatch/src/main/kotlin/**", text)
        self.assertIn("optimization/field-service-dispatch/src/test/kotlin/**", text)

    def test_train_scope_rejects_paths_outside_one_node(self):
        errors = CHECKER.validate_train_scope(
            self.manifest(),
            ["src/A1/Changed.kt", "src/P0/Changed.kt"],
            base_ref_name="develop",
            head_ref_name="branch/A1",
            base_oid="b" * 40,
            head_oid="a" * 40,
        )
        self.assertTrue(any("exactly one manifest track" in error for error in errors))

    def test_train_scope_rejects_wrong_refs_and_oids(self):
        manifest = self.manifest()
        manifest["nodes"][1]["oid_policy"] = "exact"
        manifest["nodes"][1]["base_oid"] = "b" * 40
        manifest["nodes"][1]["head_oid"] = "a" * 40
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/A1/Changed.kt"],
            base_ref_name="feature/base",
            head_ref_name="branch/P0",
            base_oid="c" * 40,
            head_oid="d" * 40,
        )
        self.assertTrue(any("expected_base_ref" in error for error in errors))
        self.assertTrue(any("expected_head_ref" in error for error in errors))
        self.assertTrue(any("base_oid" in error for error in errors))
        self.assertTrue(any("head_oid" in error for error in errors))

    def test_train_scope_selects_follow_up_scope_by_exact_refs_and_oids(self):
        manifest = self.manifest_with_follow_up_scope()
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/F1/Changed.kt", "docs/review/F1-child-7tier.md"],
            base_ref_name="branch/F1",
            head_ref_name="branch/F1-child",
            base_oid="b" * 40,
            head_oid="a" * 40,
        )
        self.assertEqual([], errors)

    def test_train_scope_rejects_follow_up_scope_with_wrong_recorded_oid(self):
        manifest = self.manifest_with_follow_up_scope()
        manifest["follow_up_scopes"][0]["head_oid"] = "f" * 40
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/F1/Changed.kt", "docs/review/F1-child-7tier.md"],
            base_ref_name="branch/F1",
            head_ref_name="branch/F1-child",
            base_oid="b" * 40,
            head_oid="a" * 40,
        )
        self.assertTrue(any("head_oid" in error for error in errors))

    def test_train_scope_accepts_rebase_aware_follow_up_after_rebase(self):
        manifest = self.manifest_with_follow_up_scope()
        manifest["follow_up_scopes"][0] = self.follow_up_scope(oid_policy="rebase-aware")
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/F1/Changed.kt", "docs/review/F1-child-7tier.md"],
            base_ref_name="branch/F1",
            head_ref_name="branch/F1-child",
            base_oid="c" * 40,
            head_oid="d" * 40,
        )
        self.assertEqual([], errors)

    def test_train_scope_requires_sha_oids(self):
        errors = CHECKER.validate_train_scope(
            self.manifest(),
            ["src/A1/Changed.kt"],
            base_ref_name="develop",
            head_ref_name="branch/A1",
            base_oid="not-a-sha",
            head_oid="a" * 40,
        )
        self.assertTrue(any("base_oid must be a 40-hex SHA" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
