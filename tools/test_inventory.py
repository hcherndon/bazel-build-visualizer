#!/usr/bin/env python3
"""The parity checklist's test-inventory diff (migration plan section 8).

Reads every JUnit XML file under one or two roots and prints the per-class
test counts plus totals; with two roots it diffs the inventories and exits
nonzero on any difference. It works on Bazel's testlogs tree
(bazel-testlogs/**/test.xml) and worked identically on Gradle's
(*/build/test-results/test/TEST-*.xml) while that tree still existed —
the 2026-08-25 signoff compared the two with this logic and recorded
216 classes / 1852 tests / 0 failures / 0 skipped on both sides.

Usage:
  tools/test_inventory.py bazel-testlogs
  tools/test_inventory.py <old-xml-root> <new-xml-root>

Each root must be an existing directory containing at least one well-formed
JUnit XML report. Missing or unreliable input is an error, never an empty run.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


class InventoryError(Exception):
    """Raised when a test inventory cannot be read reliably."""


def inventory(root):
    """{class name: test count} plus (tests, failures, skipped) totals."""
    root = Path(root)
    if not root.exists():
        raise InventoryError(f"{root}: inventory root does not exist")
    if not root.is_dir():
        raise InventoryError(f"{root}: inventory root is not a directory")

    xml_files = sorted(root.rglob("*.xml"))
    if not xml_files:
        raise InventoryError(f"{root}: no XML test reports found")

    classes = {}
    tests = failures = skipped = 0
    for file in xml_files:
        try:
            parsed = ET.parse(file).getroot()
        except (ET.ParseError, OSError) as error:
            raise InventoryError(f"{file}: cannot read XML test report: {error}") from error

        suites = list(parsed.iter("testsuite"))
        if not suites:
            raise InventoryError(f"{file}: XML test report has no <testsuite>")

        for suite in suites:
            for case in suite.iter("testcase"):
                name = case.get("classname") or suite.get("name") or "?"
                classes[name] = classes.get(name, 0) + 1
                tests += 1
            try:
                suite_failures = int(suite.get("failures", 0))
                suite_errors = int(suite.get("errors", 0))
                suite_skipped = int(suite.get("skipped", 0))
            except ValueError as error:
                raise InventoryError(
                    f"{file}: <testsuite> has a non-integer result count"
                ) from error
            if min(suite_failures, suite_errors, suite_skipped) < 0:
                raise InventoryError(f"{file}: <testsuite> has a negative result count")
            failures += suite_failures + suite_errors
            skipped += suite_skipped
    return classes, tests, failures, skipped


def describe(label, classes, tests, failures, skipped):
    print(f"{label}: {len(classes)} classes, {tests} tests,"
          f" {failures} failures/errors, {skipped} skipped")


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 2

    try:
        inventories = [inventory(root) for root in argv[1:]]
    except InventoryError as error:
        print(f"test_inventory: {error}", file=sys.stderr)
        return 2

    a = inventories[0]
    describe(argv[1], *a)
    if len(argv) == 2:
        for name in sorted(a[0]):
            print(f"  {name}: {a[0][name]}")
        return 0
    b = inventories[1]
    describe(argv[2], *b)
    delta = 0
    for name in sorted(set(a[0]) | set(b[0])):
        left, right = a[0].get(name), b[0].get(name)
        if left != right:
            print(f"  DIFFERS {name}: {left} vs {right}")
            delta += 1
    if delta == 0 and a[1:] == b[1:]:
        print("inventories identical")
        return 0
    print(f"{delta} classes differ")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
