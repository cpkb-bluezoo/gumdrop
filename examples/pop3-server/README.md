# POP3 Server Example

Demonstrates Gumdrop 3 composition for POP3: `Gumdrop.boot()`,
`Pop3Server.compose()`, mbox mailboxes, and a small in-memory realm.

## Mailbox data

Uses the checked-in **mbox fixture** at `test/integration/mailbox/mbox`
(same tree as POP3 integration tests). On startup the example **copies** that
directory to a temporary folder so the git tree is not modified. Optional first
argument: path to another fixture root (for example `test/integration/mailbox/mbox`).

## Features

- Cleartext POP3 on port **1110** (no root privileges)
- Optional POP3S on port **1995** when PEM certificate and key paths are passed
- APOP and UTF-8 enabled; 2s login delay after failed auth

## Build

From the gumdrop root:

```bash
ant examples-compile
```

## Run

```bash
java -cp 'build/examples:build/*:lib/*' POP3Example
java -cp 'build/examples:build/*:lib/*' POP3Example etc/tls/cert.pem etc/tls/key.pem
```

(`ant tls-certs` creates the PEM files under `etc/tls/`.)

## Test user

Matches the integration mbox fixture (`editor/`):

| User    | Password |
|---------|----------|
| editor  | editor   |

## Telnet (port 1110)

```
telnet localhost 1110
USER editor
PASS editor
STAT
LIST
RETR 1
QUIT
```

## Fixture layout

```
test/integration/mailbox/mbox/
  editor/
    INBOX.mbox
    .subscriptions
```

See [BUILDING.md](../../BUILDING.md) and [web/configuration.html](../../web/configuration.html) for production TLS and realm options.
