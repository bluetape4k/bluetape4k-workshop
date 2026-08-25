import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-ecosystem-reuse.py")
SPEC = importlib.util.spec_from_file_location("check_ecosystem_reuse", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(CHECKER)


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
        (self.root / "src/source.kt").write_text("import io.bluetape4k.assertions.shouldBeEqualTo\n", encoding="utf-8")
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
                "head_oid": None if state == "PLANNED" else "a" * 40,
                "base_oid": None if state == "PLANNED" else "b" * 40,
                "parent_oid": None,
                "merge_base_oid": None if state == "PLANNED" else "c" * 40,
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

    def test_valid_row_with_existing_source_and_test_paths(self):
        errors = CHECKER.validate_inventory(self.root, self.inventory([self.row()]))
        self.assertEqual([], errors)

    def test_actual_import_must_contain_exact_capability_api(self):
        errors = CHECKER.validate_inventory(
            self.root,
            self.inventory([self.row(capability_api="missingBluetapeMatcher")]),
        )
        self.assertTrue(any("exact capability_api token" in error for error in errors))

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
        errors = CHECKER.validate_train_scope(
            manifest,
            ["src/A1/Changed.kt", "docs/review/A1-7tier.md"],
            base_ref_name="develop",
            head_ref_name="branch/A1",
            base_oid="b" * 40,
            head_oid="a" * 40,
        )
        self.assertEqual([], errors)

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
