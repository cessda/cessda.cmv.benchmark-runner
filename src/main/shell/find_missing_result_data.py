#!/usr/bin/env python3
"""
find_missing_result_data.py

Recursively scans a directory tree of Benchmark Runner output for two
different shapes of "Champion was overwhelmed" evidence:

1. Result files (<guid>.json) whose "result" field contains the phrase
   "result data not found" — the shape written to disk *before* the
   backoff/retry fix, and still the shape RunBenchmarkAssessment falls
   back to if it ever writes a response without noticing an overwhelmed
   indicator. Post-fix this section should normally be empty: the
   whole point of the fix is that a response like this now gets
   retried instead of written to disk. Treat any non-zero count here
   as a regression to investigate, not routine signal.

2. Error files (error_<guid>.json) written after all retries were
   exhausted. Since the backoff/retry fix (see
   cessda/cessda.cmv.benchmark-runner), a GUID that stayed overwhelmed
   through every retry attempt no longer produces a result file at
   all — it produces an error file instead, whose "error" text reads
   "... contained overwhelmed indicator(s): NAME1, NAME2, ...". This
   script parses those names back out, so the by-test-name breakdown
   this tool has always produced still means the same thing it used
   to: how many times did each test end up overwhelmed, regardless of
   whether that happened via a result file (before the fix) or an
   error file (after it).

Other error files (persistent HTTP 500/502/504, SSL handshake
failures, timeouts, etc.) are also reported, broken down by category,
for context on what's actually still failing.

Usage:
    python3 find_missing_result_data.py /path/to/root/dir
    python3 find_missing_result_data.py /path/to/root/dir --csv out.csv
    python3 find_missing_result_data.py /path/to/root/dir --error-csv errors.csv
"""

import argparse
import csv
import json
import re
import sys
from collections import Counter
from pathlib import Path

TARGET_PHRASE = "result data not found"

# Matches the tail of the exception message RunBenchmarkAssessment
# writes into error_<guid>.json's "error" field when every retry
# attempt still came back with at least one overwhelmed indicator:
#   "Champion response for GUID <guid> contained overwhelmed
#    indicator(s): F1_GUID, A1_1"
# This is the current (commit db8c257+) phrasing, which names the
# indicator(s). An interim build (commit 184a14f only) reported the
# same condition without naming anything:
#   "Champion response for GUID <guid> contained one or more
#    overwhelmed indicators (result data not found)"
# Both are recognized so historical results from either build classify
# correctly; only the former yields indicator names.
OVERWHELMED_INDICATORS_RE = re.compile(
    r"overwhelmed indicator\(s\):\s*(.+)$"
)
OVERWHELMED_LEGACY_RE = re.compile(
    r"overwhelmed indicators? \(result data not found\)"
)

# Matches "Server error: HTTP 500" / 502 / 504 (and the older "Gateway
# error: HTTP 502/504" phrasing from before RunBenchmarkAssessment
# started retrying on 500 too — the wording differs but the substring
# this looks for doesn't).
HTTP_ERROR_RE = re.compile(r"HTTP (\d{3})")


# ── Result-file scanning (unchanged behaviour — pre-fix data shape) ─────────

def find_matches_in_obj(obj, test_name_hint=None):
    """
    Walk a parsed JSON object looking for dicts that have a "result"
    key whose value contains TARGET_PHRASE. Yields (test_name, result_value).

    test_name_hint carries the most recent dict key we descended
    through, since that's what identifies the test (e.g. "F1_GUID").
    """
    if isinstance(obj, dict):
        result_val = obj.get("result")
        if isinstance(result_val, str) and TARGET_PHRASE in result_val:
            yield (test_name_hint, result_val)

        for key, value in obj.items():
            yield from find_matches_in_obj(value, test_name_hint=key)

    elif isinstance(obj, list):
        for item in obj:
            yield from find_matches_in_obj(item, test_name_hint=test_name_hint)


def iter_json_files(root, name_glob="*.json", exclude_prefix="error_"):
    """
    Yield *.json files under root matching name_glob, skipping files
    under any 'pages' directory and (when exclude_prefix is set) files
    whose name starts with it — used to keep result-file and
    error-file scans from double-counting each other.
    """
    root = Path(root).resolve()
    all_files = sorted(root.rglob(name_glob))
    for fp in all_files:
        if "pages" in fp.relative_to(root).parent.parts:
            continue
        if exclude_prefix and fp.name.startswith(exclude_prefix):
            continue
        yield fp


def scan_result_files(root_dir):
    """
    Walk root_dir for *.json result files (error_*.json excluded) and
    collect TARGET_PHRASE matches. Returns a list of dicts with keys:
        subdirectory, file, test_name, result
    """
    root = Path(root_dir).resolve()
    results = []
    json_files = list(iter_json_files(root))

    if not json_files:
        print(f"No result .json files found under {root}", file=sys.stderr)

    for file_path in json_files:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except (json.JSONDecodeError, UnicodeDecodeError, OSError) as e:
            print(f"WARNING: could not read/parse {file_path}: {e}",
                  file=sys.stderr)
            continue

        rel_parent = file_path.parent.relative_to(root)
        subdirectory = str(rel_parent) if str(rel_parent) != "." else "(root)"

        for test_name, result_val in find_matches_in_obj(data):
            results.append({
                "subdirectory": subdirectory,
                "file": file_path.name,
                "test_name": test_name,
                "result": result_val,
            })

    return results


# ── Error-file scanning (new — post-fix data shape) ─────────────────────────

def classify_error(entry):
    """
    Categorise one parsed error_<guid>.json entry from its "error" /
    "cause" / "errorType" fields.

    Both "error" and "cause" are checked (in that order) because a
    build without the duplicate-error-file fix (RunBenchmarkAssessment
    commit e4bfdc3) writes a second, wrapping file whose own "error"
    text is just "All retries exhausted for GUID: ..." — the actually
    useful detail (HTTP code, or the overwhelmed-indicator message)
    lives one level down, in its "cause" field. Overwhelmed-indicator
    failures and persistent gateway/server errors also both carry
    errorType "IOException" on older builds, so message text — not
    errorType alone — is what distinguishes them there.

    The current build (RunBenchmarkAssessment commit onwards) instead
    writes a dedicated "overwhelmedIndicators" array and a specific
    errorType of "OverwhelmedIndicatorException" — either one alone is
    an unambiguous, non-regex signal, and is checked first.
    """
    if entry.get("overwhelmedIndicators"):
        return "overwhelmed_indicator"

    error_type = entry.get("errorType") or "Unknown"
    if error_type == "OverwhelmedIndicatorException":
        return "overwhelmed_indicator"

    texts = [entry.get("error") or "", entry.get("cause") or ""]

    for text in texts:
        if OVERWHELMED_INDICATORS_RE.search(text) or OVERWHELMED_LEGACY_RE.search(text):
            return "overwhelmed_indicator"

    for text in texts:
        m = HTTP_ERROR_RE.search(text)
        if m:
            return f"http_{m.group(1)}"

    return error_type


def scan_error_files(root_dir):
    """
    Walk root_dir for error_*.json files and classify each. Returns a
    list of dicts with keys:
        subdirectory, file, guid, category, error, overwhelmed_indicators
    overwhelmed_indicators is a (possibly empty) list of test names.
    Preferentially read straight from the "overwhelmedIndicators" JSON
    array the current build writes; falls back to regex-parsing the
    error/cause text (and yields an empty list) for older builds that
    only recorded the unnamed phrasing.
    """
    root = Path(root_dir).resolve()
    results = []
    error_files = list(iter_json_files(root, name_glob="error_*.json",
                                        exclude_prefix=None))

    for file_path in error_files:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                entry = json.load(f)
        except (json.JSONDecodeError, UnicodeDecodeError, OSError) as e:
            print(f"WARNING: could not read/parse {file_path}: {e}",
                  file=sys.stderr)
            continue

        rel_parent = file_path.parent.relative_to(root)
        subdirectory = str(rel_parent) if str(rel_parent) != "." else "(root)"

        category = classify_error(entry)
        overwhelmed_indicators = []
        if category == "overwhelmed_indicator":
            structured = entry.get("overwhelmedIndicators")
            if structured:
                overwhelmed_indicators = [str(name) for name in structured]
            else:
                for text in (entry.get("error") or "", entry.get("cause") or ""):
                    m = OVERWHELMED_INDICATORS_RE.search(text)
                    if m:
                        overwhelmed_indicators = [
                            name.strip() for name in m.group(1).split(",") if name.strip()
                        ]
                        break

        results.append({
            "subdirectory": subdirectory,
            "file": file_path.name,
            "guid": entry.get("guid"),
            "category": category,
            "error": entry.get("error"),
            "overwhelmed_indicators": overwhelmed_indicators,
        })

    return results


# ── Reporting ────────────────────────────────────────────────────────────────

def print_result_file_section(matches):
    print("=" * 78)
    print("SECTION 1: matches still found in result files (<guid>.json)")
    print("Expected to be ~0 after the overwhelmed-indicator retry fix —")
    print("a non-zero count here means a response was written to disk")
    print("without being caught, which is worth investigating directly.")
    print("=" * 78)

    if not matches:
        print("\nNo matches found in result files.\n")
        return

    print(f"\nFound {len(matches)} matching entries:\n")
    for m in matches:
        print(f"  subdir={m['subdirectory']:<30} "
              f"file={m['file']:<35} "
              f"test={m['test_name']}")

    test_counts = Counter(m["test_name"] for m in matches)
    print("\nCounts by test name:")
    for test_name, count in test_counts.most_common():
        print(f"  {test_name}: {count}")

    subdir_counts = Counter(m["subdirectory"] for m in matches)
    print("\nCounts by subdirectory:")
    for subdir, count in subdir_counts.most_common():
        print(f"  {subdir}: {count}")

    print("\nTest x subdirectory breakdown:")
    crosstab = Counter((m["test_name"], m["subdirectory"]) for m in matches)
    for (test_name, subdir), count in sorted(crosstab.items()):
        print(f"  {test_name} / {subdir}: {count}")
    print()


def print_error_file_section(error_entries):
    print("=" * 78)
    print("SECTION 2: error files (error_<guid>.json) — all failure categories")
    print("=" * 78)

    if not error_entries:
        print("\nNo error files found.\n")
        return

    category_counts = Counter(e["category"] for e in error_entries)
    print(f"\nFound {len(error_entries)} error file(s). Counts by category:")
    for category, count in category_counts.most_common():
        print(f"  {category}: {count}")

    # A build without the duplicate-error-file fix (commit e4bfdc3)
    # writes two error_*.json files per exhausted-retry failure, both
    # carrying the same "guid" field — so more than one error file
    # for the same guid is a strong signal these counts are inflated,
    # not that the same GUID genuinely failed more than once.
    guid_counts = Counter(e["guid"] for e in error_entries if e["guid"])
    duplicated_guids = {guid: n for guid, n in guid_counts.items() if n > 1}
    if duplicated_guids:
        total_extra = sum(n - 1 for n in duplicated_guids.values())
        print(f"\nNOTE: {len(duplicated_guids)} GUID(s) have more than one "
              f"error file ({total_extra} extra file(s) total) — almost "
              "certainly the pre-e4bfdc3 duplicate-write bug, not "
              "distinct failures. Category counts above likely overstate "
              "the real failure count for these GUIDs; rerun with the "
              "latest patch for clean, deduplicated counts.")

    subdir_counts = Counter(e["subdirectory"] for e in error_entries)
    print("\nCounts by subdirectory:")
    for subdir, count in subdir_counts.most_common():
        print(f"  {subdir}: {count}")

    print("\nCategory x subdirectory breakdown:")
    crosstab = Counter((e["category"], e["subdirectory"]) for e in error_entries)
    for (category, subdir), count in sorted(crosstab.items()):
        print(f"  {category} / {subdir}: {count}")
    print()


def print_overwhelmed_indicator_section(error_entries):
    print("=" * 78)
    print("SECTION 3: overwhelmed-indicator failures, by test name")
    print("(GUIDs that stayed overwhelmed through every retry attempt —")
    print(" this is the like-for-like successor to the old by-test-name")
    print(" breakdown, now sourced from error files instead of result files)")
    print("=" * 78)

    rows = []
    for e in error_entries:
        if e["category"] != "overwhelmed_indicator":
            continue
        for test_name in e["overwhelmed_indicators"] or ["(unparsed)"]:
            rows.append({
                "subdirectory": e["subdirectory"],
                "file": e["file"],
                "guid": e["guid"],
                "test_name": test_name,
            })

    if not rows:
        print("\nNo overwhelmed-indicator error files found.\n")
        return

    print(f"\n{len(rows)} overwhelmed-indicator instance(s) across "
          f"{len({r['file'] for r in rows})} error file(s):\n")

    test_counts = Counter(r["test_name"] for r in rows)
    print("Counts by test name:")
    for test_name, count in test_counts.most_common():
        print(f"  {test_name}: {count}")

    subdir_counts = Counter(r["subdirectory"] for r in rows)
    print("\nCounts by subdirectory:")
    for subdir, count in subdir_counts.most_common():
        print(f"  {subdir}: {count}")

    print("\nTest x subdirectory breakdown:")
    crosstab = Counter((r["test_name"], r["subdirectory"]) for r in rows)
    for (test_name, subdir), count in sorted(crosstab.items()):
        print(f"  {test_name} / {subdir}: {count}")
    print()

    return rows


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Search Benchmark Runner output for overwhelmed-indicator "
            "evidence in both result files and error files."
        )
    )
    parser.add_argument("root_dir", help="Directory to scan recursively")
    parser.add_argument(
        "--csv",
        help="Optional path to write Section 1 (result-file) matches as CSV",
        default=None,
    )
    parser.add_argument(
        "--error-csv",
        help="Optional path to write Section 2/3 (error-file) entries as CSV",
        default=None,
    )
    args = parser.parse_args()

    result_matches = scan_result_files(args.root_dir)
    error_entries = scan_error_files(args.root_dir)

    print_result_file_section(result_matches)
    print_error_file_section(error_entries)
    overwhelmed_rows = print_overwhelmed_indicator_section(error_entries) or []

    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(
                f, fieldnames=["subdirectory", "file", "test_name", "result"]
            )
            writer.writeheader()
            writer.writerows(result_matches)
        print(f"Wrote Section 1 CSV to {args.csv}")

    if args.error_csv:
        with open(args.error_csv, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(
                f, fieldnames=["subdirectory", "file", "guid", "test_name"]
            )
            writer.writeheader()
            writer.writerows(overwhelmed_rows)
        print(f"Wrote Section 3 CSV to {args.error_csv}")


if __name__ == "__main__":
    main()