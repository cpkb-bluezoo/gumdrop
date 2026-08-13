#!/bin/sh
set -e

# postfix wants its spool/queue directories to exist with the right
# ownership before it will start; a fresh named volume mounted over
# /var/mail arrives empty, so make sure the delivery target exists too.
touch /var/mail/testuser
chown testuser:mail /var/mail/testuser
chmod 660 /var/mail/testuser

postfix start-fg
