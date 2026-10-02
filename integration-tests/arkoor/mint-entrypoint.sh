#!/bin/sh
set -eu

mkdir -p /data/mint
if [ ! -f /data/mint/initialized ]; then
    cdk-mintd --work-dir /data/mint config init --new-mint --file /etc/numo/mint.toml
    touch /data/mint/initialized
fi
exec cdk-mintd --work-dir /data/mint
