# syntax=docker/dockerfile:1
#
# Minimal Redis instance for gumdrop's Redis client integration tests.
#
# The official Redis image is a real, independent RESP implementation --
# same rationale as the Postfix/vsftpd/Dante/OpenLDAP containers.
#
# requirepass "testpass"; both the plaintext port (6379) and a TLS port
# (6380, self-signed cert, one-way -- no client cert required) are
# listened on simultaneously so both paths can be exercised against the
# same instance.
#
# Build: podman build -t gumdrop-redis-test -f test/integration/docker/redis/Dockerfile test/integration/docker/redis
# Run:   podman run -d --name redis-test -p 16379:6379 -p 16380:6380 gumdrop-redis-test
# Probe: podman exec redis-test redis-cli -a testpass ping

FROM docker.io/library/redis:7

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssl \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /tls \
    && openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
       -keyout /tls/key.pem -out /tls/cert.pem \
       -subj "/CN=redis-test" \
       -addext "subjectAltName=DNS:redis-test,DNS:localhost,IP:127.0.0.1" \
    && chown redis:redis /tls/key.pem /tls/cert.pem \
    && chmod 600 /tls/key.pem

COPY redis.conf /usr/local/etc/redis/redis.conf

EXPOSE 6379 6380

CMD ["redis-server", "/usr/local/etc/redis/redis.conf"]
