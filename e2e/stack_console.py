#!/usr/bin/env python3
"""Local end-to-end console for the PS0 stack (payments, fx-gateway, ledger).

Starts stub upstreams (rate provider, core banking), optionally launches the three
service jars with their URLs pointed at each other, and serves a browser UI that
drives settlements, balance lookups and failure scenarios while showing the exact
requests, responses and service log lines. Python 3 stdlib only.
"""
import argparse
import collections
import json
import os
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

STATIC_DIR = Path(__file__).resolve().parent / "static"

QUOTES = {
    "EURUSD": ("1.0848", "1.0852"),
    "USDEUR": ("0.9211", "0.9215"),
    "GBPUSD": ("1.2710", "1.2716"),
    "USDCAD": ("1.3645", "1.3651"),
    "EURCAD": ("1.4801", "1.4809"),
    "EURGBP": ("0.8531", "0.8536"),
}

BALANCES = {
    "ACC-100234": {"currency": "USD", "availableBalance": 1250.75, "ledgerBalance": 1300.00},
    "ACC-200871": {"currency": "USD", "availableBalance": 98000.00, "ledgerBalance": 98000.00},
    "ACC-300512": {"currency": "CAD", "availableBalance": 15420.10, "ledgerBalance": 15500.00},
}

STUB_TIMESTAMP = "2026-10-16T08:00:00Z"


class StubHandler(BaseHTTPRequestHandler):
    routes = []
    stub_name = "stub"
    hits = None

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        for pattern, fn in self.routes:
            m = re.fullmatch(pattern, self.path)
            if m:
                status, body = fn(*m.groups())
                break
        else:
            status, body = 404, {"message": "not found"}
        self.hits.append({"time": time.strftime("%H:%M:%S"), "path": self.path, "status": status})
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def quote(pair):
    if pair not in QUOTES:
        return 404, {"message": "no quote for " + pair}
    bid, ask = QUOTES[pair]
    return 200, {"pair": pair, "bid": float(bid), "ask": float(ask), "timestamp": STUB_TIMESTAMP}


def balance(account_id):
    if account_id not in BALANCES:
        return 404, {"message": "unknown account " + account_id}
    return 200, {"accountId": account_id, **BALANCES[account_id], "asOf": STUB_TIMESTAMP}


class Stub:
    """An upstream stub that can be stopped so callers see connection refused."""

    def __init__(self, name, port, routes):
        self.name, self.port = name, port
        self.hits = collections.deque(maxlen=50)
        self.handler = type(name + "Handler", (StubHandler,),
                            {"routes": routes, "stub_name": name, "hits": self.hits})
        self.server = None
        self.lock = threading.Lock()

    def start(self):
        with self.lock:
            if self.server:
                return
            ThreadingHTTPServer.allow_reuse_address = True
            self.server = ThreadingHTTPServer(("127.0.0.1", self.port), self.handler)
            threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stop(self):
        with self.lock:
            if not self.server:
                return
            self.server.shutdown()
            self.server.server_close()
            self.server = None

    def status(self):
        return {"name": self.name, "port": self.port, "running": self.server is not None,
                "recentHits": list(self.hits)[-5:]}


class Service:
    def __init__(self, name, port, jar, args, log_dir):
        self.name, self.port, self.jar, self.args = name, port, jar, args
        self.log_path = Path(log_dir) / (name + ".log") if jar else None
        self.lines = collections.deque(maxlen=5000)
        self.seq = 0
        self.lock = threading.Lock()
        self.proc = None

    def start(self):
        if not self.jar:
            return
        cmd = ["java", "-jar", str(self.jar), "--server.port=%d" % self.port] + self.args
        log = open(self.log_path, "w", buffering=1)
        log.write("$ " + " ".join(cmd) + "\n")
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, bufsize=1)

        def pump():
            for line in self.proc.stdout:
                log.write(line)
                with self.lock:
                    self.seq += 1
                    self.lines.append((self.seq, line.rstrip("\n")))
        threading.Thread(target=pump, daemon=True).start()

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def mark(self):
        with self.lock:
            return self.seq

    def since(self, mark):
        with self.lock:
            return [line for seq, line in self.lines if seq > mark]

    def tail(self, n):
        with self.lock:
            return [line for _, line in list(self.lines)[-n:]]

    def status(self):
        up = port_open(self.port)
        state = "up" if up else "down"
        if self.proc is not None and self.proc.poll() is not None:
            state = "exited (%d)" % self.proc.returncode
        elif self.proc is not None and not up:
            state = "starting"
        return {"name": self.name, "port": self.port, "state": state, "up": up,
                "managed": self.jar is not None, "jar": str(self.jar) if self.jar else None,
                "log": str(self.log_path) if self.log_path else None}


def port_open(port):
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


class Console:
    def __init__(self, services, stubs):
        self.services = services
        self.stubs = stubs
        self.transcript = []
        self.lock = threading.Lock()

    def call(self, service, method, path, body):
        svc = self.services[service]
        url = "http://localhost:%d%s" % (svc.port, path)
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        marks = {name: s.mark() for name, s in self.services.items()}
        started = time.time()
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                status, text = resp.status, resp.read().decode()
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode()
        except (urllib.error.URLError, OSError) as e:
            status, text = 0, "connection failed: %s" % getattr(e, "reason", e)
        elapsed = int((time.time() - started) * 1000)
        time.sleep(0.4)
        try:
            parsed = json.loads(text)
        except ValueError:
            parsed = None
        curl = "curl -s -i -X %s localhost:%d%s" % (method, svc.port, path)
        if data is not None:
            curl += " -H 'Content-Type: application/json' -d '%s'" % json.dumps(body)
        entry = {
            "id": len(self.transcript) + 1,
            "time": time.strftime("%H:%M:%S"),
            "service": service, "method": method, "path": path, "requestBody": body,
            "curl": curl, "status": status, "responseText": text, "response": parsed,
            "elapsedMs": elapsed,
            "stubs": {name: st.server is not None for name, st in self.stubs.items()},
            "logs": {name: s.since(marks[name]) for name, s in self.services.items()
                     if s.jar and s.since(marks[name])},
        }
        with self.lock:
            self.transcript.append(entry)
        return entry

    def markdown(self):
        out = []
        for e in self.transcript:
            stubs = ", ".join("%s %s" % (k, "up" if v else "DOWN") for k, v in e["stubs"].items())
            out.append("# %d. %s %s -> %s (%s)" % (e["id"], e["method"], e["path"], e["service"], stubs))
            out.append("$ " + e["curl"])
            out.append("HTTP %s" % (e["status"] or "connection failed"))
            out.append(e["responseText"])
            for name, lines in e["logs"].items():
                for line in lines:
                    out.append("[%s log] %s" % (name, line))
            out.append("")
        return "```\n" + "\n".join(out) + "\n```\n"


def make_handler(console):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass

        def send(self, status, body, ctype="application/json"):
            data = body if isinstance(body, bytes) else (
                body.encode() if isinstance(body, str) else json.dumps(body).encode())
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            path = self.path.split("?")[0]
            if path in ("/", "/index.html"):
                return self.send(200, (STATIC_DIR / "index.html").read_bytes(), "text/html; charset=utf-8")
            if path == "/api/status":
                return self.send(200, {
                    "services": [s.status() for s in console.services.values()],
                    "stubs": [s.status() for s in console.stubs.values()],
                })
            if path == "/api/transcript":
                return self.send(200, console.transcript)
            if path == "/api/transcript.md":
                return self.send(200, console.markdown(), "text/markdown; charset=utf-8")
            m = re.fullmatch(r"/api/logs/([\w-]+)", path)
            if m and m.group(1) in console.services:
                return self.send(200, {"lines": console.services[m.group(1)].tail(200)})
            self.send(404, {"message": "not found"})

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            payload = json.loads(self.rfile.read(length) or b"{}")
            m = re.fullmatch(r"/api/stubs/([\w-]+)/(start|stop)", self.path)
            if m and m.group(1) in console.stubs:
                stub = console.stubs[m.group(1)]
                stub.start() if m.group(2) == "start" else stub.stop()
                return self.send(200, stub.status())
            if self.path == "/api/call" and payload.get("service") in console.services:
                return self.send(200, console.call(payload["service"], payload.get("method", "GET"),
                                                   payload["path"], payload.get("body")))
            if self.path == "/api/transcript/clear":
                console.transcript.clear()
                return self.send(200, {"cleared": True})
            self.send(404, {"message": "not found"})

    return Handler


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--payments-jar", type=Path, help="ps0-payments-service boot jar (omit if already running)")
    p.add_argument("--fx-jar", type=Path, help="ps0-fx-gateway boot jar (omit if already running)")
    p.add_argument("--ledger-jar", type=Path, help="ps0-ledger-service boot jar (omit if already running)")
    p.add_argument("--port", type=int, default=8099, help="console UI port")
    p.add_argument("--payments-port", type=int, default=8090)
    p.add_argument("--fx-port", type=int, default=8091)
    p.add_argument("--ledger-port", type=int, default=8092)
    p.add_argument("--rate-provider-port", type=int, default=9101)
    p.add_argument("--core-banking-port", type=int, default=9102)
    p.add_argument("--log-dir", type=Path, default=Path("e2e-logs"))
    a = p.parse_args()

    for jar in (a.payments_jar, a.fx_jar, a.ledger_jar):
        if jar and not jar.is_file():
            sys.exit("jar not found: %s" % jar)
    a.log_dir.mkdir(parents=True, exist_ok=True)

    stubs = {
        "rate-provider": Stub("rate-provider", a.rate_provider_port, [(r"/v1/quotes/([A-Z]{6})", quote)]),
        "core-banking": Stub("core-banking", a.core_banking_port,
                             [(r"/v1/accounts/([\w-]+)/balance", balance)]),
    }
    services = {
        "payments-service": Service("payments-service", a.payments_port, a.payments_jar, [
            "--acme.payments.fx-base-url=http://localhost:%d" % a.fx_port,
            "--acme.payments.ledger-base-url=http://localhost:%d" % a.ledger_port], a.log_dir),
        "fx-gateway": Service("fx-gateway", a.fx_port, a.fx_jar, [
            "--acme.rate-provider.base-url=http://localhost:%d" % a.rate_provider_port], a.log_dir),
        "ledger-service": Service("ledger-service", a.ledger_port, a.ledger_jar, [
            "--acme.core-banking.base-url=http://localhost:%d" % a.core_banking_port], a.log_dir),
    }
    for stub in stubs.values():
        stub.start()
    for svc in services.values():
        svc.start()

    console = Console(services, stubs)
    server = ThreadingHTTPServer(("127.0.0.1", a.port), make_handler(console))

    def shutdown(*_):
        for svc in services.values():
            svc.stop()
        os._exit(0)
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    print("PS0 stack console on http://localhost:%d (logs in %s)" % (a.port, a.log_dir.resolve()), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
