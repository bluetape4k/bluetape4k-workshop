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
            catalog.write_text('[versions]\nbluetape4k-dependencies-version = "2.0.0"\n')
            self.assertEqual(CHECKER.read_version(catalog), "2.0.0")

    def test_non_release_versions_are_rejected(self):
        for version in ("2.0.0-SNAPSHOT", "develop", "", "2.0.0\nother=value"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                CHECKER.image_names(version)

    def test_inspects_both_exact_consumer_images(self):
        with patch.object(CHECKER.subprocess, "run") as run:
            CHECKER.inspect_images("2.0.0")
        self.assertEqual(run.call_args.args[0], [
            "docker", "image", "inspect",
            "bluetape4k/mock-web-server:2.0.0",
            "bluetape4k/mock-webflux-server:2.0.0",
        ])
        self.assertTrue(run.call_args.kwargs["check"])

    def test_missing_consumer_tag_fails_even_if_another_version_was_built(self):
        with patch.object(CHECKER.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "docker")):
            with self.assertRaises(subprocess.CalledProcessError):
                CHECKER.inspect_images("2.0.0")

    def test_workflow_uses_catalog_version_and_checks_images_before_tests(self):
        workflow = (Path(__file__).parents[1] / "workflows/nightly.yml").read_text()
        self.assertIn('nightly-mock-images.py --github-output "$GITHUB_OUTPUT"', workflow)
        self.assertIn("ref: ${{ steps.mock-version.outputs.version }}", workflow)
        self.assertLess(
            workflow.index("nightly-mock-images.py --inspect"),
            workflow.index("- name: Run tests"),
        )

    def test_registered_nightly_scope_rejects_wrong_branch_and_extra_paths(self):
        root = Path(__file__).parents[2]
        spec = importlib.util.spec_from_file_location(
            "ecosystem_checker", Path(__file__).with_name("check-ecosystem-reuse.py")
        )
        checker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checker)
        manifest = json.loads((root / "docs/ecosystem-reuse-train.json").read_text())
        scope = next(s for s in manifest["follow_up_scopes"] if s["scope_id"] == "issue-948-949-nightly-recovery")
        options = dict(base_ref_name="develop", head_ref_name=scope["expected_head_ref"],
                       base_oid="a" * 40, head_oid="b" * 40)
        self.assertEqual(checker.validate_train_scope(manifest, scope["allowed_paths"], **options), [])
        self.assertTrue(checker.validate_train_scope(
            manifest, scope["allowed_paths"], **{**options, "head_ref_name": "fix/unrelated"}
        ))
        self.assertTrue(checker.validate_train_scope(
            manifest, [*scope["allowed_paths"], "unrelated/production.kt"], **options
        ))


if __name__ == "__main__":
    unittest.main()
