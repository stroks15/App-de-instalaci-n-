#!/usr/bin/env python3
"""Small authenticated control plane for a single Linux WireGuard gateway."""
import base64
import hmac
import ipaddress
import json
import os
import re
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

TOKEN = os.environ.get("WG_MANAGER_TOKEN", "")
INTERFACE = os.environ.get("WG_INTERFACE", "wg0")
STATE_FILE = Path(os.environ.get("WG_STATE_FILE", "/var/lib/kioskgrid-vpn-manager/peers.json"))
HOST = os.environ.get("WG_MANAGER_BIND", "127.0.0.1")
PORT = int(os.environ.get("WG_MANAGER_PORT", "8787"))
LOCK = threading.Lock()
DEVICE_RE = re.compile(r"^[A-Za-z0-9._:-]{8,128}$")
NETWORK = ipaddress.ip_network("10.88.0.0/16")


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=12)


def load_state():
    try:
        data = json.loads(STATE_FILE.read_text())
        return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}


def save_state(state):
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    temporary = STATE_FILE.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2))
    os.chmod(temporary, 0o600)
    temporary.replace(STATE_FILE)


class Handler(BaseHTTPRequestHandler):
    server_version = "KioskGridWGManager/1.0"

    def respond(self, status, payload):
        encoded = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def authorized(self):
        supplied = self.headers.get("Authorization", "")
        expected = "Bearer " + TOKEN
        return bool(TOKEN) and hmac.compare_digest(supplied, expected)

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 2 or length > 16384:
            raise ValueError("invalid_body_size")
        body = json.loads(self.rfile.read(length))
        if not isinstance(body, dict):
            raise ValueError("invalid_json_object")
        return body

    def do_GET(self):
        if urlparse(self.path).path == "/health":
            self.respond(200, {"ok": True, "interface": INTERFACE})
        else:
            self.respond(404, {"error": "not_found"})

    def do_POST(self):
        if not self.authorized():
            self.respond(401, {"error": "unauthorized"})
            return
        if urlparse(self.path).path != "/api/peers":
            self.respond(404, {"error": "not_found"})
            return
        try:
            body = self.read_json()
            device_id = str(body.get("device_id", ""))
            public_key = str(body.get("public_key", ""))
            allowed_ip = ipaddress.ip_interface(str(body.get("allowed_ip", "")))
            decoded = base64.b64decode(public_key, validate=True)
            if not DEVICE_RE.fullmatch(device_id) or len(decoded) != 32:
                raise ValueError("invalid_device_or_public_key")
            if allowed_ip.version != 4 or allowed_ip.network.prefixlen != 32:
                raise ValueError("only_ipv4_host_routes_allowed")
            if allowed_ip.ip not in NETWORK or allowed_ip.ip == ipaddress.ip_address("10.88.0.1"):
                raise ValueError("address_outside_kioskgrid_pool")

            with LOCK:
                state = load_state()
                old_key = state.get(device_id)
                if old_key and old_key != public_key:
                    run("wg", "set", INTERFACE, "peer", old_key, "remove")
                run("wg", "set", INTERFACE, "peer", public_key,
                    "allowed-ips", str(allowed_ip))
                state[device_id] = public_key
                save_state(state)
                # Persist dynamic peer changes to wg0.conf when wg-quick is used.
                run("wg-quick", "save", INTERFACE)
            self.respond(200, {"ok": True, "device_id": device_id, "allowed_ip": str(allowed_ip)})
        except (ValueError, KeyError, json.JSONDecodeError) as error:
            self.respond(400, {"error": str(error)})
        except subprocess.CalledProcessError as error:
            self.respond(502, {"error": "wireguard_command_failed", "detail": error.stderr[-500:]})
        except Exception:
            self.respond(500, {"error": "peer_provisioning_failed"})

    def do_DELETE(self):
        if not self.authorized():
            self.respond(401, {"error": "unauthorized"})
            return
        query = parse_qs(urlparse(self.path).query)
        device_id = (query.get("device_id") or [""])[0]
        if not DEVICE_RE.fullmatch(device_id):
            self.respond(400, {"error": "invalid_device_id"})
            return
        try:
            with LOCK:
                state = load_state()
                public_key = state.pop(device_id, None)
                if public_key:
                    run("wg", "set", INTERFACE, "peer", public_key, "remove")
                    save_state(state)
                    run("wg-quick", "save", INTERFACE)
            self.respond(200, {"ok": True, "removed": bool(public_key)})
        except subprocess.CalledProcessError as error:
            self.respond(502, {"error": "wireguard_command_failed", "detail": error.stderr[-500:]})
        except Exception:
            self.respond(500, {"error": "peer_removal_failed"})

    def log_message(self, fmt, *args):
        # Avoid logging bearer tokens or request bodies.
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)


if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("WG_MANAGER_TOKEN is required")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
