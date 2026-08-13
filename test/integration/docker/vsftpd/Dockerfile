# syntax=docker/dockerfile:1
#
# Minimal vsftpd instance for gumdrop's FTP client integration tests.
#
# One local user (testuser/testpass) chrooted to its home directory,
# read/write, PASV mode with a narrow fixed port range so it can be
# published explicitly, and AUTH TLS/PROT P enabled with a self-signed
# cert -- the exact combination the existing gumdrop-server-backed
# FTPClientIntegrationTest explicitly does not cover (its server config
# is plaintext-only), see that test's class Javadoc.
#
# Build: podman build -t gumdrop-vsftpd-test -f test/integration/docker/vsftpd/Dockerfile test/integration/docker/vsftpd
# Run:   podman run -d --name vsftpd-test --privileged -p 12121:21 -p 21100-21110:21100-21110 \
#            gumdrop-vsftpd-test
# Probe: podman exec vsftpd-test ls -la /home/testuser
#
# Two things had to be worked around to get a stable local vsftpd here,
# both discovered by reproducing minimal cases with each variable
# isolated in turn:
#
# 1. Alpine, not Debian: Debian bookworm ships vsftpd 3.0.3, which
#    segfaults the whole daemon on connection teardown after an AUTH
#    TLS/PROT P session, regardless of container privileges -- a real
#    vsftpd/OpenSSL 3.0 incompatibility in that specific package
#    version, not a config or environment issue. Alpine 3.19 ships
#    vsftpd 3.0.5, which has the upstream fix and doesn't crash.
#
# 2. --privileged is still required even on 3.0.5: without it, vsftpd's
#    per-connection privilege separation (unshare(CLONE_NEWNET) et al,
#    see vsftpd.conf's isolate_network=NO comment) segfaults the daemon
#    on the third connection under podman's default capability set,
#    silently (SIGSEGV, exit 139, no log line anywhere). Fine for a
#    throwaway local test container with nothing else on the network;
#    not a pattern to carry into anything that isn't purely local test
#    infrastructure.
FROM alpine:3.19

RUN apk add --no-cache vsftpd openssl shadow

RUN useradd --create-home --shell /sbin/nologin testuser \
    && echo "testuser:testpass" | chpasswd \
    && chown testuser:testuser /home/testuser \
    && chmod 755 /home/testuser

# vsftpd's secure_chroot_dir (used for its own privilege-separated helper
# process, unrelated to chroot_local_user) must exist and be empty, and
# isn't created by Alpine's package install either.
RUN mkdir -p /var/run/vsftpd/empty

COPY vsftpd.conf /etc/vsftpd.conf

# Self-signed cert for AUTH TLS -- same rationale and SAN requirement as
# the Postfix test container's cert, see its Dockerfile comment.
RUN mkdir -p /etc/vsftpd/tls \
    && openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
       -keyout /etc/vsftpd/tls/key.pem -out /etc/vsftpd/tls/cert.pem \
       -subj "/CN=vsftpd-test" \
       -addext "subjectAltName=DNS:vsftpd-test,DNS:localhost,IP:127.0.0.1"

EXPOSE 21 21100-21110

ENTRYPOINT ["/usr/sbin/vsftpd", "/etc/vsftpd.conf"]
