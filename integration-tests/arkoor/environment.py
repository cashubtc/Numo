#!/usr/bin/env python3
"""Manage the isolated Docker Compose Arkoor regtest environment."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import urllib.request

COMPOSE_FILE = Path(__file__).resolve().with_name("docker-compose.yml")
MINT_URL = os.environ.get("NUMO_ARKOOR_MINT_URL", "http://127.0.0.1:3339").rstrip("/")


def compose(*args):
    return subprocess.run(
        ["docker", "compose", "--file", str(COMPOSE_FILE), *args], check=True
    )


def mint_info():
    with urllib.request.urlopen(MINT_URL + "/v1/info", timeout=10) as response:
        info = json.load(response)
    nut = info.get("nuts", {}).get("4", {})
    supported = {(method["method"], method["unit"]) for method in nut.get("methods", [])}
    if nut.get("disabled", False) or not {("arkoor", "sat"), ("bolt11", "sat")} <= supported:
        raise RuntimeError(f"Mint must support Arkoor and Lightning in sats: {nut}")
    return info


def status():
    compose("ps", "--all")
    info = mint_info()
    print(f"Mint healthy at {MINT_URL}: {info.get('name')}; Arkoor and Lightning enabled")


def start(build=True):
    args = ["up", "--detach", "--wait", "--wait-timeout", "240"]
    args.append("--build" if build else "--no-build")
    try:
        compose(*args)
        status()
    except (subprocess.CalledProcessError, OSError, RuntimeError):
        subprocess.run(
            ["docker", "compose", "--file", str(COMPOSE_FILE), "logs", "--tail", "80"],
            check=False,
        )
        raise


def stop():
    # Keep test wallets and quote IDs for a subsequent restart.
    compose("down", "--remove-orphans")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "status", "stop"))
    parser.add_argument("--no-build", action="store_true", help="Use the existing services image")
    args = parser.parse_args()
    if args.command == "start":
        start(build=not args.no_build)
    else:
        {"status": status, "stop": stop}[args.command]()
