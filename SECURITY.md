# Security Policy

## Supported Versions

Gumdrop does not yet maintain parallel maintenance branches. Security fixes
are made against the latest release and the `main` branch; older releases
are not backported.

| Version | Supported          |
| ------- | ------------------ |
| 2.1.x   | :white_check_mark: |
| main (unreleased) | :white_check_mark: |
| < 2.1   | :x:                |

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

The preferred way to report a vulnerability is through GitHub's private
vulnerability reporting:

1. Go to the [Security tab](https://github.com/cpkb-bluezoo/gumdrop/security) of this repository.
2. Click **Report a vulnerability**.
3. Provide as much detail as possible: affected version(s), which
   protocol/subsystem is involved, a minimal reproduction (a request,
   configuration, or client script that triggers the issue), and the
   observed impact.

If you are unable to use GitHub's private reporting, you may instead email
the maintainer directly at **dog@gnu.org**. Please include "Gumdrop
security" in the subject line, and PGP-encrypt the report if it contains
proof-of-concept exploit code.

You should expect an initial response within a few days acknowledging
receipt. Gumdrop is currently maintained by a single volunteer maintainer,
so please be patient — a fix timeline will be communicated once the report
has been triaged, and credit will be given in the release notes unless you
ask to remain anonymous. Coordinated disclosure is appreciated: please give
the maintainer a reasonable window to publish a fix before disclosing
publicly.

## Scope

Gumdrop is a multipurpose server framework implementing HTTP, SMTP, POP3,
IMAP, FTP, DNS, MQTT, SOCKS, WebDAV, and a servlet container, plus a web
based manager application, so reports of particular interest include (but
are not limited to):

- Authentication or authorization bypass in any protocol handler or in the
  manager web application (e.g. realm/credential handling, servlet
  `<auth-constraint>` enforcement, SASL/SCRAM mechanisms)
- Request smuggling, parser desync, or other protocol-framing issues in any
  of the protocol listeners
- Path traversal or unintended file access via the servlet container,
  WebDAV, or FTP handlers
- Denial of service: crashes, hangs, unbounded memory growth, or resource
  exhaustion from malformed or adversarial network input, including
  connection/request floods that bypass intended limits
- TLS/certificate handling issues (validation, downgrade, cipher selection)
- Memory-safety issues in the optional native QUIC support (the JNI
  boundary in `libgumdrop`/`libquiche`), or in the bundled Public Suffix
  List / cookie domain handling
- Any issue allowing arbitrary code execution

Gumdrop depends on [gonzalez](https://github.com/cpkb-bluezoo/gonzalez) and
[jsonparser](https://github.com/cpkb-bluezoo/jsonparser); vulnerabilities
specific to XML or JSON parsing should be reported to those projects
directly (see their own `SECURITY.md`), though a report here that turns out
to originate in one of them is still welcome — it will be routed
appropriately.
