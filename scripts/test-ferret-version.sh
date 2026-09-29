#!/usr/bin/env bash
set -euo pipefail
script="$(dirname "$0")/ferret-version.sh"
assert_version() {
    local actual
    actual=$("$script" "$1" "${6:-1}")
    local expected
    expected=$(printf 'VERSION_NAME=%s\nVERSION_CODE=%s\nRELEASE_DRAFT=%s\nIOS_MARKETING_VERSION=%s\nIOS_BUILD_VERSION=%s\n' "$1" "$2" "$3" "$4" "$5")
    expected=${expected/VERSION_NAME=v/VERSION_NAME=}
    [[ $actual == "$expected" ]] || { printf 'Wrong version for %s:\n%s\n' "$1" "$actual" >&2; exit 1; }
}
assert_version v1.2.3-alpha7 10203107 true 1.2.3 107.0.1
assert_version v1.2.3-alpha7 10203107 true 1.2.3 107.0.2 2
assert_version v1.2.3-beta0 10203200 true 1.2.3 200.0.1
assert_version v1.2.3-rc99 10203399 true 1.2.3 399.0.1
assert_version v1.2.3 10203400 false 1.2.3 400.0.1
assert_version v99.99.99 999999400 false 99.99.99 400.0.1
assert_version v01.02.03-alpha07 10203107 true 1.2.3 107.0.1
previous=0
for tag in v0.0.99 v0.1.0 v0.99.99 v1.0.0 v1.99.99 v2.0.0; do
    output=$("$script" "$tag")
    code=${output#*VERSION_CODE=}
    code=${code%%$'\n'*}
    (( code > previous )) || { echo "Version order failed: $tag" >&2; exit 1; }
    previous=$code
done
for invalid in v1.2 v1.2.3-alpha100 v100.0.0 v1.2.3-nightly1 v1.2.3+meta 'v1.2.3;true' 'v1.2.3
BAD=1' v99999999999999999999.0.0 v1.2.3-rc v1.2.3-alpha-1; do
    if "$script" "$invalid" >/dev/null 2>&1; then echo "Accepted invalid tag: $invalid" >&2; exit 1; fi
done
for attempt in 0 100 99999999999999999999 '1;true'; do
    if "$script" v1.2.3 "$attempt" >/dev/null 2>&1; then echo "Accepted invalid attempt: $attempt" >&2; exit 1; fi
done
if "$script" v1.2.3 1 extra >/dev/null 2>&1 || "$script" >/dev/null 2>&1; then
    echo 'Accepted invalid argument count' >&2
    exit 1
fi
echo 'Version parser checks passed'
