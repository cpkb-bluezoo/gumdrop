#!/bin/sh
#
# Create a locally trusted TLS keystore for Gumdrop container smoke tests
# (HTTPS + HTTP/3 on port 8443). Requires mkcert and openssl on PATH.
#
# Installs mkcert's CA into the OS trust store (mkcert -install), generates
# localhost certificates under etc/ (gitignored), builds etc/keystore.p12, and
# copies the keystore into dist/container-home/conf/ when that tree exists.

set -e

script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
etc_dir=$repo_root/etc
cert_pem=$etc_dir/localhost.pem
key_pem=$etc_dir/localhost-key.pem
keystore=$etc_dir/keystore.p12
store_pass=${GUMDROP_DEV_TLS_STORE_PASS:-changeit}

if ! command -v mkcert >/dev/null 2>&1; then
	echo "dev-tls-setup: mkcert not found." >&2
	echo "  macOS:  brew install mkcert && brew install nss   # Firefox trust on macOS" >&2
	echo "  Linux:  see https://github.com/FiloSottile/mkcert#installation" >&2
	exit 1
fi
if ! command -v openssl >/dev/null 2>&1; then
	echo "dev-tls-setup: openssl not found (needed to build keystore.p12)." >&2
	exit 1
fi

mkdir -p "$etc_dir"

echo "dev-tls-setup: installing local CA into system trust store (may prompt for sudo)..."
mkcert -install

echo "dev-tls-setup: generating localhost certificate in etc/..."
mkcert -cert-file "$cert_pem" -key-file "$key_pem" localhost ::1

echo "dev-tls-setup: building PKCS#12 keystore ($keystore)..."
openssl pkcs12 -export \
	-in "$cert_pem" \
	-inkey "$key_pem" \
	-out "$keystore" \
	-passout "pass:$store_pass" \
	-name gumdrop

container_conf=$repo_root/dist/container-home/conf
if [ -d "$container_conf" ]; then
	cp "$keystore" "$container_conf/keystore.p12"
	echo "dev-tls-setup: copied keystore to dist/container-home/conf/"
fi

echo "dev-tls-setup: done."
echo "  Run: ant assemble-container  (if you have not already)"
echo "  Then: ./start-tls"
echo "  Try:  curl -sS https://localhost:8443/"
echo "        curl -sS 'https://[::1]:8443/'   (HTTP/2+TLS; HTTP/3 uses the same port via QUIC)"
