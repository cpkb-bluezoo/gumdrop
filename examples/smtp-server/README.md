# SMTP Server Example

Demonstrates Gumdrop 3 composition for SMTP: `Gumdrop.boot()`,
`SmtpServer.compose()`, a staged handler implementing every protocol stage, and
`AuthPipeline` for SPF, DKIM and DMARC.

## Features

- Accepts mail for `example.test` and refuses relaying (`551 5.7.1`)
- Runs SPF, DKIM and DMARC checks and holds the final reply until the DMARC
  verdict is in; a DMARC `reject` policy refuses the message
- Spools each accepted message to a `.eml` file
- Cleartext SMTP on port **2525** (no root privileges)

## Build

```bash
ant jar examples-compile
```

## Run

The first argument is the spool directory; the default is a fresh temporary one.

```bash
java -cp 'build/examples:dist/*:lib/*' SmtpExample /tmp/spool
```

## Try it

```bash
python3 -c "
import smtplib
s = smtplib.SMTP('localhost', 2525)
s.sendmail('a@sender.example', ['bob@example.test'],
           'From: a@sender.example\r\nTo: bob@example.test\r\nSubject: hi\r\n\r\nhello\r\n')
s.quit()"
```
