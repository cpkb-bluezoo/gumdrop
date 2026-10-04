#!/bin/bash
# Prepares the benchmark: fetches Netty into lib/ (not kept in the repo),
# generates a throwaway TLS keystore, and compiles the two servers and the
# load clients. Run "ant jar" in the project root first.
set -eu
cd "$(dirname "$0")"
ROOT=../..
NETTY_VERSION=${NETTY_VERSION:-4.1.121.Final}
NETTY_MODULES="netty-common netty-buffer netty-transport netty-resolver netty-codec
netty-codec-http netty-codec-http2 netty-handler netty-transport-native-unix-common"

if [ ! -f "$ROOT/dist/gumdrop.jar" ]; then
    echo "dist/gumdrop.jar not found: run 'ant jar' in the project root first" >&2
    exit 1
fi

# HTTP/3: Netty's incubator codec and its QUIC transport, which is
# Cloudflare's quiche with BoringSSL, prebuilt in a jar for each platform.
NETTY_HTTP3_VERSION=${NETTY_HTTP3_VERSION:-0.0.29.Final}
NETTY_QUIC_VERSION=${NETTY_QUIC_VERSION:-0.0.71.Final}
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) QUIC_PLATFORM=osx-aarch_64 ;;
    Darwin-x86_64) QUIC_PLATFORM=osx-x86_64 ;;
    Linux-x86_64) QUIC_PLATFORM=linux-x86_64 ;;
    Linux-aarch64) QUIC_PLATFORM=linux-aarch_64 ;;
    *) QUIC_PLATFORM= ;;
esac

mkdir -p lib/netty lib/netty-h3 certs results out/gumdrop out/netty out/netty-h3 out/loadclient out/h3client
for m in $NETTY_MODULES; do
    jar="$m-$NETTY_VERSION.jar"
    if [ ! -f "lib/netty/$jar" ]; then
        echo "fetching $jar"
        curl -sSfL -o "lib/netty/$jar" \
            "https://repo1.maven.org/maven2/io/netty/$m/$NETTY_VERSION/$jar"
    fi
done

INCUBATOR=https://repo1.maven.org/maven2/io/netty/incubator
fetch_h3() {
    if [ ! -f "lib/netty-h3/$2" ]; then
        echo "fetching $2"
        curl -sSfL -o "lib/netty-h3/$2" "$INCUBATOR/$1/$3/$2"
    fi
}
if [ -n "$QUIC_PLATFORM" ]; then
    fetch_h3 netty-incubator-codec-http3 "netty-incubator-codec-http3-$NETTY_HTTP3_VERSION.jar" "$NETTY_HTTP3_VERSION"
    fetch_h3 netty-incubator-codec-classes-quic "netty-incubator-codec-classes-quic-$NETTY_QUIC_VERSION.jar" "$NETTY_QUIC_VERSION"
    fetch_h3 netty-incubator-codec-native-quic "netty-incubator-codec-native-quic-$NETTY_QUIC_VERSION-$QUIC_PLATFORM.jar" "$NETTY_QUIC_VERSION"
else
    echo "no prebuilt quiche for this platform: the Netty HTTP/3 server will not be built" >&2
fi

if [ ! -f certs/benchmark.p12 ]; then
    keytool -genkeypair -alias bench -keyalg RSA -keysize 2048 -validity 3650 \
        -keystore certs/benchmark.p12 -storetype PKCS12 \
        -storepass benchpass -keypass benchpass \
        -dname "CN=localhost, OU=Bench, O=Bench, L=Bench, ST=Bench, C=US" \
        -ext "SAN=dns:localhost,ip:127.0.0.1" > /dev/null 2>&1
fi

# The HTTP/3 servers take the same certificate and key as PEM files.
if [ ! -f certs/benchmark-key.pem ]; then
    openssl pkcs12 -in certs/benchmark.p12 -passin pass:benchpass -nokeys 2> /dev/null \
        | openssl x509 -out certs/benchmark-cert.pem
    openssl pkcs12 -in certs/benchmark.p12 -passin pass:benchpass -nocerts -nodes 2> /dev/null \
        | openssl pkey -out certs/benchmark-key.pem
fi

GUMDROP_CP="$ROOT/dist/gumdrop.jar:$ROOT/lib/*"
javac -nowarn -cp "$GUMDROP_CP" -d out/gumdrop src/gumdrop/*.java
javac -nowarn -cp "lib/netty/*:$ROOT/lib/jsonparser-1.3.jar" -d out/netty \
    src/netty/NettyBenchServer.java src/gumdrop/BenchJson.java
javac -nowarn -d out/loadclient src/loadclient/*.java
javac -nowarn -cp "$GUMDROP_CP:out/loadclient" -d out/h3client src/h3client/*.java
if [ -n "$QUIC_PLATFORM" ]; then
    javac -nowarn -cp "lib/netty/*:lib/netty-h3/*:out/netty" -d out/netty-h3 src/netty/NettyH3BenchServer.java
fi
echo "benchmark ready"
