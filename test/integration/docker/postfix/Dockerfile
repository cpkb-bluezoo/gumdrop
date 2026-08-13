# syntax=docker/dockerfile:1
#
# Minimal Postfix instance for gumdrop's SMTP client integration tests.
#
# Configured for local delivery only (no relaying anywhere): everything
# accepted on port 25 lands in /var/mail/testuser as a plain mbox file,
# which the test reads directly off a mounted volume rather than via a
# retrieval protocol. TLS material is generated at build time so the
# client integration test can exercise STARTTLS against a real server.
#
# Build: podman build -t gumdrop-postfix-test -f test/integration/docker/postfix/Dockerfile test/integration/docker/postfix
# Run:   podman run -d --name postfix-test -p 2525:25 \
#            -v gumdrop-postfix-spool:/var/mail gumdrop-postfix-test
# Probe: podman exec postfix-test cat /var/mail/testuser

FROM debian:bookworm-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       postfix openssl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /usr/sbin/nologin testuser

COPY main.cf /etc/postfix/main.cf
COPY master.cf /etc/postfix/master.cf
COPY start.sh /start.sh
RUN chmod +x /start.sh

# Self-signed cert for STARTTLS -- verification is not the point of these
# tests (see RabbitMQTlsIntegrationTest for a real-CA-verified example);
# this exercises the client's handshake and chunked-body-over-TLS path.
# A SAN is required, not just a CN: Java's default endpoint identification
# (HostnameChecker) rejects a cert with no subjectAltName outright, even
# for an otherwise-trusted pinned leaf cert.
RUN mkdir -p /etc/postfix/tls \
    && openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
       -keyout /etc/postfix/tls/key.pem -out /etc/postfix/tls/cert.pem \
       -subj "/CN=postfix-test" \
       -addext "subjectAltName=DNS:postfix-test,DNS:localhost,IP:127.0.0.1"

EXPOSE 25

ENTRYPOINT ["/start.sh"]
