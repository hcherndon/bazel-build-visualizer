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
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def inventory(root):
    """{class name: test count} plus (tests, failures, skipped) totals."""
    classes = {}
    tests = failures = skipped = 0
    for file in sorted(Path(root).rglob("*.xml")):
        try:
            parsed = ET.parse(file).getroot()
        except ET.ParseError:
            continue
        for suite in parsed.iter("testsuite"):
            for case in suite.iter("testcase"):
                name = case.get("classname") or suite.get("name") or "?"
                classes[name] = classes.get(name, 0) + 1
                tests += 1
            failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
    return classes, tests, failures, skipped


def describe(label, classes, tests, failures, skipped):
    print(f"{label}: {len(classes)} classes, {tests} tests,"
          f" {failures} failures/errors, {skipped} skipped")


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 2
    a = inventory(argv[1])
    describe(argv[1], *a)
    if len(argv) == 2:
        for name in sorted(a[0]):
            print(f"  {name}: {a[0][name]}")
        return 0
    b = inventory(argv[2])
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
