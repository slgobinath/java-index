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

"""Parallel worktrees: N git worktrees of spring-petclinic, each with its own small change (as if
an agent had worked in it), opened at the same time in N editor windows. Every window gets its own
language server process, as editors do; java-index's relays all share one daemon.

    python3 worktrees.py                          # N = 1, 2, 4, 8; java-index and jdtls; 2 runs each
    python3 worktrees.py --counts 4 --runs 1
"""

import argparse
import datetime
import json
import os
import shutil
import statistics
import subprocess
import sys
import threading
import time

import bench as B
import cases as C
from lsp import LspClient, LspError, Sampler, kill_tree, processes

BASE = os.path.join(B.WORK, "repos", "petclinic-worktrees")
MAIN = os.path.join(BASE, "main")
CONTROLLER = C.OWNER_CONTROLLER


def git(*args, cwd=None):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


def worktree_change(i):
    """The change 'agent' i made: two methods appended to OwnerController."""
    return ("\n\tString reviewMarker%d() {\n\t\treturn initFindForm();\n\t}\n"
            "\n\tString callsReviewMarker() {\n\t\treturn reviewMarker%d();\n\t}\n" % (i, i))


def prepare_worktrees(n):
    """wt-1 .. wt-n, each a git worktree of one local clone, reset and given its own change."""
    if not os.path.exists(MAIN):
        os.makedirs(BASE, exist_ok=True)
        git("clone", "-q", "--local", B.PETCLINIC, MAIN)
    paths = []
    for i in range(1, n + 1):
        path = os.path.join(BASE, "wt-%d" % i)
        if not os.path.exists(path):
            git("worktree", "add", "-q", "--detach", path, "HEAD", cwd=MAIN)
        git("checkout", "-q", "--", ".", cwd=path)
        controller = os.path.join(path, CONTROLLER)
        with open(controller) as f:
            text = f.read()
        end = text.rstrip().rindex("}")
        with open(controller, "w") as f:
            f.write(text[:end].rstrip("\n") + "\n" + worktree_change(i) + "\n}\n")
        paths.append(path)
    return paths


def worktree_cases(i):
    return [
        {
            "name": "definition: this worktree's change",
            "method": "textDocument/definition",
            "at": (CONTROLLER, "return reviewMarker%d();" % i, "reviewMarker%d" % i),
            "expect": {"locations": ["OwnerController.java:String reviewMarker%d()" % i]},
        },
        {
            "name": "workspace/symbol: only this worktree's change",
            "method": "workspace/symbol",
            "query": "reviewMarker",
            "expect": {"only": "reviewMarker%d" % i},
        },
    ]


def correct(case, summary):
    if "only" in case["expect"]:
        found = {n.split("(")[0].strip() for n in summary["names"] if n.startswith("reviewMarker")}
        return found == {case["expect"]["only"]}
    return B.check(case["expect"], summary)


class Window:
    """One editor window: a language server opened on one worktree."""

    def __init__(self, index, repo, spec, state, env, log_path):
        self.index, self.repo = index, repo
        self.client = LspClient(spec["cmd"](state), repo, env, log_path, spec["settings"])
        self.spec = spec
        self.ready_s = None
        self.error = None
        self.caps = {}

    def open(self, t0, ready_timeout):
        try:
            init = self.client.request("initialize", {
                "processId": os.getpid(),
                "rootUri": "file://" + self.repo,
                "rootPath": self.repo,
                "workspaceFolders": [{"uri": "file://" + self.repo, "name": os.path.basename(self.repo)}],
                "capabilities": B.CLIENT_CAPABILITIES,
                "initializationOptions": self.spec["init_options"],
            }, timeout=ready_timeout)
            self.caps = init.get("capabilities", {})
            self.client.notify("initialized", {})
            paths = sorted({c["at"][0] for c in C.CASES if "at" in c} | {c["file"] for c in C.CASES if "file" in c})
            for path in paths:
                with open(os.path.join(self.repo, path)) as f:
                    self.client.notify("textDocument/didOpen", {"textDocument": {
                        "uri": B.uri(path, self.repo), "languageId": "java", "version": 1, "text": f.read()}})
            probe = next(c for c in C.CASES if c["name"] == C.PROBE)
            while True:
                _ms, answer, _err = B.timed(self.client, probe, self.repo)
                if answer is not None and B.check(probe["expect"], B.summarize(probe["method"], answer, self.repo)):
                    break
                if self.client.closed or time.monotonic() - t0 > ready_timeout:
                    raise LspError("not ready after %ss" % ready_timeout)
                time.sleep(0.25)
            self.ready_s = time.monotonic() - t0
        except LspError as e:
            self.error = str(e)

    def query(self, reps):
        results = {}
        for case in C.CASES + worktree_cases(self.index):
            if not self.caps.get(B.CAPABILITY[case["method"]]):
                results[case["name"]] = {"supported": False}
                continue
            _first, answer, error = B.timed(self.client, case, self.repo)
            summary = B.summarize(case["method"], answer, self.repo)
            times = [B.timed(self.client, case, self.repo)[0] for _ in range(reps)]
            results[case["name"]] = {
                "supported": True,
                "median_ms": statistics.median(times),
                "correct": error is None and correct(case, summary),
                "answer": summary,
            }
        return results


def run(name, spec, repos, reps, ready_timeout, out_dir, label):
    state_root = os.path.join(B.WORK, "state", "worktrees", name)
    if os.path.exists(state_root):
        shutil.rmtree(state_root)
    preexisting = set(processes())
    t0 = time.monotonic()
    windows = []
    for i, repo in enumerate(repos, start=1):
        # java-index: one state directory, so all relays find the same daemon. jdtls: one
        # workspace per window, as editors keep per-window workspace storage.
        state = state_root if name.startswith("java-index") else os.path.join(state_root, "wt-%d" % i)
        os.makedirs(state, exist_ok=True)
        env = dict(os.environ)
        env.update(spec["env"](state))
        windows.append(Window(i, repo, spec, state, env, os.path.join(out_dir, "%s-wt%d.log" % (label, i))))
    sampler = Sampler([w.client.proc.pid for w in windows], spec["extra_processes"], ignore_pids=preexisting).start()

    threads = [threading.Thread(target=w.open, args=(t0, ready_timeout)) for w in windows]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    all_ready = time.monotonic()
    result = {
        "server": name, "worktrees": len(repos),
        "ready_s": [w.ready_s for w in windows],
        "errors": [w.error for w in windows if w.error],
        "startup_peak_rss_mb": sampler.peak_rss_mb(),
        "all_ready_s": all_ready - t0,
    }
    result["windows"] = [w.query(reps) if not w.error else {} for w in windows]
    result["query_peak_rss_mb"] = sampler.peak_rss_mb(since=all_ready)
    time.sleep(5)
    sampler.sample()
    result["idle_rss_mb"] = sampler.last_rss_mb()
    result["cpu_s"] = sampler.cpu_s()
    result["processes"] = len(sampler.samples[-1][3])
    sampler.stop()
    tracked = set(sampler.pids(processes()))
    for w in windows:
        w.client.shutdown()
    for pid in tracked - preexisting:
        kill_tree(pid)
    result["peak_rss_mb"] = sampler.peak_rss_mb()
    result["disk_mb"] = B.du_mb(state_root)
    return result


def report(out):
    runs = {}
    for path in sorted(os.listdir(out)):
        if path.endswith(".json") and path != "environment.json":
            r = json.load(open(os.path.join(out, path)))
            runs.setdefault((r["server"], r["worktrees"]), []).append(r)
    servers = [s for s in ("java-index", "java-index-native", "jdtls") if any(k[0] == s for k in runs)]
    counts = sorted({k[1] for k in runs})
    env = json.load(open(os.path.join(out, "environment.json")))

    def med(values, fmt):
        values = [v for v in values if v is not None]
        return fmt % statistics.median(values) if values else "–"

    lines = ["# Parallel worktrees: spring-petclinic", "", "| | |", "| --- | --- |"]
    lines += ["| %s | %s |" % kv for kv in env.items()]
    lines += ["", "N worktrees opened at the same time, one editor window (language server client) each.",
              "Medians across runs. Memory and CPU are summed over every process involved.", ""]
    metrics = [
        ("All windows ready (s)", lambda r: r["all_ready_s"], "%.1f"),
        ("Peak memory (MB)", lambda r: r["peak_rss_mb"], "%.0f"),
        ("Memory when idle (MB)", lambda r: r["idle_rss_mb"], "%.0f"),
        ("CPU time (s)", lambda r: r["cpu_s"], "%.1f"),
        ("Processes", lambda r: r["processes"], "%.0f"),
        ("Disk (MB)", lambda r: r["disk_mb"], "%.0f"),
        ("Query median (ms)", lambda r: statistics.median(
            c["median_ms"] for w in r["windows"] for c in w.values() if c.get("supported")) if any(r["windows"]) else None, "%.1f"),
        ("Correct answers per run", None, None),
    ]
    for title, fn, fmt in metrics:
        lines += ["## " + title, "", "| Worktrees | " + " | ".join(servers) + " |", "| --- |" + " --- |" * len(servers)]
        for n in counts:
            cells = []
            for s in servers:
                rs = runs.get((s, n), [])
                if not rs:
                    cells.append("–")
                elif fn is None:
                    ok = sum(1 for r in rs for w in r["windows"] for c in w.values() if c.get("correct"))
                    total = sum(1 for r in rs for w in r["windows"] for c in w.values() if c.get("supported"))
                    errs = sum(len(r["errors"]) for r in rs)
                    cells.append("%d/%d%s" % (ok // len(rs), total // len(rs),
                                              " (%d windows failed)" % errs if errs else ""))
                else:
                    cells.append(med([fn(r) for r in rs], fmt))
            lines.append("| %d | %s |" % (n, " | ".join(cells)))
        lines.append("")
    text = "\n".join(lines)
    with open(os.path.join(out, "report.md"), "w") as f:
        f.write(text + "\n")
    print(text)


def main():
    specs = B.server_specs()
    parser = argparse.ArgumentParser()
    parser.add_argument("--servers", default="java-index,jdtls")
    parser.add_argument("--counts", default="1,2,4,8", help="numbers of worktrees to open at once")
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--reps", type=int, default=5)
    parser.add_argument("--ready-timeout", type=int, default=600)
    args = parser.parse_args()
    B.java("JAVA21_HOME"), B.java("JAVA25_HOME")
    counts = [int(c) for c in args.counts.split(",")]
    all_repos = prepare_worktrees(max(counts))

    out = os.path.join(B.BENCH, "results", datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + "-worktrees")
    os.makedirs(out)
    env = B.environment()
    with open(os.path.join(out, "environment.json"), "w") as f:
        json.dump(env, f, indent=2)
    for n in counts:
        for name in args.servers.split(","):
            for run_i in range(1, args.runs + 1):
                label = "%s-n%d-%d" % (name, n, run_i)
                print("%-24s" % label, end="", flush=True)
                r = run(name, specs[name], all_repos[:n], args.reps, args.ready_timeout, out, label)
                with open(os.path.join(out, label + ".json"), "w") as f:
                    json.dump(r, f, indent=2)
                ok = sum(1 for w in r["windows"] for c in w.values() if c.get("correct"))
                total = sum(1 for w in r["windows"] for c in w.values() if c.get("supported"))
                print("all ready %6.1fs  peak %6.0f MB  idle %6.0f MB  cpu %6.1fs  correct %d/%d  %s" % (
                    r["all_ready_s"], r["peak_rss_mb"] or 0, r["idle_rss_mb"] or 0, r["cpu_s"], ok, total,
                    "; ".join(r["errors"])))
    print("results in", out)
    report(out)


if __name__ == "__main__":
    main()
