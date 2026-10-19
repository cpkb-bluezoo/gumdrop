#!/bin/bash
#
# Runs gumdrop's interop endpoint against a peer implementation in podman,
# on the quic-interop-runner's topology (two networks, the endpoints'
# addresses, their routes via the .2 address) but with a plain router in
# place of the ns-3 simulator. That covers the protocol cases (handshake,
# transfer, http3, resumption, zerortt, v2, connectionmigration, ...) in
# minutes; the cases that need the simulator to drop packets (loss,
# corruption, blackhole, amplification limit) it cannot. Rebinding it can:
# rebind-port and rebind-addr shape the link like the runner's simple-p2p
# scenario (10 Mbps, 15 ms, 25 packets of queue) and have the router change
# the client's source port, or address and port, one second in and every five
# seconds after, dropping what is still sent to the old one.
#
#   local.sh [-b] [-v] PEER ROLE TESTCASE
#
#   PEER      ngtcp2, picoquic, quic-go, quiche, xquic, nginx, haproxy, or an image name
#   ROLE      the part gumdrop plays: server or client
#   TESTCASE  handshake, transfer, multiconnect, http3, resumption, zerortt,
#             v2, chacha20, retry, keyupdate, connectionmigration, ipv6,
#             rebind-port, rebind-addr
#   -b        rebuild the gumdrop image first (slow under emulation)
#   -v        print both consoles even when the case passes
#   -t        capture the server's network at the router (tcpdump), to
#             <logs>/cap/server.pcap
#
# On Apple silicon a peer image published for linux/amd64 only (picoquic) runs
# emulated; nothing here depends on timing finer than a second. ngtcp2's
# published image is built with AddressSanitizer and does not start emulated:
# build it natively once with
#   local.sh --build-ngtcp2
# which takes a while and leaves the image ngtcp2-interop-native.
#
# Example: local.sh ngtcp2 server v2

set -u

if [ "${1:-}" = "--build-ngtcp2" ]; then
    SRC=${INTEROP_LOCAL_DIR:-$HOME/.cache/gumdrop-interop-local}/ngtcp2-src
    mkdir -p "$SRC"
    for f in Dockerfile run_endpoint.sh; do
        curl -fsSL "https://raw.githubusercontent.com/ngtcp2/ngtcp2/main/interop/$f" -o "$SRC/$f" || exit 1
    done
    exec podman build --platform "$(podman info --format 'linux/{{.Host.Arch}}')" \
        -f "$SRC/Dockerfile" -t ngtcp2-interop-native "$SRC"
fi

BUILD=0
VERBOSE=0
CAPTURE=0
while getopts bvt opt; do
    case $opt in
        b) BUILD=1 ;;
        v) VERBOSE=1 ;;
        t) CAPTURE=1 ;;
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
    ngtcp2)   if podman image exists ngtcp2-interop-native; then
                  PEER_IMAGE=ngtcp2-interop-native
              else
                  PEER_IMAGE=ghcr.io/ngtcp2/ngtcp2-interop:latest
              fi ;;
    picoquic) PEER_IMAGE=privateoctopus/picoquic:latest ;;
    quic-go)  PEER_IMAGE=martenseemann/quic-go-interop:latest ;;
    quiche)   PEER_IMAGE=cloudflare/quiche-qns:latest ;;
    xquic)    PEER_IMAGE=ghcr.io/alibaba/xquic/xquic-interop:latest ;;
    nginx)    PEER_IMAGE=ghcr.io/nginx/nginx-quic-qns:latest ;;
    haproxy)  PEER_IMAGE=haproxytech/haproxy-qns:latest ;;
    *)        PEER_IMAGE=$PEER ;;
esac
GUMDROP_IMAGE=gumdrop-interop
# an image with no arm64 variant has to be asked for by platform (and runs emulated)
PEER_PLATFORM_ARG=""
case $PEER in
    xquic) PEER_PLATFORM_ARG="--platform linux/amd64" ;;
esac
# gumdrop's own image is built for the host; a peer image runs as published
# (emulated if it is amd64 only)
PLATFORM=${PLATFORM:-$(podman info --format 'linux/{{.Host.Arch}}')}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
# not under build/: ant clean deletes that, and with it the logs of a sweep
WORK=${INTEROP_LOCAL_DIR:-$HOME/.cache/gumdrop-interop-local}/$(echo "$PEER" | tr '/:' '__')-$ROLE-$CASE
TIMEOUT=${TIMEOUT:-180}

if [ "$ROLE" = server ]; then
    SERVER_IMAGE=$GUMDROP_IMAGE
    CLIENT_IMAGE=$PEER_IMAGE
    SERVER_PLATFORM_ARG=""
    CLIENT_PLATFORM_ARG=$PEER_PLATFORM_ARG
else
    SERVER_IMAGE=$PEER_IMAGE
    CLIENT_IMAGE=$GUMDROP_IMAGE
    SERVER_PLATFORM_ARG=$PEER_PLATFORM_ARG
    CLIENT_PLATFORM_ARG=""
fi

# The names the runner gives each side of a case, and what it asks for.
SERVER_CASE=$CASE
CLIENT_CASE=$CASE
HOST=server4
case $CASE in
    keyupdate)           SERVER_CASE=transfer ;;
    connectionmigration) CLIENT_CASE=transfer; HOST=server46 ;;
    ipv6)                HOST=server6 ;;
    rebind-port|rebind-addr) SERVER_CASE=transfer; CLIENT_CASE=transfer ;;
esac
REBIND=""
case $CASE in
    rebind-port) REBIND=port ;;
    rebind-addr) REBIND=addr ;;
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
    rebind-port|rebind-addr) add_file $((10 * MB)) ;;
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

mkdir -p "$WORK/logs/cap"
SIM_CMD='while true; do nc -l -p 57832 >/dev/null 2>&1; done'
if [ -n "$REBIND" ]; then
    # the runner's simple-p2p link, and the client's traffic pinned to one
    # source port so the rebinding below has a mapping to change
    SIM_CMD='apk add --no-cache iptables conntrack-tools iproute2 iproute2-tc >/dev/null 2>&1;
        for i in eth0 eth1; do tc qdisc add dev $i root netem delay 15ms rate 10mbit limit 25; done;
        iptables -t nat -A POSTROUTING -s 193.167.0.100 -p udp -j SNAT --to-source 193.167.0.100:40000;
        touch /cap/ready; '"$SIM_CMD"
fi
if [ $CAPTURE -eq 1 ]; then
    SIM_CMD='apk add --no-cache tcpdump >/dev/null 2>&1; tcpdump -i eth1 -U -w /cap/server.pcap udp >/cap/tcpdump.out 2>&1 & '"$SIM_CMD"
fi
podman run -d --name sim --platform $PLATFORM --cap-add NET_ADMIN --cap-add NET_RAW \
    --sysctl net.ipv4.ip_forward=1 --sysctl net.ipv6.conf.all.forwarding=1 \
    --network leftnet:ip=193.167.0.2,ip=fd00:cafe:cafe:0::2 \
    --network rightnet:ip=193.167.100.2,ip=fd00:cafe:cafe:100::2 \
    -v "$WORK/logs/cap:/cap" \
    docker.io/library/alpine:latest \
    sh -c "$SIM_CMD" >/dev/null || exit 1
[ $CAPTURE -eq 1 ] && sleep 6
if [ -n "$REBIND" ]; then
    for _ in $(seq 1 60); do
        [ -f "$WORK/logs/cap/ready" ] && break
        sleep 1
    done
    [ -f "$WORK/logs/cap/ready" ] || { echo "the router did not come up (NAT or traffic control unavailable?)"; cleanup; exit 1; }
fi

common_env=(-e SSLKEYLOGFILE=/logs/keys.log -e QLOGDIR=/logs/qlog/ -e INTEROP_DEBUG=1 -e "JAVA_OPTS=${JAVA_OPTS:-}")

podman run -d --name server $SERVER_PLATFORM_ARG --cap-add NET_ADMIN \
    --network rightnet:ip=193.167.100.100,ip=fd00:cafe:cafe:100::100 \
    --add-host server4:193.167.100.100 --add-host server6:fd00:cafe:cafe:100::100 \
    -v "$WORK/www:/www:ro" -v "$WORK/certs:/certs:ro" -v "$WORK/logs/server:/logs" \
    "${common_env[@]}" -e ROLE=server -e TESTCASE=$SERVER_CASE \
    $SERVER_IMAGE >/dev/null || { cleanup; exit 1; }

# the client starts when the server's console shows it is up, or after a few seconds
sleep 8

podman run -d --name client $CLIENT_PLATFORM_ARG --cap-add NET_ADMIN \
    --network leftnet:ip=193.167.0.100,ip=fd00:cafe:cafe:0::100 \
    --add-host server4:193.167.100.100 --add-host server6:fd00:cafe:cafe:100::100 \
    --add-host server46:193.167.100.100 --add-host server46:fd00:cafe:cafe:100::100 \
    -v "$WORK/www:/www:ro" -v "$WORK/certs:/certs:ro" \
    -v "$WORK/downloads:/downloads" -v "$WORK/logs/client:/logs" \
    "${common_env[@]}" -e ROLE=client -e TESTCASE=$CLIENT_CASE -e "REQUESTS=$REQUESTS" \
    $CLIENT_IMAGE >/dev/null || { cleanup; exit 1; }

if [ -n "$REBIND" ]; then
    # first rebinding one second in, then every five seconds; flushing the
    # tracked flow makes the client's next packet take the new mapping, and
    # whatever the server still sends to the old one reaches nobody
    if [ "$REBIND" = port ]; then
        TO='193.167.0.100:$((40000 + n))'
    else
        TO='193.167.0.$((100 + n)):$((40000 + n))'
    fi
    podman exec -d sim sh -c 'n=0; sleep 1; while true; do n=$((n + 1));
        iptables -t nat -R POSTROUTING 1 -s 193.167.0.100 -p udp -j SNAT --to-source '"$TO"';
        conntrack -F >/dev/null 2>&1; sleep 5; done'
fi

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
