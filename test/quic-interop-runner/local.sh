#!/bin/bash
#
# Runs gumdrop's interop endpoint against a peer implementation in podman,
# on the quic-interop-runner's topology (two networks, the endpoints'
# addresses, their routes via the .2 address) but with a plain router in
# place of the ns-3 simulator. That covers the protocol cases (handshake,
# transfer, http3, resumption, zerortt, v2, connectionmigration, ...) in
# minutes; the cases that need the simulator to drop or rewrite packets
# (loss, corruption, blackhole, amplification limit, rebinding) it cannot.
#
#   local.sh [-b] [-v] PEER ROLE TESTCASE
#
#   PEER      ngtcp2, picoquic, quic-go, or an image name
#   ROLE      the part gumdrop plays: server or client
#   TESTCASE  handshake, transfer, multiconnect, http3, resumption, zerortt,
#             v2, chacha20, retry, keyupdate, connectionmigration, ipv6
#   -b        rebuild the gumdrop image first (slow under emulation)
#   -v        print both consoles even when the case passes
#
# The peer images are linux/amd64, so on Apple silicon they run emulated;
# nothing here depends on timing finer than a second.
#
# Example: local.sh ngtcp2 server v2

set -u

BUILD=0
VERBOSE=0
while getopts bv opt; do
    case $opt in
        b) BUILD=1 ;;
        v) VERBOSE=1 ;;
        *) sed -n '2,/^$/p' "$0"; exit 2 ;;
    esac
done
shift $((OPTIND - 1))
if [ $# -ne 3 ]; then
    sed -n '2,/^set -u/p' "$0" | sed '$d'
    exit 2
fi
PEER=$1
ROLE=$2
CASE=$3

case $PEER in
    ngtcp2)   PEER_IMAGE=ghcr.io/ngtcp2/ngtcp2-interop:latest ;;
    picoquic) PEER_IMAGE=privateoctopus/picoquic:latest ;;
    quic-go)  PEER_IMAGE=martenseemann/quic-go-interop:latest ;;
    *)        PEER_IMAGE=$PEER ;;
esac
GUMDROP_IMAGE=gumdrop-interop
PLATFORM=linux/amd64
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$ROOT/build/interop-local/$PEER-$ROLE-$CASE
TIMEOUT=${TIMEOUT:-180}

if [ "$ROLE" = server ]; then
    SERVER_IMAGE=$GUMDROP_IMAGE
    CLIENT_IMAGE=$PEER_IMAGE
else
    SERVER_IMAGE=$PEER_IMAGE
    CLIENT_IMAGE=$GUMDROP_IMAGE
fi

# The names the runner gives each side of a case, and what it asks for.
SERVER_CASE=$CASE
CLIENT_CASE=$CASE
HOST=server4
case $CASE in
    keyupdate)           SERVER_CASE=transfer ;;
    connectionmigration) CLIENT_CASE=transfer; HOST=server46 ;;
    ipv6)                HOST=server6 ;;
esac

if [ $BUILD -eq 1 ] || ! podman image exists $GUMDROP_IMAGE; then
    echo "building $GUMDROP_IMAGE ($PLATFORM)"
    podman build --platform $PLATFORM -f "$ROOT/test/quic-interop-runner/Dockerfile" \
        -t $GUMDROP_IMAGE "$ROOT" || exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK"/www "$WORK"/downloads "$WORK"/certs "$WORK"/logs/server "$WORK"/logs/client

# ---- files the client asks for -------------------------------------------
REQUESTS=""
add_file() { # size_bytes [name]
    local name=${2:-$(LC_ALL=C tr -dc 'a-z' </dev/urandom | head -c 12)}
    head -c "$1" /dev/urandom >"$WORK/www/$name"
    REQUESTS="$REQUESTS https://$HOST:443/$name"
}
KB=1024
MB=$((1024 * 1024))
case $CASE in
    handshake|v2|retry|ipv6) add_file $KB ;;
    chacha20)                add_file $((3 * MB)) ;;
    transfer|connectionmigration) add_file $((2 * MB)); add_file $((3 * MB)); add_file $((5 * MB)) ;;
    keyupdate)               add_file $((3 * MB)) ;;
    multiconnect)            for _ in 1 2 3 4 5; do add_file $KB; done ;;
    http3)                   add_file $((5 * KB)); add_file $((10 * KB)); add_file $((500 * KB)) ;;
    resumption)              add_file $((5 * KB)); add_file $((10 * KB)) ;;
    zerortt)                 for _ in $(seq 1 40); do
                                 add_file 32 "$(LC_ALL=C tr -dc 'a-z' </dev/urandom | head -c 250)"
                             done ;;
    *) echo "unknown test case: $CASE"; exit 2 ;;
esac
REQUESTS=${REQUESTS# }

# ---- certificates (the runner's chain of length one) ---------------------
(
    cd "$WORK/certs" || exit 1
    printf '[v3_ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\n' >ca.cnf
    openssl ecparam -name prime256v1 -genkey -out ca.key 2>/dev/null
    openssl req -x509 -sha256 -nodes -days 10 -key ca.key -out ca.pem \
        -subj "/O=interop runner Root Certificate Authority/" -config ca.cnf -extensions v3_ca 2>/dev/null
    openssl ecparam -name prime256v1 -genkey -out priv.key 2>/dev/null
    openssl req -new -key priv.key -nodes -subj "/O=interop runner leaf/" -out leaf.csr 2>/dev/null
    printf 'subjectAltName=DNS:server,DNS:server4,DNS:server6,DNS:server46\n' >leaf.ext
    openssl x509 -req -sha256 -days 10 -in leaf.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
        -extfile leaf.ext -out cert.pem 2>/dev/null
    rm -f ca.key ca.cnf leaf.csr leaf.ext ca.srl
) || { echo "certificate generation failed"; exit 1; }
chmod -R a+rX "$WORK"

# ---- network: two LANs and a router at the simulator's addresses ----------
cleanup() {
    podman rm -f sim server client >/dev/null 2>&1
}
cleanup
podman network exists leftnet || podman network create --ipv6 \
    --subnet 193.167.0.0/24 --subnet fd00:cafe:cafe:0::/64 leftnet >/dev/null
podman network exists rightnet || podman network create --ipv6 \
    --subnet 193.167.100.0/24 --subnet fd00:cafe:cafe:100::/64 rightnet >/dev/null

podman run -d --name sim --cap-add NET_ADMIN \
    --sysctl net.ipv4.ip_forward=1 --sysctl net.ipv6.conf.all.forwarding=1 \
    --network leftnet:ip=193.167.0.2,ip=fd00:cafe:cafe:0::2 \
    --network rightnet:ip=193.167.100.2,ip=fd00:cafe:cafe:100::2 \
    docker.io/library/alpine:latest \
    sh -c 'while true; do nc -l -p 57832 >/dev/null 2>&1; done' >/dev/null || exit 1

common_env=(-e SSLKEYLOGFILE=/logs/keys.log -e QLOGDIR=/logs/qlog/ -e INTEROP_DEBUG=1)

podman run -d --name server --platform $PLATFORM --cap-add NET_ADMIN \
    --network rightnet:ip=193.167.100.100,ip=fd00:cafe:cafe:100::100 \
    --add-host server4:193.167.100.100 --add-host server6:fd00:cafe:cafe:100::100 \
    -v "$WORK/www:/www:ro" -v "$WORK/certs:/certs:ro" -v "$WORK/logs/server:/logs" \
    "${common_env[@]}" -e ROLE=server -e TESTCASE=$SERVER_CASE \
    $SERVER_IMAGE >/dev/null || { cleanup; exit 1; }

# the client starts when the server's console shows it is up, or after a few seconds
sleep 8

podman run -d --name client --platform $PLATFORM --cap-add NET_ADMIN \
    --network leftnet:ip=193.167.0.100,ip=fd00:cafe:cafe:0::100 \
    --add-host server4:193.167.100.100 --add-host server6:fd00:cafe:cafe:100::100 \
    --add-host server46:193.167.100.100 --add-host server46:fd00:cafe:cafe:100::100 \
    -v "$WORK/www:/www:ro" -v "$WORK/certs:/certs:ro" \
    -v "$WORK/downloads:/downloads" -v "$WORK/logs/client:/logs" \
    "${common_env[@]}" -e ROLE=client -e TESTCASE=$CLIENT_CASE -e "REQUESTS=$REQUESTS" \
    $CLIENT_IMAGE >/dev/null || { cleanup; exit 1; }

# ---- wait for the client, then judge -------------------------------------
deadline=$(( $(date +%s) + TIMEOUT ))
while [ "$(podman inspect -f '{{.State.Status}}' client 2>/dev/null)" = running ]; do
    if [ "$(date +%s)" -ge $deadline ]; then
        echo "client still running after ${TIMEOUT}s"
        break
    fi
    sleep 2
done
CLIENT_EXIT=$(podman inspect -f '{{.State.ExitCode}}' client 2>/dev/null)
podman logs server >"$WORK/server.console" 2>&1
podman logs client >"$WORK/client.console" 2>&1
cleanup

missing=0
bad=0
for url in $REQUESTS; do
    name=${url##*/}
    if [ ! -f "$WORK/downloads/$name" ]; then
        missing=$((missing + 1))
    elif ! cmp -s "$WORK/www/$name" "$WORK/downloads/$name"; then
        bad=$((bad + 1))
    fi
done
total=$(echo $REQUESTS | wc -w | tr -d ' ')

verdict=PASS
[ "$CLIENT_EXIT" = 0 ] || verdict=FAIL
[ $missing -eq 0 ] && [ $bad -eq 0 ] || verdict=FAIL
echo "$verdict: $PEER, gumdrop as $ROLE, $CASE (client exit $CLIENT_EXIT, $total files, $missing missing, $bad differing)"
if [ $verdict = FAIL ] || [ $VERBOSE -eq 1 ]; then
    echo "--- server console (tail) ---"; tail -40 "$WORK/server.console"
    echo "--- client console (tail) ---"; tail -40 "$WORK/client.console"
fi
echo "logs: $WORK"
[ $verdict = PASS ]
