#!/bin/sh
set -e

mkdir -p /var/run/slapd
chown openldap:openldap /var/run/slapd

# Start slapd backgrounded (with the ldapi:/// local socket, needed for
# the EXTERNAL-auth cn=config changes below) so it can be configured and
# loaded before becoming this container's long-running foreground
# process. slapd daemonizes (double-forks and detaches) by default even
# when launched with "&" from this shell, which made the earlier "wait"
# below return immediately as soon as the parent side of that fork
# exited -- "-d 0" (debug level 0, not "no debugging") happens to also
# suppress the daemonizing behaviour and keep it attached as a normal
# child process.
slapd -d 0 -h "ldap:/// ldapi:///" -u openldap -g openldap &
SLAPD_PID=$!

# Wait for the socket rather than a fixed sleep.
for i in $(seq 1 50); do
    if ldapsearch -Y EXTERNAL -H ldapi:/// -b "" -s base >/dev/null 2>&1; then
        break
    fi
    sleep 0.2
done

ldapmodify -Y EXTERNAL -H ldapi:/// -f /tls.ldif
ldapadd -x -H ldap:/// -D "cn=admin,dc=test,dc=gumdrop,dc=local" -w adminpass -f /data.ldif

wait "$SLAPD_PID"
