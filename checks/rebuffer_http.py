"""Read-only, loopback HTTP fixture with a single controlled supply interruption.

Example: python checks/rebuffer_http.py VIDEO.mp4 --initial-mib 4 --pause 20
GET /state reports progress; GET /reset arms the next round after closing the player.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("video", type=Path)
parser.add_argument("--port", type=int, default=14464)
parser.add_argument("--initial-mib", type=int, default=4)
parser.add_argument("--pause", type=float, default=20)
args = parser.parse_args()
args.video = args.video.resolve(strict=True)
assert args.video.is_file() and args.initial_mib > 0 and 0 <= args.pause <= 120
lock = threading.Lock()
gate = dict(start=None, bytes=0, stall=None, resumed=False)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *unused):
        pass

    def do_HEAD(self):
        self.serve(False)

    def do_GET(self):
        if self.path in ("/state", "/reset"):
            with lock:
                if self.path == "/reset":
                    gate.update(start=None, bytes=0, stall=None, resumed=False)
                body = json.dumps(gate).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.serve(True)

    def serve(self, body):
        size = args.video.stat().st_size
        start, end = 0, size - 1
        requested = self.headers.get("Range")
        try:
            if requested:
                if not requested.startswith("bytes="):
                    raise ValueError()
                first, last = requested[6:].split("-")
                start = int(first) if first else max(0, size - int(last))
                if first and last:
                    end = min(end, int(last))
            if start < 0 or start >= size or end < start:
                raise ValueError()
        except ValueError:
            self.send_error(416)
            return
        self.send_response(206 if requested else 200)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Content-Type", "video/mp4")
        if requested:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if not body:
            return
        try:
            with args.video.open("rb") as source:
                source.seek(start)
                left = end - start + 1
                while left:
                    with lock:
                        now = time.monotonic()
                        if gate["start"] is None:
                            gate["start"] = now
                        if gate["bytes"] >= args.initial_mib * 1024 * 1024 and gate["stall"] is None:
                            gate["stall"] = now
                        wait = gate["stall"] is not None and now < gate["stall"] + args.pause
                        gate["resumed"] = gate["stall"] is not None and not wait
                    if wait:
                        time.sleep(0.05)
                        continue
                    data = source.read(min(16384, left))
                    if not data:
                        break
                    self.wfile.write(data)
                    self.wfile.flush()
                    left -= len(data)
                    with lock:
                        gate["bytes"] += len(data)
                    time.sleep(0.015)
        except OSError:
            pass  # Intentional cancellation and range changes close the response.


ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
