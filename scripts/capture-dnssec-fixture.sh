#!/usr/bin/env bash
# Records the DNSSEC data needed to validate one answer, for use as a unit
# test fixture under test/junit/resources/dnssec/.
#
# Usage: scripts/capture-dnssec-fixture.sh NAME TYPE ZONE... > fixture.zone
#
#   NAME   the name the answer is for, e.g. www.ietf.org
#   TYPE   the record type, e.g. A
#   ZONE   each zone on the path from the root to the zone that signs the
#          answer, root first: for www.ietf.org A use  . org ietf.org
#
# For every ZONE the DNSKEY RRset (with its RRSIGs) and, except for the root,
# the DS RRset (with its RRSIGs) are recorded, plus the answer itself. The
# output is dig's own zone-file text, each response preceded by a
# ";; query NAME TYPE" line, and begins with ";; captured EPOCH" so a test
# can validate as of the time the signatures were fresh (signatures expire
# within days or weeks, so a fixture is only valid at its capture time).
#
# Needs dig and network access; it is a maintainer tool, never run by CI.
# RESOLVER defaults to 9.9.9.9. It must return RRSIGs (any validating
# resolver does); queries use TCP so large DNSKEY sets are not truncated.

set -eu

if [ $# -lt 3 ]; then
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
fi

RESOLVER="${RESOLVER:-9.9.9.9}"
NAME="$1"
TYPE="$2"
shift 2

# One response, retried a few times: dig exits 0 even when the connection
# failed, so look for its error text instead, and never record a response
# that is incomplete.
query() {
    attempt=1
    while :; do
        out=$(dig "@$RESOLVER" +tcp +noall +answer +dnssec +time=10 +tries=1 "$1" "$2" 2>&1) || out=";; dig failed"
        if ! printf '%s\n' "$out" | grep -q '^;;'; then
            break
        fi
        if [ "$attempt" -ge 5 ]; then
            echo "capture-dnssec-fixture: no clean response for $1 $2: $out" >&2
            exit 1
        fi
        attempt=$((attempt + 1))
        sleep 1
    done
    echo ";; query $1 $2"
    if [ -n "$out" ]; then
        printf '%s\n' "$out"
    fi
}

echo ";; captured $(date -u +%s)"
for zone in "$@"; do
    case "$zone" in
        .) query . DNSKEY ;;
        *) query "$zone" DNSKEY; query "$zone" DS ;;
    esac
done
query "$NAME" "$TYPE"
