#!/usr/bin/env python3
"""A deliberate slowdown, to see the timing limits fail it: runs the Cuttlebone named by SLOW_CUTTLEBONE
and holds each of its responses (a message with an `id` and a `result` or `error`) SLOW_MS milliseconds
before passing it on. Notifications pass at once, and nothing else changes. It doesn't touch `inko`, so
the timing baseline (ci/timing-baseline.py), and so the limits' scale, stays what it was.

build.yml's Cuttlebone job uses it when run by hand with `slow`. Usage: point CUTTLEBONE at this script.
"""
import json
import os
import subprocess
import sys
import threading

DELAY = int(os.environ["SLOW_MS"]) / 1000


def main():
    # `--version`, which Parakeet asks before starting it, isn't LSP.
    if sys.argv[1:]:
        os.execv(os.environ["SLOW_CUTTLEBONE"], [os.environ["SLOW_CUTTLEBONE"], *sys.argv[1:]])
    server = subprocess.Popen([os.environ["SLOW_CUTTLEBONE"]], stdin=subprocess.PIPE, stdout=subprocess.PIPE)

    def forward_input():
        for chunk in iter(lambda: sys.stdin.buffer.read1(65536), b""):
            server.stdin.write(chunk)
            server.stdin.flush()
        server.stdin.close()

    threading.Thread(target=forward_input, daemon=True).start()
    out = sys.stdout.buffer
    lock = threading.Lock()

    def send(message):
        with lock:
            out.write(message)
            out.flush()

    while True:
        headers = {}
        while True:
            line = server.stdout.readline()
            if not line:
                sys.exit(server.wait())
            if line in (b"\r\n", b"\n"):
                break
            name, _, value = line.decode().partition(":")
            headers[name.strip().lower()] = value.strip()
        body = server.stdout.read(int(headers["content-length"]))
        message = b"Content-Length: %d\r\n\r\n" % len(body) + body
        parsed = json.loads(body)
        if "id" in parsed and ("result" in parsed or "error" in parsed):
            threading.Timer(DELAY, send, [message]).start()
        else:
            send(message)


if __name__ == "__main__":
    main()
