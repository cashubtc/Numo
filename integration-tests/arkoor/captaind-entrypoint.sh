#!/bin/sh
set -eu

# CLN's entrypoint creates and re-signs the hold certificates before serving RPCs.
for attempt in $(seq 1 120); do
    if [ -f /data/cln/regtest/generation.done ]; then
        break
    fi
    if [ "$attempt" -eq 120 ]; then
        echo "Timed out waiting for CLN hold certificates" >&2
        exit 1
    fi
    sleep 1
done
test -f /data/cln/regtest/generation.done
if [ ! -f /data/captaind/mnemonic ]; then
    captaind --config /etc/numo/captaind.toml create
fi
exec captaind --config /etc/numo/captaind.toml start
