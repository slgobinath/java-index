#!/usr/bin/env python3
# Copyright 2026 Gobinath Loganathan
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Turns results directories from bench.py into report.md, written to the first directory.

    report.py RESULTS_DIR [MORE_RESULTS_DIRS...]   # later directories add servers not in earlier ones
"""

import glob
import json
import os
import statistics
import sys

import cases as C

REFERENCE = "jdtls"  # compiles the project, so its set-valued answers are the baseline


def load(dirs):
    runs = {}
    for out in dirs:
        found = {}
        for path in sorted(glob.glob(os.path.join(out, "*-*-*.json"))):
            r = json.load(open(path))
            found.setdefault(r["server"], {}).setdefault(r["mode"], []).append(r)
        for server, modes in found.items():
            runs.setdefault(server, modes)
    return runs


def med(values, fmt="%.0f"):
    values = [v for v in values if v is not None]
    return fmt % statistics.median(values) if values else "–"


def location_keys(answer):
    return {(l.get("file"), l.get("line")) for l in answer["locations"] if l.get("project")}


def main(dirs):
    out = dirs[0]
    runs = load(dirs)
    env = json.load(open(os.path.join(out, "environment.json")))
    env["results"] = ", ".join(os.path.basename(os.path.normpath(d)) for d in dirs)
    servers = [s for s in ("java-index", "java-index-native", "jdtls", "java-language-server") if s in runs] + \
              [s for s in runs if s not in ("java-index", "java-index-native", "jdtls", "java-language-server")]
    lines = ["# LSP benchmark: spring-petclinic", ""]
    lines += ["| | |", "| --- | --- |"]
    lines += ["| %s | %s |" % (k, v) for k, v in env.items()]
    lines += ["", "Medians across runs. Memory is the summed resident set of every process the server",
              "uses (for java-index: the relay and the daemon).", ""]

    lines += ["## Startup and resources", "",
              "| | " + " | ".join(servers) + " |", "| --- |" + " --- |" * len(servers)]
    rows = [
        ("Ready, cold (s)", "cold", "ready_ms", "%.1f", 1000),
        ("Ready, warm (s)", "warm", "ready_ms", "%.1f", 1000),
        ("Peak memory, cold (MB)", "cold", "peak_rss_mb", "%.0f", 1),
        ("Peak memory, warm (MB)", "warm", "peak_rss_mb", "%.0f", 1),
        ("Memory when idle after queries (MB)", "warm", "idle_rss_mb", "%.0f", 1),
        ("CPU time, cold run (s)", "cold", "cpu_s", "%.1f", 1),
        ("CPU time, warm run (s)", "warm", "cpu_s", "%.1f", 1),
        ("Processes", "warm", "processes", "%.0f", 1),
        ("Cache on disk (MB)", "warm", "disk_mb", "%.0f", 1),
    ]
    for label, mode, key, fmt, div in rows:
        cells = [med([r[key] / div if r.get(key) is not None else None
                      for r in runs[s].get(mode, []) if not r.get("error")], fmt)
                 for s in servers]
        lines.append("| %s | %s |" % (label, " | ".join(cells)))
    errors = [(s, r["mode"], r["error"]) for s in servers for m in runs[s].values() for r in m if r.get("error")]
    if errors:
        lines += ["", "Run errors:", ""] + ["* %s (%s): %s" % e for e in errors]

    lines += ["", "## Queries (warm runs)", "",
              "Median latency in ms; ✓ the answer is correct, ✗ wrong or empty, n/a not supported.",
              "", "| Query | " + " | ".join(servers) + " |", "| --- |" + " --- |" * len(servers)]
    for case in C.CASES:
        cells = []
        for s in servers:
            entries = [r["cases"].get(case["name"], {}) for r in runs[s].get("warm", [])]
            entries = [e for e in entries if e]
            if not entries:
                cells.append("–")
            elif not entries[0].get("supported"):
                cells.append("n/a")
            else:
                mark = "✓" if all(e.get("correct") for e in entries) else "✗"
                cells.append("%s %s" % (mark, med([e["median_ms"] for e in entries], "%.1f")))
        lines.append("| %s | %s |" % (case["name"], " | ".join(cells)))

    # Set-valued answers against the reference.
    ref = [r for r in runs.get(REFERENCE, {}).get("warm", []) if r.get("cases")]
    if ref:
        lines += ["", "## Agreement with %s" % REFERENCE, "",
                  "For location answers in project code: locations also found by %s / locations it found." % REFERENCE,
                  "", "| Query | " + " | ".join(s for s in servers if s != REFERENCE) + " |",
                  "| --- |" + " --- |" * (len(servers) - 1)]
        for case in C.CASES:
            if case["method"] not in ("textDocument/references", "textDocument/implementation",
                                      "textDocument/documentHighlight"):
                continue
            ref_case = ref[0]["cases"].get(case["name"], {})
            if not ref_case.get("supported"):
                continue
            want = location_keys(ref_case["answer"])
            cells = []
            for s in servers:
                if s == REFERENCE:
                    continue
                e = (runs[s].get("warm") or [{}])[0].get("cases", {}).get(case["name"], {})
                if not e.get("supported"):
                    cells.append("n/a")
                    continue
                got = location_keys(e["answer"])
                cells.append("%d / %d (+%d extra)" % (len(got & want), len(want), len(got - want)))
            lines.append("| %s | %s |" % (case["name"], " | ".join(cells)))

    with open(os.path.join(out, "report.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main(sys.argv[1:])
