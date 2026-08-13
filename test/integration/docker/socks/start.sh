#!/bin/sh
set -e

CONF=/etc/sockd.conf
if [ "$SOCKS_MODE" = "auth" ]; then
    CONF=/etc/sockd-auth.conf
fi

# -N 1 (a single pre-forked worker) was tried first and caused
# intermittent ~10s test timeouts: with only one worker, a new
# connection has to wait for the previous one's teardown to fully
# release it, and that release isn't always fast enough to beat the
# next test's connect attempt. A handful of workers removes the
# artificial bottleneck.
exec /usr/sbin/danted -f "$CONF" -N 4
