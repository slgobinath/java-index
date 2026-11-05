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

"""Runs the same LSP queries against several Java language servers and records latency,
correctness and resource usage. Run ./setup.sh first. Standard library only.

    python3 bench.py                       # all servers, 3 cold + 3 warm runs each
    python3 bench.py --servers java-index --runs 1
"""

import argparse
import datetime
import glob
import json
import os
import platform
import shutil
import statistics
import subprocess
import sys
import time
import urllib.parse

import cases as C
from lsp import LspClient, LspError, Sampler, kill_tree, processes

BENCH = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(BENCH, ".work")
PETCLINIC = os.path.join(WORK, "repos", "spring-petclinic")
REPO = PETCLINIC  # --spaces switches to a copy indented with spaces
SERVERS_DIR = os.path.join(WORK, "servers")
JAVA_INDEX_JAR = os.path.join(BENCH, "..", "target", "java-index.jar")
JAVA_INDEX_RELAY = os.path.join(BENCH, "..", "target", "jindex-relay")


def java(var):
    home = os.environ.get(var)
    if not home or not os.access(os.path.join(home, "bin", "java"), os.X_OK):
        raise SystemExit("set %s to a JDK home (java-index needs 21+, jdtls and "
                         "java-language-server need 25+)" % var)
    return os.path.join(home, "bin", "java")


# vscode-java's defaults where jdtls's own differ (signature help is off in jdtls itself).
JDTLS_SETTINGS = {"java": {"import": {"gradle": {"enabled": False}}, "signatureHelp": {"enabled": True}}}

# jdtls and java-language-server require Java 25; java-index runs on Java 21.
# Each server runs with the defaults its usual launcher applies, so the numbers reflect what a
# user gets: java-index's own daemon flags, jdtls with vscode-java's default VM arguments, and
# java-language-server with its launcher's flags.
def server_specs():
    jdtls = os.path.join(SERVERS_DIR, "jdtls")
    jls = os.path.join(SERVERS_DIR, "jls")
    jls_exports = [a for m in ("api", "code", "comp", "main", "tree", "model", "util")
                   for a in ("--add-exports", "jdk.compiler/com.sun.tools.javac.%s=ALL-UNNAMED" % m)]
    return {
        "java-index": {
            "cmd": lambda state: [java("JAVA21_HOME"), "-jar", os.path.abspath(JAVA_INDEX_JAR)],
            "env": lambda state: {"JINDEX_HOME": state},
            # The daemon is detached from the relay's process tree; sample and stop it too.
            "extra_processes": r"dev\.javaindex\.Main daemon",
            "init_options": {},
            "settings": {},
        },
        # The same server with the GraalVM native relay (mvn -Pnative package) in place of the JVM one.
        "java-index-native": {
            "cmd": lambda state: [os.path.abspath(JAVA_INDEX_RELAY)],
            "env": lambda state: {"JINDEX_HOME": state, "JINDEX_JAVA": java("JAVA21_HOME")},
            "extra_processes": r"dev\.javaindex\.Main daemon",
            "init_options": {},
            "settings": {},
        },
        "jdtls": {
            "cmd": lambda state: [
                java("JAVA25_HOME"),
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Dosgi.checkConfiguration=true",
                "-Dosgi.sharedConfiguration.area=" + os.path.join(jdtls, "config_mac_arm" if platform.machine() == "arm64" else "config_mac"),
                "-Dosgi.sharedConfiguration.area.readOnly=true",
                "-Dosgi.configuration.cascaded=true",
                "-Dsun.zip.disableMemoryMapping=true",
                "-XX:+UseParallelGC", "-XX:GCTimeRatio=4", "-XX:AdaptiveSizePolicyWeight=90",
                "-Xmx2G", "-Xms100m",
                "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", glob.glob(os.path.join(jdtls, "plugins", "org.eclipse.equinox.launcher_*.jar"))[0],
                "-configuration", os.path.join(state, "config"),
                "-data", os.path.join(state, "workspace"),
            ],
            "env": lambda state: {},
            "extra_processes": None,
            "init_options": {
                "settings": JDTLS_SETTINGS,
                "extendedClientCapabilities": {"classFileContentsSupport": True},
            },
            "settings": JDTLS_SETTINGS,
        },
        "java-language-server": {
            "cmd": lambda state: [java("JAVA25_HOME")] + jls_exports + [
                "--add-opens", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "-classpath", os.pathsep.join(sorted(glob.glob(os.path.join(jls, "dist", "classpath", "*.jar")))),
                "org.javacs.Main",
            ],
            "env": lambda state: {},
            "extra_processes": None,
            "init_options": {},
            "settings": {"java": {}},
        },
    }


CAPABILITY = {
    "textDocument/definition": "definitionProvider",
    "textDocument/typeDefinition": "typeDefinitionProvider",
    "textDocument/hover": "hoverProvider",
    "textDocument/references": "referencesProvider",
    "textDocument/documentHighlight": "documentHighlightProvider",
    "textDocument/implementation": "implementationProvider",
    "typeHierarchy/supertypes": "typeHierarchyProvider",
    "typeHierarchy/subtypes": "typeHierarchyProvider",
    "callHierarchy/incomingCalls": "callHierarchyProvider",
    "callHierarchy/outgoingCalls": "callHierarchyProvider",
    "textDocument/signatureHelp": "signatureHelpProvider",
    "textDocument/documentSymbol": "documentSymbolProvider",
    "workspace/symbol": "workspaceSymbolProvider",
}

CLIENT_CAPABILITIES = {
    "workspace": {"configuration": True, "workspaceFolders": True, "symbol": {}},
    "window": {"workDoneProgress": True},
    "textDocument": {
        "synchronization": {"didSave": True},
        "definition": {"linkSupport": True},
        "typeDefinition": {"linkSupport": True},
        "implementation": {"linkSupport": True},
        "hover": {"contentFormat": ["markdown", "plaintext"]},
        "references": {},
        "documentHighlight": {},
        "documentSymbol": {"hierarchicalDocumentSymbolSupport": True},
        "signatureHelp": {"signatureInformation": {"parameterInformation": {"labelOffsetSupport": True}}},
        "typeHierarchy": {},
        "callHierarchy": {},
        "publishDiagnostics": {},
    },
}


# -- positions and answers --------------------------------------------------------------------

def uri(path, repo=None):
    return "file://" + urllib.parse.quote(os.path.join(repo or REPO, path))


def position(at, repo=None):
    path, line_text, token = at[:3]
    occurrence = at[3] if len(at) > 3 else 0
    with open(os.path.join(repo or REPO, path)) as f:
        lines = f.read().split("\n")
    matches = [i for i, l in enumerate(lines) if line_text in l]
    if len(matches) < 1:
        raise SystemExit("case position not found: %r in %s" % (line_text, path))
    line = matches[0]
    col = lines[line].index(token, lines[line].index(line_text))
    for _ in range(occurrence):
        col = lines[line].index(token, col + 1)
    return {"line": line, "character": col + 1}  # inside the token


def target_label(loc, repo=None):
    """'File.java:<line text>' for project files, the URI's class name for libraries."""
    u = loc.get("targetUri") or loc.get("uri")
    r = loc.get("targetSelectionRange") or loc.get("range")
    decoded = urllib.parse.unquote(u)
    if decoded.startswith("file://" + (repo or REPO) + "/src/"):
        path = decoded[len("file://"):]
        with open(path) as f:
            text = f.read().split("\n")[r["start"]["line"]].strip()
        return {"project": True, "file": os.path.basename(path), "line": r["start"]["line"] + 1,
                "text": text}
    return {"project": False, "uri": decoded}


def flatten_symbols(symbols):
    for s in symbols or []:
        yield s.get("name", "")
        yield from flatten_symbols(s.get("children"))


def hover_text(result):
    if not result:
        return ""
    c = result.get("contents")
    parts = c if isinstance(c, list) else [c]
    return "\n".join(p if isinstance(p, str) else p.get("value", "") for p in parts if p)


def summarize(method, result, repo=None):
    """Reduce an answer to {'names': [...], 'locations': [...], 'text': ...} for checking and diffing."""
    out = {"names": [], "locations": [], "text": ""}
    if result is None:
        return out
    if method in ("textDocument/definition", "textDocument/typeDefinition", "textDocument/references",
                  "textDocument/implementation", "textDocument/documentHighlight"):
        items = result if isinstance(result, list) else [result]
        for loc in items:
            if method == "textDocument/documentHighlight":
                out["locations"].append({"project": True, "line": loc["range"]["start"]["line"] + 1})
                continue
            label = target_label(loc, repo)
            out["locations"].append(label)
            out["names"].append(label["file"][:-5] if label["project"] else label["uri"])
    elif method.startswith("typeHierarchy/"):
        out["names"] = [i.get("name", "") for i in result]
    elif method == "callHierarchy/incomingCalls":
        out["names"] = [c["from"].get("name", "") for c in result]
    elif method == "callHierarchy/outgoingCalls":
        out["names"] = [c["to"].get("name", "") for c in result]
    elif method == "textDocument/documentSymbol":
        out["names"] = list(flatten_symbols(result))
    elif method == "workspace/symbol":
        out["names"] = [s.get("name", "") for s in result]
    elif method == "textDocument/hover":
        out["text"] = hover_text(result)
    elif method == "textDocument/signatureHelp":
        out["names"] = [s.get("label", "") for s in result.get("signatures", [])]
    return out


def check(expect, s):
    """True when the summarized answer meets the case's hand-written expectation."""
    names = s["names"]
    if "locations" in expect:
        return any(l.get("project") and l["file"] == e.split(":")[0] and e.split(":")[1] in l["text"]
                   for e in expect["locations"] for l in s["locations"])
    if "library" in expect:
        return any(not l.get("project") and expect["library"] in l["uri"] for l in s["locations"])
    if "hover_contains" in expect:
        return all(w in s["text"] for w in expect["hover_contains"])
    if "min_count" in expect:
        return len(s["locations"]) >= expect["min_count"]
    if "contains" in expect:
        return all(any(w == n or n.startswith(w + "(") or n.startswith(w + " ") or w == n.split(".")[-1]
                       for n in names) for w in expect["contains"])
    if "signature_contains" in expect:
        return any(expect["signature_contains"] in n for n in names)
    return bool(names or s["locations"] or s["text"])


# -- one query --------------------------------------------------------------------------------

def run_case(client, case, repo=None):
    method = case["method"]
    if method == "workspace/symbol":
        return client.request(method, {"query": case["query"]})
    if method == "textDocument/documentSymbol":
        return client.request(method, {"textDocument": {"uri": uri(case["file"], repo)}})
    doc = {"textDocument": {"uri": uri(case["at"][0], repo)}, "position": position(case["at"], repo)}
    if method.startswith("typeHierarchy/") or method.startswith("callHierarchy/"):
        prepare = "textDocument/prepareTypeHierarchy" if method.startswith("type") else "textDocument/prepareCallHierarchy"
        items = client.request(prepare, doc)
        if not items:
            return []
        return client.request(method, {"item": items[0]})
    if method == "textDocument/references":
        doc["context"] = {"includeDeclaration": False}
    return client.request(method, doc)


def timed(client, case, repo=None):
    t = time.monotonic()
    try:
        result, error = run_case(client, case, repo), None
    except LspError as e:
        result, error = None, str(e)
    return (time.monotonic() - t) * 1000, result, error


# -- one server run ---------------------------------------------------------------------------

def du_mb(path):
    if not os.path.exists(path):
        return 0.0
    out = subprocess.run(["du", "-sk", path], capture_output=True, text=True).stdout
    return int(out.split()[0]) / 1024 if out else 0.0


def run_server(name, spec, mode, state, reps, ready_timeout, log_path):
    if mode == "cold" and os.path.exists(state):
        shutil.rmtree(state)
    os.makedirs(state, exist_ok=True)
    env = dict(os.environ)
    env.update(spec["env"](state))
    preexisting = set(processes())  # never sample or stop daemons that were already running

    t0 = time.monotonic()
    client = LspClient(spec["cmd"](state), REPO, env, log_path, spec["settings"])
    sampler = Sampler(client.proc.pid, spec["extra_processes"], ignore_pids=preexisting).start()
    result = {"server": name, "mode": mode, "cases": {}}
    try:
        init = client.request("initialize", {
            "processId": os.getpid(),
            "rootUri": "file://" + REPO,
            "rootPath": REPO,
            "workspaceFolders": [{"uri": "file://" + REPO, "name": "spring-petclinic"}],
            "capabilities": CLIENT_CAPABILITIES,
            "initializationOptions": spec["init_options"],
        }, timeout=ready_timeout)
        caps = init.get("capabilities", {})
        result["server_info"] = init.get("serverInfo")
        client.notify("initialized", {})
        result["initialize_ms"] = (time.monotonic() - t0) * 1000

        opened = sorted({c["at"][0] for c in C.CASES if "at" in c} | {c["file"] for c in C.CASES if "file" in c})
        for path in opened:
            with open(os.path.join(REPO, path)) as f:
                client.notify("textDocument/didOpen", {"textDocument": {
                    "uri": uri(path), "languageId": "java", "version": 1, "text": f.read()}})

        # Ready = the probe definition returns the right answer.
        probe = next(c for c in C.CASES if c["name"] == C.PROBE)
        while True:
            _ms, answer, err = timed(client, probe)
            if answer is not None and check(probe["expect"], summarize(probe["method"], answer)):
                break
            if client.closed or time.monotonic() - t0 > ready_timeout:
                result["last_probe"] = {"answer": answer, "error": err}
                raise LspError("not ready after %ss" % ready_timeout)
            time.sleep(0.25)
        ready_at = time.monotonic()
        result["ready_ms"] = (ready_at - t0) * 1000
        result["startup_peak_rss_mb"] = sampler.peak_rss_mb()

        for case in C.CASES:
            entry = {}
            if not caps.get(CAPABILITY[case["method"]]):
                entry["supported"] = False
                result["cases"][case["name"]] = entry
                continue
            first_ms, answer, error = timed(client, case)
            summary = summarize(case["method"], answer)
            times = [timed(client, case)[0] for _ in range(reps)]
            entry.update({
                "supported": True,
                "first_ms": first_ms,
                "median_ms": statistics.median(times),
                "p95_ms": sorted(times)[max(0, int(round(0.95 * len(times))) - 1)],
                "error": error,
                "correct": error is None and check(case["expect"], summary),
                "answer": summary,
            })
            result["cases"][case["name"]] = entry

        result["query_peak_rss_mb"] = sampler.peak_rss_mb(since=ready_at)
        time.sleep(5)
        sampler.sample()
        result["idle_rss_mb"] = sampler.last_rss_mb()
        result["cpu_s"] = sampler.cpu_s()
        result["processes"] = len(sampler.samples[-1][3])
    except LspError as e:
        result["error"] = str(e)
    finally:
        sampler.stop()
        tracked = set(sampler.pids(processes()))
        client.shutdown()
        for pid in tracked - preexisting:
            kill_tree(pid)
    result["peak_rss_mb"] = sampler.peak_rss_mb()
    result["disk_mb"] = du_mb(state)
    return result


def environment():
    def version(cmd):
        try:
            return subprocess.run(cmd, capture_output=True, text=True).stderr.split("\n")[0]
        except OSError:
            return None

    def git(path):
        return subprocess.run(["git", "-C", path, "describe", "--always", "--dirty"],
                              capture_output=True, text=True).stdout.strip()

    cpu = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True).stdout.strip()
    mem = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout or 0)
    return {
        "date": datetime.datetime.now().isoformat(timespec="seconds"),
        "machine": "%s, %d GB, %s %s" % (cpu, mem // 2**30, platform.system(), platform.release()),
        "java21": version([java("JAVA21_HOME"), "-version"]),
        "java25": version([java("JAVA25_HOME"), "-version"]),
        "spring-petclinic": git(PETCLINIC),
        "java-index": git(os.path.join(BENCH, "..")),
        "jdtls": os.path.basename(glob.glob(os.path.join(SERVERS_DIR, "jdt-language-server-*.tar.gz"))[0])
                 if glob.glob(os.path.join(SERVERS_DIR, "jdt-language-server-*.tar.gz")) else None,
        "java-language-server": git(os.path.join(SERVERS_DIR, "jls")),
    }


def spaces_copy():
    """A copy of petclinic with tabs in Java sources expanded to 4-column stops.

    java-language-server reads LSP character offsets as javac columns, which expand tabs to 8, so
    in tab-indented files it resolves the wrong token. Indentation is the only difference."""
    dest = PETCLINIC + "-spaces"
    if os.path.exists(dest):
        shutil.rmtree(dest)
    shutil.copytree(PETCLINIC, dest, symlinks=True, ignore=shutil.ignore_patterns("target"))
    for java_file in glob.glob(os.path.join(dest, "src", "**", "*.java"), recursive=True):
        with open(java_file) as f:
            text = f.read()
        with open(java_file, "w") as f:
            f.write("\n".join(line.expandtabs(4) for line in text.split("\n")))
    return dest


def main():
    global REPO
    specs = server_specs()
    parser = argparse.ArgumentParser()
    parser.add_argument("--servers", default=",".join(specs))
    parser.add_argument("--runs", type=int, default=3, help="cold runs and warm runs per server")
    parser.add_argument("--reps", type=int, default=10, help="timed repetitions of each query")
    parser.add_argument("--ready-timeout", type=int, default=600)
    parser.add_argument("--spaces", action="store_true",
                        help="run on a copy of petclinic indented with spaces instead of tabs")
    args = parser.parse_args()
    variant = "spaces" if args.spaces else "tabs"
    if args.spaces:
        REPO = spaces_copy()

    java("JAVA21_HOME"), java("JAVA25_HOME")
    out = os.path.join(BENCH, "results", datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + variant)
    os.makedirs(out)
    with open(os.path.join(out, "environment.json"), "w") as f:
        json.dump(dict(environment(), indentation=variant), f, indent=2)
    for name in args.servers.split(","):
        state = os.path.join(WORK, "state", variant, name)
        # One cold run first, then warm runs on its caches, repeated: cold, warm, cold, warm ...
        for i in range(args.runs):
            for mode in ("cold", "warm"):
                label = "%s-%s-%d" % (name, mode, i + 1)
                print("%-32s" % label, end="", flush=True)
                r = run_server(name, specs[name], mode, state, args.reps, args.ready_timeout,
                               os.path.join(out, label + ".log"))
                with open(os.path.join(out, label + ".json"), "w") as f:
                    json.dump(r, f, indent=2)
                ok = sum(1 for c in r["cases"].values() if c.get("correct"))
                supported = sum(1 for c in r["cases"].values() if c.get("supported"))
                print("ready %6.1fs  peak %5.0f MB  correct %d/%d supported (%d unsupported)  %s" % (
                    (r.get("ready_ms") or float("nan")) / 1000, r.get("peak_rss_mb") or 0, ok, supported,
                    len(C.CASES) - supported if r["cases"] else 0, r.get("error") or ""))
    print("results in", out)
    subprocess.run([sys.executable, os.path.join(BENCH, "report.py"), out])


if __name__ == "__main__":
    main()
