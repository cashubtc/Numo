#!/usr/bin/env python3
"""Verify real regtest Arkoor and Lightning quotes without sending funds."""
import argparse
import json
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
    quotes = [request(base, "/v1/mint/quote/arkoor", {"amount": 2, "unit": "sat"})
              for _ in range(2)]
    assert quotes[0]["request"] != quotes[1]["request"], "Checkout addresses must be distinct"
    for quote in quotes:
        assert quote["method"] == "arkoor" and quote["request"].startswith("tark1")
        assert quote["amount"] == 2 and quote["amount_paid"] == 0
        assert quote["amount_issued"] == 0
        status = request(base, "/v1/mint/quote/arkoor/" + quote["quote"])
        assert status["request"] == quote["request"] and status["amount_paid"] == 0
    # Bark's default Lightning receive fee exceeds two sats; use a viable invoice amount.
    lightning = request(base, "/v1/mint/quote/bolt11", {"amount": 330, "unit": "sat"})
    assert lightning["request"].startswith("lnbcrt"), lightning
    assert lightning["quote"] not in {q["quote"] for q in quotes}
    assert lightning["state"] == "UNPAID", lightning
    status = request(base, "/v1/mint/quote/bolt11/" + lightning["quote"])
    assert status["request"] == lightning["request"] and status["state"] == "UNPAID"
    print("PASS: independent 2-sat Arkoor and 330-sat Lightning regtest quotes, all unpaid")


if __name__ == "__main__":
    main()
