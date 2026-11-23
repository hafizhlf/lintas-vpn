#!/usr/bin/env python3
"""Create fresh WARP registrations and inspect their account entitlement.

The registration payload mirrors WarpApiClient.register() in the Android app:
  - Xiaomi 2602BPC18G
  - Android 16
  - en_US
  - random 16-byte serial number
  - empty install_id and fcm_token
  - WireGuard curve25519 public key

By default each test device is deleted again so it does not keep a WARP+ seat.
"""

import argparse
import base64
import gzip
import json
import secrets
import sys
import time
from datetime import datetime
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

BASE_URL = "https://api.cloudflareclient.com"
API_VERSION = "v0a4471"
CLIENT_VERSION = "a-6.35-4471"
USER_AGENT = "WARP for Android"


def timestamp() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def generate_wireguard_public_key() -> str:
    private_key = X25519PrivateKey.generate()
    public_key = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    return base64.b64encode(public_key).decode("ascii")


def mask(value: str) -> str:
    if not value:
        return "(empty)"
    if len(value) <= 12:
        return "***"
    return f"{value[:6]}…{value[-4:]}"


def call(method: str, path: str, body=None, token: str | None = None):
    data = None
    headers = {
        "User-Agent": USER_AGENT,
        "CF-Client-Version": CLIENT_VERSION,
        "Accept": "application/json",
        "Accept-Encoding": "gzip",
    }
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=UTF-8"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    request = Request(BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=30) as response:
            raw = response.read()
            if response.headers.get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
            text = raw.decode("utf-8")
            return response.status, json.loads(text) if text.strip() else {}
    except HTTPError as error:
        raw = error.read()
        if error.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        text = raw.decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code} {method} {path}: {text}") from error
    except URLError as error:
        raise RuntimeError(f"network error: {error.reason}") from error


def account_summary(account: dict) -> dict:
    account_type = str(account.get("account_type") or "").lower()
    return {
        "account_type": account.get("account_type"),
        "warp_plus_field": account.get("warp_plus"),
        "warp_plus_effective": account_type in {"unlimited", "warp_plus"},
        "plus_expires_at": account.get("plus_expires_at"),
        "premium_data": account.get("premium_data"),
        "quota": account.get("quota"),
        "license": mask(str(account.get("license") or "")),
    }


def run_one(index: int, args) -> bool:
    print(f"\n=== registration {index} ===")
    body = {
        "key": generate_wireguard_public_key(),
        "install_id": "",
        "fcm_token": "",
        "tos": timestamp(),
        "model": args.model,
        "serial_number": secrets.token_hex(16),
        "os_version": args.os_version,
        "key_type": "curve25519",
        "tunnel_type": "wireguard",
        "locale": args.locale,
    }

    device_id = None
    token = None
    try:
        status, registration = call("POST", f"/{args.api_version}/reg", body)
        device_id = registration.get("id")
        token = registration.get("token")
        if not device_id or not token:
            raise RuntimeError(f"registration did not return id/token: {registration}")

        print(f"http_status: {status}")
        print(f"device_id: {device_id}")
        print(f"token: {mask(token)}")
        print("account after register:", json.dumps(account_summary(registration.get("account") or {}), indent=2))

        call(
            "PATCH",
            f"/{args.api_version}/reg/{device_id}",
            {"warp_enabled": True},
            token,
        )
        _, account = call(
            "GET",
            f"/{args.api_version}/reg/{device_id}/account",
            token=token,
        )
        summary = account_summary(account)
        print("account after enable:", json.dumps(summary, indent=2))
        return summary.get("warp_plus_effective") is True
    finally:
        if device_id and token and not args.keep:
            try:
                call("DELETE", f"/{args.api_version}/reg/{device_id}", token=token)
                print("device deleted")
            except Exception as error:
                print(f"delete failed: {error}", file=sys.stderr)
        elif device_id and args.keep:
            print(f"kept device: {device_id}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=1, help="number of fresh registrations (default: 1)")
    parser.add_argument("--keep", action="store_true", help="do not delete test devices")
    parser.add_argument("--model", default="Xiaomi 2602BPC18G", help="registration model field")
    parser.add_argument("--os-version", default="16", help="registration os_version field")
    parser.add_argument("--locale", default="en_US", help="registration locale field")
    parser.add_argument("--api-version", default=API_VERSION, help="Cloudflare API version")
    args = parser.parse_args()

    if args.count < 1:
        parser.error("--count must be at least 1")

    results = []
    for index in range(1, args.count + 1):
        try:
            results.append(run_one(index, args))
        except Exception as error:
            print(f"registration {index} failed: {error}", file=sys.stderr)
            results.append(None)
        if index < args.count:
            time.sleep(1)

    print("\n=== summary ===")
    for index, result in enumerate(results, start=1):
        state = "warp_plus" if result else "not_warp_plus" if result is False else "error"
        print(f"{index}: {state}")
    return 0 if all(result is not None for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
