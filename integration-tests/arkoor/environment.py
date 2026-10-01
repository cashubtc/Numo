#!/usr/bin/env python3
"""Run the Numo experimental mint with the existing local Bark wallet."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import time
import tomllib
import urllib.request

CDK = Path(os.environ.get("NUMO_CDK_DIR", Path.home() / "cdk"))
BARK = Path(os.environ.get("NUMO_BARK_DIR", Path.home() / "cdk-payment-processors/crates/bark"))
STATE = Path(os.environ.get("NUMO_ARK_STATE", Path.home() / ".local/share/numo-arkoor"))
MINT_PORT = 3339
PROCESSOR_PORT = 50059


def live(name):
    path = STATE / f"{name}.pid"
    if not path.exists():
        return None
    pid = int(path.read_text())
    try:
        cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
        expected = b"cdk-mintd" if name == "mint" else b"cdk-payment-processor-bark"
        if expected in cmdline:
            os.kill(pid, 0)
            return pid
    except (FileNotFoundError, ProcessLookupError):
        pass
    return None


def wait_port(port, process):
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError(f"Service exited with {process.returncode}; inspect logs in {STATE}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return
        except OSError:
            time.sleep(0.5)
    raise RuntimeError(f"Service is still starting; inspect logs in {STATE}")


def spawn(name, cmd, cwd, env, port):
    if live(name):
        print(f"{name} already running (PID {live(name)})")
        return
    with socket.socket() as check:
        # A stopped service can leave connections in TIME_WAIT during a restart.
        check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        check.bind(("127.0.0.1", port))
    with (STATE / f"{name}.log").open("ab") as log:
        process = subprocess.Popen(cmd, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (STATE / f"{name}.pid").write_text(str(process.pid))
    wait_port(port, process)
    print(f"{name} started (PID {process.pid})")


def start():
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    binary = CDK / "target/debug/cdk-mintd"
    processor = BARK / "target/release/cdk-payment-processor-bark"
    if not binary.is_file() or not processor.is_file():
        raise RuntimeError("Build CDK mintd and the Bark processor first; see README.md")
    # Read the existing configuration without printing its mnemonic.
    config = tomllib.loads((BARK / "config.toml").read_text())
    bark_config = config["bark"]
    if bark_config.get("network") != "mainnet":
        raise RuntimeError("This setup expects the existing Bark mainnet wallet")
    data = Path(bark_config.get("data_dir", ".data/bark"))
    if not data.is_absolute():
        data = BARK / data
    if not (data / "db.sqlite").is_file():
        raise RuntimeError("Existing Bark wallet database was not found")
    if not live("processor"):
        # Preserve existing wallet state before the experiment first opens it.
        with (data / "LOCK").open("a+") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            backup = STATE / "bark-before-experiment"
            if not backup.exists():
                shutil.copytree(data, backup)
        env = dict(os.environ, BARK_PAYMENT_METHODS="bolt11,arkoor", SERVER_ADDRESS="127.0.0.1",
                   SERVER_PORT=str(PROCESSOR_PORT), TLS_ENABLE="false", ALLOW_INSECURE="true")
        spawn("processor", [str(processor)], BARK, env, PROCESSOR_PORT)
    mint_dir = STATE / "mint"
    mint_dir.mkdir(exist_ok=True, mode=0o700)
    seed = STATE / "mint.seed"
    if not seed.exists():
        with seed.open("x") as handle:
            os.chmod(seed, 0o600)
            handle.write(secrets.token_hex(64))
    config_path = STATE / "mint.toml"
    if not config_path.exists():
        config_path.write_text(f'''[info]
url = "http://127.0.0.1:{MINT_PORT}"
listen_host = "127.0.0.1"
listen_port = {MINT_PORT}
seed = "file:{seed}"

[info.quote_ttl]
mint_ttl = 3600
melt_ttl = 120

[mint_info]
name = "Numo Arkoor experiment"
description = "Mainnet ecash backed by the local Bark wallet"

[payment_backend]
backend = "grpcprocessor"
unit = "sat"
min_mint = 330
max_mint = 1000000

[grpc_processor]
address = "127.0.0.1"
port = {PROCESSOR_PORT}
supported_units = ["sat"]
allow_insecure = true

[database]
engine = "sqlite"
''')
    marker = STATE / "mint-initialized"
    if not marker.exists():
        # Config is persisted in CDK's database; normal starts do not re-import TOML.
        subprocess.run([str(binary), "--work-dir", str(mint_dir), "config", "init",
                        "--new-mint", "--file", str(config_path)], check=True)
        marker.touch()
    spawn("mint", [str(binary), "--work-dir", str(mint_dir)], CDK, os.environ.copy(), MINT_PORT)
    status()


def status():
    for name in ("processor", "mint"):
        pid = live(name)
        print(f"{name}: {'running, PID ' + str(pid) if pid else 'stopped'}")
    with urllib.request.urlopen(f"http://127.0.0.1:{MINT_PORT}/v1/info", timeout=10) as response:
        info = json.load(response)
    methods = info["nuts"]["4"]["methods"]
    supported = {(m["method"], m["unit"]) for m in methods}
    assert {("arkoor", "sat"), ("bolt11", "sat")} <= supported, methods
    print(f"Mint healthy: {info.get('name')}; mint methods: {methods}")


def stop():
    for name in ("mint", "processor"):
        pid = live(name)
        if pid:
            os.kill(pid, signal.SIGTERM)
            print(f"Sent SIGTERM to {name} (PID {pid})")
            for _ in range(100):
                if not live(name):
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError(f"{name} has not stopped; leaving it running")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "status", "stop"))
    args = parser.parse_args()
    {"start": start, "status": status, "stop": stop}[args.command]()
