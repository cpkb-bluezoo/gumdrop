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

mkdir -p lib/netty certs results out/gumdrop out/netty out/loadclient
for m in $NETTY_MODULES; do
    jar="$m-$NETTY_VERSION.jar"
    if [ ! -f "lib/netty/$jar" ]; then
        echo "fetching $jar"
        curl -sSfL -o "lib/netty/$jar" \
            "https://repo1.maven.org/maven2/io/netty/$m/$NETTY_VERSION/$jar"
    fi
done

if [ ! -f certs/benchmark.p12 ]; then
    keytool -genkeypair -alias bench -keyalg RSA -keysize 2048 -validity 3650 \
        -keystore certs/benchmark.p12 -storetype PKCS12 \
        -storepass benchpass -keypass benchpass \
        -dname "CN=localhost, OU=Bench, O=Bench, L=Bench, ST=Bench, C=US" \
        -ext "SAN=dns:localhost,ip:127.0.0.1" > /dev/null 2>&1
fi

GUMDROP_CP="$ROOT/dist/gumdrop.jar:$ROOT/lib/*"
javac -nowarn -cp "$GUMDROP_CP" -d out/gumdrop src/gumdrop/*.java
javac -nowarn -cp "lib/netty/*:$ROOT/lib/jsonparser-1.3.jar" -d out/netty \
    src/netty/NettyBenchServer.java src/gumdrop/BenchJson.java
javac -nowarn -d out/loadclient src/loadclient/*.java
echo "benchmark ready"
