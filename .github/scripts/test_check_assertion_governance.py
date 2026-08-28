#!/usr/bin/env python3
"""Tests for the assertion matcher governance guard."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-assertion-governance.py")
SPEC = importlib.util.spec_from_file_location("check_assertion_governance", SCRIPT)
GUARD = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = GUARD
SPEC.loader.exec_module(GUARD)


class AssertionGovernanceGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def write(self, relative: str, content: str) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def test_detects_generic_boolean_and_null_matchers_with_line_numbers(self):
        path = self.write(
            "commerce/sample/src/test/kotlin/SampleTest.kt",
            """package sample

            import io.bluetape4k.assertions.shouldBeEqualTo

            fun test() {
                actual.shouldBeEqualTo(true)
                nullable.shouldBeEqualTo(null)
                // ignored.shouldBeEqualTo(false)
                val text = \"ignored.shouldBeEqualTo(false)\"
            }
            """,
        )

        result = GUARD.scan_repository(self.root)

        self.assertEqual(
            [(finding.path, finding.line, finding.rule) for finding in result.findings],
            [
                (path.relative_to(self.root).as_posix(), 6, "generic-boolean-or-null-matcher"),
                (path.relative_to(self.root).as_posix(), 7, "generic-boolean-or-null-matcher"),
            ],
        )

    def test_detects_legacy_imports_only_in_consumer_tests(self):
        path = self.write(
            "commerce/sample/src/test/kotlin/SampleTest.kt",
            """package sample
            import kotlin.test.assertEquals
            import org.junit.jupiter.api.Assertions.assertTrue
            """,
        )

        result = GUARD.scan_repository(self.root)

        self.assertEqual(
            [(finding.path, finding.line, finding.rule) for finding in result.findings],
            [
                (path.relative_to(self.root).as_posix(), 2, "legacy-assertion-import"),
                (path.relative_to(self.root).as_posix(), 3, "legacy-assertion-import"),
            ],
        )
        self.assertEqual(result.allowlisted_legacy_imports, 0)

    def test_build_logic_legacy_imports_are_explicitly_allowlisted(self):
        self.write(
            "build-logic/src/test/kotlin/BuildLogicTest.kt",
            """package buildlogic
            import kotlin.test.assertEquals
            import kotlin.test.assertTrue
            """,
        )

        result = GUARD.scan_repository(self.root)

        self.assertEqual(result.findings, [])
        self.assertEqual(result.allowlisted_legacy_imports, 2)

    def test_framework_dsl_and_non_boolean_equality_are_not_findings(self):
        self.write(
            "commerce/sample/src/test/kotlin/SampleTest.kt",
            """package sample
            import io.bluetape4k.assertions.shouldBeEqualTo

            fun test() {
                response.expectStatus().isEqualTo(200)
                response.jsonPath(\"$.enabled\").isEqualTo(true)
                actual.shouldBeEqualTo(\"true\")
                actual.shouldBeEqualTo(1)
            }
            """,
        )

        result = GUARD.scan_repository(self.root)

        self.assertEqual(result.findings, [])


if __name__ == "__main__":
    unittest.main()
