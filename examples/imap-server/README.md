# IMAP Server Example

Demonstrates Gumdrop 3 composition for IMAP: `Gumdrop.boot()`,
`ImapServer.compose()`, mbox mailboxes, a small in-memory realm, and a
`DefaultIMAPHandler` subclass that adds site policy.

## Policy

The handler logs every `SELECT` and refuses to `DELETE` INBOX
(`NO INBOX cannot be deleted`). Everything else is accepted by the default
handler.

## Mailbox data

Uses the checked-in mbox fixture at `test/integration/mailbox/mbox`. On startup
the example copies it to a temporary directory so the git tree is not modified.

## Build

```bash
ant jar examples-compile
```

## Run

```bash
java -cp 'build/examples:dist/*:lib/*' ImapExample
java -cp 'build/examples:dist/*:lib/*' ImapExample etc/tls/cert.pem etc/tls/key.pem
```

Cleartext IMAP listens on port **1143**, with LOGIN allowed for testing. With a
certificate and key (`ant tls-certs` creates them under `etc/tls/`) IMAPS also
listens on port **1993**.

## Test user

| User   | Password |
|--------|----------|
| editor | editor   |

## Try it

```python
import imaplib
c = imaplib.IMAP4('localhost', 1143)
c.login('editor', 'editor')
c.select('INBOX')
c.logout()
```
