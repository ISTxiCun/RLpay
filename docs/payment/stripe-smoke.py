#!/usr/bin/env python3
"""Sign RLpay merchant requests for the Stripe sandbox checklist."""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

PAYMENTS = "/api/merchant/payments"


def request(method, path, payload=None):
    """Sign the exact bytes sent to RLpay, with a fresh nonce per request."""
    base_url = os.getenv("RLPAY_SMOKE_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
    target = urllib.parse.urlsplit(base_url)
    if target.scheme != "https" and not (
        target.scheme == "http" and target.hostname in {"127.0.0.1", "localhost", "::1"}
    ):
        raise ValueError("Use HTTPS except on loopback")
    if target.username or target.password or target.query or target.fragment:
        raise ValueError("Base URL must not contain credentials, a query or a fragment")

    key_id = os.environ["RLPAY_MERCHANT_API_CREDENTIALS_0_ID"]
    secret = base64.b64decode(os.environ["RLPAY_MERCHANT_API_CREDENTIALS_0_SECRET"], validate=True)
    if len(secret) < 32:
        raise ValueError("Merchant key must contain at least 32 decoded bytes")
    body = b"" if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
    time_seconds = str(int(time.time()))
    nonce = uuid.uuid4().hex
    signed_path = target.path + path
    canonical = "\n".join((method, signed_path, key_id, time_seconds, nonce,
                           hashlib.sha256(body).hexdigest())).encode("utf-8")
    headers = {
        "X-Key-Id": key_id, "X-Timestamp": time_seconds, "X-Nonce": nonce,
        "X-Signature": hmac.new(secret, canonical, hashlib.sha256).hexdigest(),
    }
    if payload is not None:
        headers["Content-Type"] = "application/json"
    outgoing = urllib.request.Request(base_url + path, body if method == "POST" else None, headers, method=method)
    with urllib.request.urlopen(outgoing, timeout=30) as response:
        return json.load(response)


def main():
    """Dispatch one repeatable checkout operation without displaying secrets."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create")
    create.add_argument("merchant_order_no")
    create.add_argument("--amount-cent", type=int, default=1290)
    for command in ("start", "query"):
        commands.add_parser(command).add_argument("payment_no")
    args = parser.parse_args()
    try:
        if args.command == "create":
            result = request("POST", PAYMENTS, {
                "merchantOrderNo": args.merchant_order_no, "amountCent": args.amount_cent
            })
        else:
            payment_no = urllib.parse.quote(args.payment_no, safe="")
            path = f"{PAYMENTS}/{payment_no}"
            result = (request("POST", path + "/stripe-checkout") if args.command == "start"
                      else request("GET", path))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except urllib.error.HTTPError as error:
        print(f"HTTP {error.code}: {error.read(4096).decode('utf-8', errors='replace')}", file=sys.stderr)
    except (KeyError, ValueError, OSError) as error:
        print(f"Request failed: {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
