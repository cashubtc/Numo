#!/usr/bin/env python3
"""Fund the Docker regtest Ark server and a separate Bark payer wallet."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

COMPOSE_FILE = Path(__file__).resolve().with_name("docker-compose.yml")
MINT_URL = os.environ.get("NUMO_ARKOOR_MINT_URL", "http://127.0.0.1:3339").rstrip("/")


def command(*args, check=True):
    result = subprocess.run(
        ["docker", "compose", "--file", str(COMPOSE_FILE), *args],
        capture_output=True, text=True, timeout=60,
    )
    if check and result.returncode:
        raise RuntimeError(f"Docker command failed: {args}\n{result.stderr[-4000:]}")
    return result


def bark(*args):
    return json.loads(command("run", "--rm", "--no-deps", "-T", "bark", "--quiet", *args).stdout)


def bitcoin(*args):
    # bitcoin-cli prints scalar strings (such as transaction IDs) without JSON quotes.
    return command(
        "exec", "-T", "bitcoind", "bitcoin-cli", "-regtest", "-rpcuser=second",
        "-rpcpassword=ark", "-rpcwallet=miner", *args,
    ).stdout.strip()


def wait_for_lightning():
    # Invoice creation needs the funded Ark server's VTXO pool, not only an open RPC port.
    request = urllib.request.Request(
        MINT_URL + "/v1/mint/quote/bolt11",
        data=json.dumps({"amount": 330, "unit": "sat"}).encode(),
        headers={"Content-Type": "application/json"},
    )
    deadline = time.monotonic() + 90
    last_error = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                quote = json.load(response)
        except urllib.error.URLError as error:
            if last_error is None:
                print(f"Waiting for funded Ark server to create Lightning invoices: {error}")
            last_error = error
            time.sleep(1)
            continue
        if not quote.get("request", "").startswith("lnbcrt") or quote.get("state") != "UNPAID":
            raise RuntimeError(f"Expected an unpaid regtest Lightning quote: {quote}")
        return
    raise RuntimeError(f"Lightning quote creation did not become ready: {last_error}")


def provision():
    wallet = json.loads(command("exec", "-T", "captaind", "captaind", "rpc", "wallet").stdout)
    address = wallet["rounds"]["address"]
    if not address.startswith("bcrt1"):
        raise RuntimeError("The Ark server must use Bitcoin regtest")
    if wallet["rounds"]["total_balance"] < 100_000_000:
        bitcoin("sendtoaddress", address, "2")
        bitcoin("-generate", "6")

    existing = command("run", "--rm", "--no-deps", "-T", "bark", "--quiet", "config", check=False)
    if existing.returncode:
        command("run", "--rm", "--no-deps", "-T", "bark", "--quiet", "create", "--regtest",
                "--ark", "http://captaind:3535", "--esplora", "http://electrs:3002")
    # Fail if a payer volume belongs to a different Ark server after a partial reset.
    bark("ark-info")
    if bark("balance")["spendable_sat"] < 100_000:
        address = bark("onchain", "address")["address"]
        if not address.startswith("bcrt1"):
            raise RuntimeError("The payer wallet must use Bitcoin regtest")
        bitcoin("sendtoaddress", address, "0.02")
        bitcoin("-generate", "6")
        command("run", "--rm", "--no-deps", "-T", "bark", "--quiet", "board", "1000000 sat")
        bitcoin("-generate", "6")
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        balance = bark("balance")["spendable_sat"]
        if balance >= 100_000:
            wait_for_lightning()
            print(f"PASS: separate regtest Bark payer funded with {balance} spendable sats")
            return
        time.sleep(1)
    raise RuntimeError("Regtest payer board did not become spendable within 60 seconds")


if __name__ == "__main__":
    provision()
