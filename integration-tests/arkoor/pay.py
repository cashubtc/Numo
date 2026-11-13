#!/usr/bin/env python3
"""Pay an Arkoor regtest quote from the Docker stack's provisioned payer."""
import argparse

from provision import command


def pay(address, amount):
    if not address.lower().startswith("tark1") or amount <= 0:
        raise ValueError("A regtest Ark address and positive sat amount are required")
    command("run", "--rm", "--no-deps", "-T", "bark", "--quiet", "send",
            "--wait", address, f"{amount} sat")
    print(f"Sent {amount} regtest sats to the Arkoor quote")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", required=True)
    parser.add_argument("--amount", type=int, required=True)
    args = parser.parse_args()
    pay(args.address, args.amount)
