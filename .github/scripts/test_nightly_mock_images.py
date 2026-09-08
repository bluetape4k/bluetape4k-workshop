import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "nightly_mock_images", Path(__file__).with_name("nightly-mock-images.py")
)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class NightlyMockImagesTest(unittest.TestCase):
    def test_official_catalog_version_is_the_image_version(self):
        with tempfile.TemporaryDirectory() as directory:
            catalog = Path(directory) / "libs.versions.toml"
            catalog.write_text(
                '[versions]\nbluetape4k-dependencies-version = "2.0.0"\n'
            )
            self.assertEqual(CHECKER.read_version(catalog), "2.0.0")

    def test_non_release_versions_are_rejected(self):
        for version in ("2.0.0-SNAPSHOT", "develop", "", "2.0.0\nother=value"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                CHECKER.image_names(version)

    def test_snapshot_train_uses_develop_source_and_release_image_tag(self):
        self.assertEqual(
            CHECKER.resolve_consumer_version("2.1.0-SNAPSHOT", allow_snapshot=True),
            ("2.1.0", "develop"),
        )

    def test_snapshot_train_remains_explicitly_opt_in(self):
        with self.assertRaises(ValueError):
            CHECKER.resolve_consumer_version("2.1.0-SNAPSHOT")

    def test_snapshot_catalog_resolves_to_release_image_and_develop_source(self):
        with tempfile.TemporaryDirectory() as directory:
            catalog = Path(directory) / "libs.versions.toml"
            catalog.write_text(
                '[versions]\nbluetape4k-dependencies-version = "2.1.0-SNAPSHOT"\n'
            )
            self.assertEqual(CHECKER.read_version(catalog, allow_snapshot=True), "2.1.0")
            self.assertEqual(
                CHECKER.read_source_ref(catalog, allow_snapshot=True), "develop"
            )

    def test_trusted_source_ref_must_match_catalog_resolution(self):
        self.assertEqual(CHECKER.validate_source_ref("develop", "develop"), "develop")
        with self.assertRaisesRegex(ValueError, "trusted workflow ref"):
            CHECKER.validate_source_ref("develop", "2.1.0")

    def test_inspects_both_exact_consumer_images(self):
        with patch.object(CHECKER.subprocess, "run") as run:
            CHECKER.inspect_images("2.0.0")
        self.assertEqual(
            run.call_args.args[0],
            [
                "docker",
                "image",
                "inspect",
                "bluetape4k/mock-web-server:2.0.0",
                "bluetape4k/mock-webflux-server:2.0.0",
            ],
        )
        self.assertTrue(run.call_args.kwargs["check"])

    def test_missing_consumer_tag_fails_even_if_another_version_was_built(self):
        with (
            patch.object(
                CHECKER.subprocess,
                "run",
                side_effect=subprocess.CalledProcessError(1, "docker"),
            ),
            self.assertRaises(subprocess.CalledProcessError),
        ):
            CHECKER.inspect_images("2.0.0")

    def test_workflow_uses_catalog_version_and_checks_images_before_tests(self):
        workflow = (Path(__file__).parents[1] / "workflows/nightly.yml").read_text()
        self.assertIn(
            "BLUETAPE4K_PROJECTS_REF: 'develop'",
            workflow,
        )
        self.assertIn(
            "nightly-mock-images.py --allow-snapshot "
            '--expected-source-ref "$BLUETAPE4K_PROJECTS_REF"',
            workflow,
        )
        self.assertIn("ref: ${{ env.BLUETAPE4K_PROJECTS_REF }}", workflow)
        self.assertNotIn("steps.mock-version.outputs.source_ref", workflow)
        self.assertLess(
            workflow.index("nightly-mock-images.py --allow-snapshot --inspect"),
            workflow.index("- name: Run tests"),
        )

    def test_historical_nightly_scope_is_ignored_and_active_scope_rejects_drift(self):
        root = Path(__file__).parents[2]
        spec = importlib.util.spec_from_file_location(
            "ecosystem_checker", Path(__file__).with_name("check-ecosystem-reuse.py")
        )
        checker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checker)
        manifest = json.loads((root / "docs/ecosystem-reuse-train.json").read_text())
        historical_scope = next(
            s
            for s in manifest["follow_up_scopes"]
            if s["scope_id"] == "issue-948-949-nightly-recovery"
        )
        self.assertEqual(historical_scope["lifecycle"], "MERGED")
        options = {
            "base_ref_name": "develop",
            "head_ref_name": historical_scope["expected_head_ref"],
            "base_oid": "a" * 40,
            "head_oid": "b" * 40,
        }
        self.assertTrue(
            checker.validate_train_scope(
                manifest,
                historical_scope["allowed_paths"],
                **options,
            )
        )

        scope = {
            **historical_scope,
            "scope_id": "active-nightly-recovery-test",
            "lifecycle": "ACTIVE",
        }
        manifest["follow_up_scopes"].append(scope)
        self.assertEqual(
            checker.validate_train_scope(manifest, scope["allowed_paths"], **options),
            [],
        )
        self.assertTrue(
            checker.validate_train_scope(
                manifest,
                scope["allowed_paths"],
                **{**options, "head_ref_name": "fix/unrelated"},
            )
        )
        self.assertTrue(
            checker.validate_train_scope(
                manifest,
                [*scope["allowed_paths"], "unrelated/production.kt"],
                **options,
            )
        )

    def test_kafka_sanitizer_preserves_shell_argument_boundaries(self):
        workflow = (Path(__file__).parents[1] / "workflows/nightly.yml").read_text()
        step = workflow.split("- name: Sanitize Kafka failover artifacts", 1)[1]
        step = step.split("- name: Upload test results", 1)[0]
        self.assertIn("        run: |\n", step)
        command = step.split("        run: |\n", 1)[1]
        command = "\n".join(line[10:] for line in command.splitlines())
        capture = command.replace(
            "./scripts/validate-kafka-failover-artifacts.sh", "printf '%s\\0'", 1
        )
        result = subprocess.run(
            ["bash", "-c", capture],
            env={"KAFKA_FAILOVER_RUN_ID": "nightly-contract"},
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual(
            result.stdout.split("\0")[:-1],
            [
                "--module",
                "messaging/kafka-multi-broker-failover",
                "--run-id",
                "nightly-contract",
                "--staging",
                "messaging/kafka-multi-broker-failover/build/reports/kafka-failover/sanitized",
            ],
        )


if __name__ == "__main__":
    unittest.main()
