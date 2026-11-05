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

"""A minimal LSP client over stdio, plus a process-tree resource sampler. Standard library only."""

import json
import os
import re
import subprocess
import threading
import time


class LspError(Exception):
    pass


class LspClient:
    def __init__(self, cmd, cwd, env, log_path, settings=None):
        self.settings = settings or {}
        self.log = open(log_path, "wb")
        self.proc = subprocess.Popen(
            cmd, cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log,
            start_new_session=True)
        self.next_id = 0
        self.pending = {}
        self.lock = threading.Lock()
        self.notifications = []
        self.closed = False
        threading.Thread(target=self._read, daemon=True).start()

    # -- wire ---------------------------------------------------------------------------------

    def _send(self, msg):
        body = json.dumps(msg).encode()
        data = b"Content-Length: %d\r\n\r\n" % len(body) + body
        try:
            self.proc.stdin.write(data)
            self.proc.stdin.flush()
        except (BrokenPipeError, ValueError) as e:
            raise LspError("server closed its input: %s" % e)

    def _read(self):
        out = self.proc.stdout
        while True:
            length = None
            while True:
                line = out.readline()
                if not line:
                    self._close()
                    return
                line = line.strip()
                if not line:
                    break
                if line.lower().startswith(b"content-length:"):
                    length = int(line.split(b":")[1])
            if length is None:
                continue
            body = out.read(length)
            try:
                msg = json.loads(body)
            except ValueError:
                continue
            self._dispatch(msg)

    def _close(self):
        self.closed = True
        with self.lock:
            for slot in self.pending.values():
                slot["error"] = {"message": "server exited"}
                slot["event"].set()

    def _dispatch(self, msg):
        if "id" in msg and "method" in msg:
            self._answer_server_request(msg)
        elif "id" in msg:
            with self.lock:
                slot = self.pending.pop(msg["id"], None)
            if slot:
                slot["result"] = msg.get("result")
                slot["error"] = msg.get("error")
                slot["event"].set()
        else:
            self.notifications.append((time.monotonic(), msg))

    def _answer_server_request(self, msg):
        method = msg["method"]
        result = None
        if method == "workspace/configuration":
            result = [self._setting(item.get("section")) for item in msg["params"].get("items", [])]
        elif method == "workspace/workspaceFolders":
            result = None
        self._send({"jsonrpc": "2.0", "id": msg["id"], "result": result})

    def _setting(self, section):
        node = self.settings
        for part in (section or "").split(".") if section else []:
            if not isinstance(node, dict) or part not in node:
                return None
            node = node[part]
        return node

    # -- API ----------------------------------------------------------------------------------

    def request(self, method, params, timeout=120):
        with self.lock:
            self.next_id += 1
            rid = self.next_id
            slot = {"event": threading.Event()}
            self.pending[rid] = slot
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        if not slot["event"].wait(timeout):
            with self.lock:
                self.pending.pop(rid, None)
            raise LspError("%s timed out after %ss" % (method, timeout))
        if slot.get("error"):
            raise LspError("%s failed: %s" % (method, slot["error"].get("message")))
        return slot.get("result")

    def notify(self, method, params):
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def shutdown(self, timeout=20):
        try:
            if not self.closed:
                self.request("shutdown", None, timeout=timeout)
                self.notify("exit", None)
            self.proc.wait(timeout=timeout)
        except Exception:
            pass
        finally:
            kill_tree(self.proc.pid)
            self.log.close()


# -- processes --------------------------------------------------------------------------------

def processes():
    """pid -> (ppid, rss_kb, cpu_seconds, command) for every process."""
    out = subprocess.run(["ps", "-axo", "pid=,ppid=,rss=,time=,command="],
                         capture_output=True, text=True).stdout
    table = {}
    for line in out.splitlines():
        parts = line.split(None, 4)
        if len(parts) < 5:
            continue
        pid, ppid, rss, cpu, cmd = parts
        table[int(pid)] = (int(ppid), int(rss), cpu_seconds(cpu), cmd)
    return table


def cpu_seconds(text):
    # ps prints [[dd-]hh:]mm:ss.cc
    days = 0
    if "-" in text:
        d, text = text.split("-", 1)
        days = int(d)
    secs = 0.0
    for part in text.split(":"):
        secs = secs * 60 + float(part)
    return days * 86400 + secs


def descendants(table, root):
    found = {root} if root in table else set()
    changed = True
    while changed:
        changed = False
        for pid, (ppid, *_rest) in table.items():
            if ppid in found and pid not in found:
                found.add(pid)
                changed = True
    return found


def kill_tree(root):
    table = processes()
    for pid in descendants(table, root):
        try:
            os.kill(pid, 9)
        except ProcessLookupError:
            pass


class Sampler:
    """Samples RSS and CPU of process trees plus extra processes (e.g. a detached daemon)."""

    def __init__(self, root_pids, extra_pattern=None, ignore_pids=(), interval=0.25):
        self.roots = list(root_pids) if isinstance(root_pids, (list, tuple, set)) else [root_pids]
        self.extra = re.compile(extra_pattern) if extra_pattern else None
        self.ignore = set(ignore_pids)
        self.interval = interval
        self.samples = []  # (t, rss_kb, cpu_s, pids)
        self.cpu_by_pid = {}
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self.thread.start()
        return self

    def pids(self, table):
        pids = set()
        for root in self.roots:
            pids |= descendants(table, root)
        if self.extra:
            for pid, (_p, _r, _c, cmd) in table.items():
                if pid not in self.ignore and self.extra.search(cmd):
                    pids |= descendants(table, pid)
        return pids

    def sample(self):
        table = processes()
        pids = self.pids(table)
        rss = sum(table[p][1] for p in pids)
        for p in pids:
            self.cpu_by_pid[p] = table[p][2]
        self.samples.append((time.monotonic(), rss, sum(self.cpu_by_pid.values()), sorted(pids)))
        return pids

    def _run(self):
        while not self.stop_event.is_set():
            self.sample()
            self.stop_event.wait(self.interval)

    def stop(self):
        self.stop_event.set()
        self.thread.join()

    def peak_rss_mb(self, since=None):
        values = [s[1] for s in self.samples if since is None or s[0] >= since]
        return max(values) / 1024 if values else None

    def last_rss_mb(self):
        return self.samples[-1][1] / 1024 if self.samples else None

    def cpu_s(self):
        return sum(self.cpu_by_pid.values())
