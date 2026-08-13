# syntax=docker/dockerfile:1
#
# AdGuard Home instance for gumdrop's DNS client integration tests.
#
# AdGuard Home is a real, independent, free-software (GPL-3.0) DNS
# resolver -- same rationale as every other container in this
# directory. Chosen specifically because it supports every wire format
# gumdrop's DNS client does (plain UDP/TCP, DoT, DoH, DoQ) from one
# config, so the full transport matrix can be tested against a single
# locally-controlled instance instead of live public resolvers (no
# external-network flakiness/rate-limiting, consistent with every
# other test in this suite).
#
# AdGuardHome.yaml here is a real generated config (via the install API
# during development of this container, see that file's header
# comment) with tls enabled and a static rewrite added so tests can
# assert an exact answer without depending on real internet name
# resolution being reachable.
#
# Build: podman build -t gumdrop-dns-test -f test/integration/docker/dns/Dockerfile test/integration/docker/dns
# Run:   podman run -d --name dns-test \
#            -p 15353:53/udp -p 15353:53/tcp \
#            -p 18443:443/tcp -p 18853:853/tcp -p 18853:853/udp \
#            gumdrop-dns-test
# Probe: podman exec dns-test sh -c "wget -qO- http://127.0.0.1:80/control/status"

FROM adguard/adguardhome

USER root

RUN apk add --no-cache openssl \
    && mkdir -p /opt/adguardhome/certs \
    && openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
       -keyout /opt/adguardhome/certs/key.pem -out /opt/adguardhome/certs/cert.pem \
       -subj "/CN=dns-test" \
       -addext "subjectAltName=DNS:dns-test,DNS:localhost,IP:127.0.0.1"

COPY AdGuardHome.yaml /opt/adguardhome/conf/AdGuardHome.yaml

EXPOSE 53/udp 53/tcp 443/tcp 853/tcp 853/udp

ENTRYPOINT ["/opt/adguardhome/AdGuardHome"]
CMD ["--no-check-update", "-c", "/opt/adguardhome/conf/AdGuardHome.yaml", "-w", "/opt/adguardhome/work"]
