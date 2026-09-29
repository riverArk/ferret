#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 || $# > 2 )); then
    echo 'Usage: ferret-version.sh <tag> [run-attempt]' >&2
    exit 1
fi
if [[ ! $1 =~ ^v([0-9]{1,2})\.([0-9]{1,2})\.([0-9]{1,2})(-(alpha|beta|rc)([0-9]{1,2}))?$ ]]; then
    echo 'Invalid release tag' >&2
    exit 1
fi
major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))
kind=${BASH_REMATCH[5]:-}
build=${BASH_REMATCH[6]:-0}
build=$((10#$build))
attempt=${2:-1}
if [[ ! $attempt =~ ^[0-9]{1,2}$ ]] || (( 10#$attempt < 1 )); then
    echo 'Invalid run attempt' >&2
    exit 1
fi
attempt=$((10#$attempt))
case "$kind" in
    alpha) stage=1; draft=true ;;
    beta) stage=2; draft=true ;;
    rc) stage=3; draft=true ;;
    '') stage=4; draft=false ;;
esac
printf 'VERSION_NAME=%s\nVERSION_CODE=%d\nRELEASE_DRAFT=%s\nIOS_MARKETING_VERSION=%d.%d.%d\nIOS_BUILD_VERSION=%d.0.%d\n' \
    "${1#v}" "$((major * 10000000 + minor * 100000 + patch * 1000 + stage * 100 + build))" \
    "$draft" "$major" "$minor" "$patch" "$((stage * 100 + build))" "$attempt"
