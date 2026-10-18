#!/usr/bin/env python3
"""
Turns quic-interop-runner --json results into a pass/fail verdict for the
interop workflow, so that a job is green only when every test case it ran
succeeded.

Usage:
    check_results.py RESULTS_DIR FILE...    verdict on stdout, markdown table
                                            appended to $GITHUB_STEP_SUMMARY
    check_results.py --self-test

Each FILE is a result file name expected in RESULTS_DIR (the runner writes one
per direction). Exit status is 1 if any test case failed or an expected file
is missing or unreadable (the runner crashed or never ran), otherwise 0.
Cases the peer does not implement ("unsupported") are reported but are not
failures: they say nothing about gumdrop.
"""

import json
import os
import sys
import tempfile


def verdicts(path):
    """Returns {abbr: (name, result)} for one runner result file."""
    with open(path) as handle:
        data = json.load(handle)
    found = {}
    for row in data.get("results", []):
        for case in row:
            found[case["abbr"]] = (case["name"], case["result"])
    return found


def check(results_dir, files):
    """Returns (ok, markdown) for the expected result files."""
    ok = True
    lines = []
    for name in files:
        path = os.path.join(results_dir, name)
        label = name[:-5] if name.endswith(".json") else name
        if not os.path.isfile(path):
            ok = False
            lines.append("| %s | no result file, the run did not complete | |" % label)
            continue
        try:
            found = verdicts(path)
        except (ValueError, KeyError, OSError) as error:
            ok = False
            lines.append("| %s | unreadable result file: %s | |" % (label, error))
            continue
        failed = sorted(a for a, (_, r) in found.items() if r == "failed")
        unsupported = sorted(a for a, (_, r) in found.items() if r == "unsupported")
        passed = len(found) - len(failed) - len(unsupported)
        if failed or not found:
            ok = False
        lines.append("| %s | %d passed, %d failed, %d unsupported | %s |" % (
            label, passed, len(failed), len(unsupported),
            ", ".join("%s (%s)" % (found[a][0], a) for a in failed) or "none"))
    table = "| Direction | Result | Failed cases |\n|---|---|---|\n" + "\n".join(lines)
    return ok, table


def self_test():
    with tempfile.TemporaryDirectory() as directory:
        def write(name, results):
            with open(os.path.join(directory, name), "w") as handle:
                json.dump({"results": [[
                    {"abbr": a, "name": a.lower(), "result": r} for a, r in results]]}, handle)
        write("good.json", [("H", "succeeded"), ("E", "unsupported")])
        write("bad.json", [("H", "succeeded"), ("R", "failed")])
        write("empty.json", [])
        assert check(directory, ["good.json"])[0]
        assert not check(directory, ["bad.json"])[0]
        assert not check(directory, ["empty.json"])[0]
        assert not check(directory, ["missing.json"])[0]
        assert not check(directory, ["good.json", "bad.json"])[0]
    print("self-test ok")


def main(argv):
    if argv[1:] == ["--self-test"]:
        self_test()
        return 0
    if len(argv) < 3:
        print(__doc__)
        return 2
    ok, table = check(argv[1], argv[2:])
    print(table)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as handle:
            handle.write("### Verdict\n\n%s\n\n" % table)
    print("PASS" if ok else "FAIL: at least one test case failed or did not run")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
