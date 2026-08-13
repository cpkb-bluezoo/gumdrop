# syntax=docker/dockerfile:1
#
# Minimal OpenLDAP (slapd) instance for gumdrop's LDAP client
# integration tests.
#
# OpenLDAP is a mature, independent LDAPv3 implementation -- the same
# rationale as the Postfix/vsftpd/Dante containers: a same-lineage fake
# server can't disagree with the client on wire-level details.
#
# Base DN dc=test,dc=gumdrop,dc=local, admin cn=admin,dc=test,dc=gumdrop,dc=local
# (password: adminpass), preloaded with one ou=people entry and one test
# user (uid=jdoe, password: jdoepass) via data.ldif. StartTLS is
# available (not required) with a self-signed cert.
#
# Build: podman build -t gumdrop-ldap-test -f test/integration/docker/ldap/Dockerfile test/integration/docker/ldap
# Run:   podman run -d --name ldap-test -p 13389:389 gumdrop-ldap-test
# Probe: podman exec ldap-test ldapsearch -x -H ldap://localhost -b dc=test,dc=gumdrop,dc=local

FROM debian:bookworm-slim

# slapd's postinst normally prompts interactively; debconf-set-selections
# preseeds the answers (domain/org/admin password/backend) so
# dpkg-reconfigure can run non-interactively and produce a slapd.d config
# with the base DN and admin account this test suite expects.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       slapd ldap-utils openssl \
    && rm -rf /var/lib/apt/lists/*

RUN debconf-set-selections <<'EOF'
slapd slapd/internal/generated_adminpw password adminpass
slapd slapd/internal/adminpw password adminpass
slapd slapd/password1 password adminpass
slapd slapd/password2 password adminpass
slapd slapd/domain string test.gumdrop.local
slapd shared/organization string gumdrop
slapd slapd/backend select MDB
slapd slapd/purge_database boolean true
slapd slapd/move_old_database boolean true
slapd slapd/allow_ldap_v2 boolean false
slapd slapd/no_configuration boolean false
EOF

RUN DEBIAN_FRONTEND=noninteractive dpkg-reconfigure slapd

# Self-signed cert for StartTLS -- same rationale and SAN requirement as
# the Postfix/vsftpd test containers' certs.
RUN mkdir -p /etc/ldap/tls \
    && openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
       -keyout /etc/ldap/tls/key.pem -out /etc/ldap/tls/cert.pem \
       -subj "/CN=ldap-test" \
       -addext "subjectAltName=DNS:ldap-test,DNS:localhost,IP:127.0.0.1" \
    && chown openldap:openldap /etc/ldap/tls/key.pem /etc/ldap/tls/cert.pem \
    && chmod 640 /etc/ldap/tls/key.pem

COPY tls.ldif /tls.ldif
COPY data.ldif /data.ldif
COPY start.sh /start.sh
RUN chmod +x /start.sh

EXPOSE 389

ENTRYPOINT ["/start.sh"]
