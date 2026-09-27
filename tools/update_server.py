#!/usr/bin/env python3
"""Serve signed KCoach debug APKs and manifests on the local network."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

DEFAULT_ROOT = (
    Path(__file__).resolve().parent
    if Path(__file__).resolve().parent.name == "KCoachUpdateServer"
    else Path(__file__).resolve().parents[1]
)
ROOT = Path(os.environ.get("KCOACH_UPDATE_ROOT", DEFAULT_ROOT))
ARTIFACTS = {
    "phone": ROOT / "phone/build/outputs/apk/debug/phone-debug.apk",
    "watch": ROOT / "wear/build/outputs/apk/ota/wear-ota.apk",
}
GRADLE_FILES = {
    "phone": ROOT / "phone/build.gradle.kts",
    "watch": ROOT / "wear/build.gradle.kts",
}


def version(kind: str) -> tuple[int, str]:
    text = GRADLE_FILES[kind].read_text()
    code = int(re.search(r"versionCode\s*=\s*(\d+)", text).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1)
    return code, name


CANONICAL_HOST = "gyeongmin-ui-MacBook-Neo.local"


def canonical_host(host: str) -> str:
    # Android URI host comparison in shipped builds is case-sensitive.
    # Preserve the exact hostname casing embedded in the app manifest URL.
    return CANONICAL_HOST if host.lower() == CANONICAL_HOST.lower() else host


def current_ipv4(fallback: str) -> str:
    """Return the IPv4 address selected by the current default LAN route."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # UDP connect selects a route without sending any traffic.
        probe.connect(("192.0.2.1", 9))
        address = probe.getsockname()[0]
        return address if address != "0.0.0.0" else fallback
    except OSError:
        return fallback
    finally:
        probe.close()


def manifest(kind: str, host: str, port: int) -> dict:
    host = current_ipv4(canonical_host(host))
    url_host = f"[{host}]" if ":" in host and not host.startswith("[") else host
    artifact = ARTIFACTS[kind]
    if not artifact.is_file():
        task = f":{kind}:assembleDebug" if kind == "phone" else ":wear:assembleOta"
        raise FileNotFoundError(f"APK not found: {artifact}; build {task} first")
    code, name = version(kind)
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    filename = "phone-debug.apk" if kind == "phone" else "wear-debug.apk"
    return {
        "versionCode": code,
        "versionName": name,
        "apkUrl": f"http://{url_host}:{port}/artifacts/{filename}",
        "sha256": digest,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "KCoachUpdateServer/1.1"

    def do_HEAD(self):  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/artifacts/phone-debug.apk":
            self.send_file(ARTIFACTS["phone"], head_only=True)
            return
        if parsed.path == "/artifacts/wear-debug.apk":
            self.send_file(ARTIFACTS["watch"], head_only=True)
            return
        self.send_error(404, "not found")

    def do_GET(self):  # noqa: N802
        parsed = urlparse(self.path)
        host_header = self.headers.get("Host", "127.0.0.1")
        host = urlparse(f"//{host_header}").hostname or "127.0.0.1"
        port = self.server.server_address[1]
        if parsed.path == "/":
            self.send_home(host, port)
            return
        if parsed.path == "/healthz":
            self.send_json({"status": "ok", "host": host, "port": port})
            return
        if parsed.path in ("/update.json", "/update-phone.json"):
            self.send_json(manifest("phone", host, port))
            return
        if parsed.path == "/update-watch.json":
            self.send_json(manifest("watch", host, port))
            return
        if parsed.path == "/artifacts/phone-debug.apk":
            self.send_file(ARTIFACTS["phone"])
            return
        if parsed.path == "/artifacts/wear-debug.apk":
            self.send_file(ARTIFACTS["watch"])
            return
        self.send_error(404, "not found")

    def send_home(self, host: str, port: int):
        phone_code, phone_name = version("phone")
        watch_code, watch_name = version("watch")
        body = f"""<!doctype html>
<html lang="ko"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>KCoach 업데이트</title>
<style>body{{font-family:-apple-system,sans-serif;max-width:560px;margin:40px auto;padding:20px;background:#0a0c0c;color:#f5f6f6}}
a{{display:block;margin:16px 0;padding:18px;border-radius:14px;background:#ffb100;color:#111;text-decoration:none;font-weight:700}}
small{{color:#929696}}</style>
<h1>KCoach 업데이트 서버</h1>
<p>서버 정상 작동 중 · {socket.gethostname()}</p>
<a href="/artifacts/phone-debug.apk">폰 {phone_name} 직접 다운로드 (code {phone_code})</a>
<a href="/artifacts/wear-debug.apk">워치 {watch_name} 직접 다운로드 (code {watch_code})</a>
<small>같은 Wi-Fi에서 http://{host}:{port} 로 접속</small>
</html>""".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, payload: dict):
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path: Path, head_only: bool = False):
        if not path.is_file():
            self.send_error(404, "APK not built")
            return
        size = path.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        if not head_only:
            with path.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    self.wfile.write(chunk)

    def log_message(self, fmt, *args):
        request_host = self.headers.get("Host", "<missing>")
        print(f"[update] {self.address_string()} host={request_host} - {fmt % args}")


class IPv6ThreadingHTTPServer(ThreadingHTTPServer):
    address_family = socket.AF_INET6

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        super().server_bind()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    ipv6_server = None
    if args.host == "0.0.0.0":
        ipv6_server = IPv6ThreadingHTTPServer(("::", args.port), Handler)
        threading.Thread(target=ipv6_server.serve_forever, daemon=True).start()
    listeners = f"{args.host}:{args.port}" + (f" and [::]:{args.port}" if ipv6_server else "")
    print(f"KCoach update server listening on {listeners}", flush=True)
    try:
        server.serve_forever()
    finally:
        if ipv6_server:
            ipv6_server.shutdown()
            ipv6_server.server_close()


if __name__ == "__main__":
    main()
