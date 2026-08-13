# syntax=docker/dockerfile:1
#
# Minimal Dante SOCKS proxy instance for gumdrop's SOCKS client
# integration tests.
#
# Dante (sockd) is a mature, independent SOCKS4/4a/5 implementation --
# exactly the kind of "wire-compatible but not gumdrop" server the
# Postfix/vsftpd containers already exercise for SMTP/FTP, see their
# Dockerfiles for the shared rationale.
#
# Build: podman build -t gumdrop-socks-test -f test/integration/docker/socks/Dockerfile test/integration/docker/socks
# Run:   podman run -d --name socks-test --network gumdrop-test-net -p 11080:1080 \
#            gumdrop-socks-test
# Probe: podman exec socks-test cat /var/log/sockd.log (if logoutput is a file) or check stderr via podman logs

FROM debian:bookworm-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       dante-server \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /usr/sbin/nologin socksuser \
    && echo "socksuser:sockspass" | chpasswd

COPY sockd.conf /etc/sockd.conf
COPY sockd-auth.conf /etc/sockd-auth.conf
COPY pam.d-sockd /etc/pam.d/sockd
COPY start.sh /start.sh
RUN chmod +x /start.sh

EXPOSE 1080

# SOCKS_MODE=auth selects sockd-auth.conf (username/password required)
# instead of the default sockd.conf (none, effectively) -- see
# sockd-auth.conf's comment for why these need to be separate configs.
ENTRYPOINT ["/start.sh"]
