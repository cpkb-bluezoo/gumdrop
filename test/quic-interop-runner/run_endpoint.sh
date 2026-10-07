#!/bin/bash
#
# Entrypoint of gumdrop's quic-interop-runner endpoint image. The runner
# drives the container entirely through the environment: ROLE (client or
# server), TESTCASE, REQUESTS (client: the URLs to fetch), and the mounts
# /www (server: files to serve), /downloads (client: where to store them),
# /certs (priv.key, cert.pem, ca.pem) and /logs.
#
# Unknown test cases exit 127, which the runner reads as "unsupported";
# that decision is made in Java so there is a single list of cases.

set -e

# Routes and checksum offload for the ns-3 simulator network.
/setup.sh

echo "gumdrop interop endpoint: ROLE=$ROLE TESTCASE=$TESTCASE"
echo "$(java -version 2>&1 | head -n 1)"

LOGGING=/gumdrop/logging.properties
if [ -n "$INTEROP_DEBUG" ]; then
    LOGGING=/gumdrop/logging-debug.properties
fi
JVM_OPTS="-XX:MaxRAMPercentage=50 -Djava.util.logging.config.file=$LOGGING $JAVA_OPTS"
CLASSPATH="/gumdrop/classes:/gumdrop/lib/*"

# The connectionmigration server case advertises this host's own addresses
# on another port (setup.sh reads them the same way).
export INTEROP_PREFERRED_IPV4="${INTEROP_PREFERRED_IPV4:-$(hostname -I | cut -f1 -d" ")}"
export INTEROP_PREFERRED_IPV6="${INTEROP_PREFERRED_IPV6:-$(hostname -I | cut -f2 -d" ")}"

if [ "$ROLE" = "client" ]; then
    # The simulator opens this port once its network is up.
    /wait-for-it.sh sim:57832 -s -t 30
    echo "REQUESTS=$REQUESTS"
    exec java $JVM_OPTS -cp "$CLASSPATH" org.bluezoo.gumdrop.quic.interop.InteropClient
else
    exec java $JVM_OPTS -cp "$CLASSPATH" org.bluezoo.gumdrop.quic.interop.InteropServer
fi
