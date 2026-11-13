#!/usr/bin/env python3
"""Wait for Electrs to expose confirmed regtest funding to the Bark payer."""

import argparse
import json
from pathlib import Path
import subprocess
import time


COMPOSE_FILE = (
    Path(__file__).resolve().parents[1] / "integration-tests/arkoor/docker-compose.yml"
)


def confirmed_balance(timeout_seconds):
    result = subprocess.run(
        ["docker", "compose", "--file", str(COMPOSE_FILE), "run", "--rm",
         "--no-deps", "-T", "bark", "--quiet", "onchain", "balance"],
        check=True, capture_output=True, text=True, timeout=timeout_seconds,
    )
    return int(json.loads(result.stdout)["confirmed_sat"])


def wait_for_funding(required_sats, timeout_seconds=60):
    if required_sats <= 0 or timeout_seconds <= 0:
        raise ValueError("Funding amount and timeout must be positive")
    deadline = time.monotonic() + timeout_seconds
    last_balance = 0
    last_error = None
    waiting_logged = False
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            last_balance = confirmed_balance(min(60, remaining))
            last_error = None
            if last_balance >= required_sats:
                print(f"PASS: Bark payer sees {last_balance} confirmed on-chain sats")
                return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            last_error = str(error)
            if error.stderr:
                last_error += f": {error.stderr[-2000:]}"
        if not waiting_logged:
            print(f"Waiting for Bark payer to see {required_sats} confirmed on-chain sats")
            waiting_logged = True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(1, remaining))
    detail = f"last confirmed balance: {last_balance} sats"
    if last_error:
        detail += f"; last balance query failed: {last_error}"
    raise RuntimeError(f"Payer funding was not visible within {timeout_seconds} seconds ({detail})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--amount-sats", type=int, required=True)
    parser.add_argument("--timeout-seconds", type=float, default=60)
    args = parser.parse_args()
    wait_for_funding(args.amount_sats, args.timeout_seconds)


if __name__ == "__main__":
    main()
