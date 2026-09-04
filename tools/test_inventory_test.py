#!/usr/bin/env python3
"""Focused tests for the release test-inventory helper."""

import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import test_inventory


class TestInventoryTest(unittest.TestCase):

    def test_counts_valid_junit_report(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "test.xml"
            report.write_text(
                """<?xml version="1.0"?>
<testsuite name="suite" failures="1" errors="2" skipped="3">
  <testcase classname="example.One" name="first"/>
  <testcase classname="example.Two" name="second"/>
</testsuite>
""",
                encoding="utf-8",
            )

            classes, tests, failures, skipped = test_inventory.inventory(directory)

        self.assertEqual({"example.One": 1, "example.Two": 1}, classes)
        self.assertEqual(2, tests)
        self.assertEqual(3, failures)
        self.assertEqual(3, skipped)

    def test_rejects_missing_root(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing"

            with self.assertRaisesRegex(test_inventory.InventoryError, "does not exist"):
                test_inventory.inventory(missing)

    def test_rejects_root_without_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(test_inventory.InventoryError, "no XML test reports"):
                test_inventory.inventory(directory)

    def test_rejects_file_as_root(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "test.xml"
            report.write_text("<testsuite/>", encoding="utf-8")

            with self.assertRaisesRegex(test_inventory.InventoryError, "not a directory"):
                test_inventory.inventory(report)

    def test_rejects_malformed_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "test.xml").write_text(
                "<testsuite><testcase></testsuite>", encoding="utf-8"
            )

            with self.assertRaisesRegex(test_inventory.InventoryError, "cannot read XML"):
                test_inventory.inventory(directory)

    def test_rejects_xml_without_test_suite(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "test.xml").write_text("<report/>", encoding="utf-8")

            with self.assertRaisesRegex(test_inventory.InventoryError, "no <testsuite>"):
                test_inventory.inventory(directory)

    def test_rejects_invalid_result_count(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "test.xml").write_text(
                '<testsuite failures="many"/>', encoding="utf-8"
            )

            with self.assertRaisesRegex(test_inventory.InventoryError, "non-integer"):
                test_inventory.inventory(directory)

    def test_rejects_negative_result_count(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "test.xml").write_text(
                '<testsuite skipped="-1"/>', encoding="utf-8"
            )

            with self.assertRaisesRegex(test_inventory.InventoryError, "negative"):
                test_inventory.inventory(directory)

    def test_main_reports_input_error_without_traceback(self):
        error_output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing"
            with contextlib.redirect_stderr(error_output):
                exit_code = test_inventory.main(["test_inventory.py", str(missing)])

        self.assertEqual(2, exit_code)
        self.assertIn("test_inventory:", error_output.getvalue())
        self.assertIn("does not exist", error_output.getvalue())


if __name__ == "__main__":
    unittest.main()
