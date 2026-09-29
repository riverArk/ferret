#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || ( $1 != aarch64-apple-ios && $1 != aarch64-apple-ios-sim ) ]]; then
    echo 'usage: scripts/build-cardano-ios.sh <aarch64-apple-ios|aarch64-apple-ios-sim>' >&2
    exit 2
fi

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root/native/cardano-ios-bridge"
IPHONEOS_DEPLOYMENT_TARGET=17.0 cargo build --locked --release --target "$1"
