#!/usr/bin/env python3
"""Verify real Arkoor quote creation and polling without sending funds."""
import argparse
import json
import urllib.error
import urllib.request


def request(base, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(base + path, data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mint", default="http://127.0.0.1:3339")
    args = parser.parse_args()
    base = args.mint.rstrip("/")
    info = request(base, "/v1/info")
    assert not info["nuts"]["4"].get("disabled", False)
    assert any(m["method"] == "arkoor" and m["unit"] == "sat"
               for m in info["nuts"]["4"]["methods"])
    quotes = [request(base, "/v1/mint/quote/arkoor", {"amount": 330, "unit": "sat"})
              for _ in range(2)]
    assert quotes[0]["request"] != quotes[1]["request"], "Checkout addresses must be distinct"
    for quote in quotes:
        assert quote["method"] == "arkoor" and quote["request"].startswith("ark1")
        assert quote["amount"] == 330 and quote["amount_paid"] == 0
        assert quote["amount_issued"] == 0
        status = request(base, "/v1/mint/quote/arkoor/" + quote["quote"])
        assert status["request"] == quote["request"] and status["amount_paid"] == 0
    try:
        request(base, "/v1/mint/quote/bolt11", {"amount": 330, "unit": "sat"})
    except urllib.error.HTTPError as error:
        assert 400 <= error.code < 500
    else:
        raise AssertionError("This experiment should advertise only Arkoor")
    print("PASS: two distinct mainnet Ark requests, unpaid status, no BOLT11 fallback")


if __name__ == "__main__":
    main()
