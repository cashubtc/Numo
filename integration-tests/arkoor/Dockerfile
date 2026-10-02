# syntax=docker/dockerfile:1
FROM rust:1.98.0-trixie AS builder
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git pkg-config libssl-dev protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*
ENV RUSTUP_TOOLCHAIN=1.98.0 CARGO_BUILD_JOBS=2 CARGO_PROFILE_DEV_DEBUG=0 CARGO_INCREMENTAL=0

# Public, immutable revisions; no sibling checkout or personal wallet is required.
WORKDIR /src/cdk
RUN git init && git remote add origin https://github.com/cashubtc/cdk.git \
    && git fetch --depth 1 origin c29da8f4b2897a313ac480994fbfd46dc73e15f3 \
    && git checkout --detach FETCH_HEAD
RUN cargo build --locked -p cdk-mintd --no-default-features --features grpc-processor,sqlite

WORKDIR /src/processors
RUN git init && git remote add origin https://github.com/cashubtc/cdk-payment-processors.git \
    && git fetch --depth 1 origin c7940dd9add3f2ac399da596d253f582809230b1 \
    && git checkout --detach FETCH_HEAD
# Incoming Arkoor support is not yet in the processor's public v0.1.0 release.
COPY patches/bark-arkoor-receive.patch /tmp/bark-arkoor-receive.patch
RUN git apply --check /tmp/bark-arkoor-receive.patch \
    && git apply /tmp/bark-arkoor-receive.patch
WORKDIR /src/processors/crates/bark
# Keep compiled dependencies cached when the small event-loop patch changes.
RUN cargo build --locked --bin cdk-payment-processor-bark
COPY patches/bark-nonblocking-events.patch /tmp/bark-nonblocking-events.patch
RUN git apply --check /tmp/bark-nonblocking-events.patch \
    && git apply /tmp/bark-nonblocking-events.patch \
    && cargo build --locked --bin cdk-payment-processor-bark \
    && cargo test --locked --lib

FROM debian:trixie-slim AS services
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl libssl3t64 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /src/cdk/target/debug/cdk-mintd /usr/local/bin/
COPY --from=builder /src/processors/crates/bark/target/debug/cdk-payment-processor-bark /usr/local/bin/
COPY mint.toml /etc/numo/mint.toml
COPY mint-entrypoint.sh /usr/local/bin/mint-entrypoint.sh
CMD ["cdk-payment-processor-bark"]
