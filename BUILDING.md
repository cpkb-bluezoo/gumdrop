# Building Gumdrop

This document covers how to build and run Gumdrop.

## Building

Building Gumdrop is straightforward. You need Java 17+ and [Apache Ant](https://ant.apache.org/) (you can also use [Gantt](https://github.com/cpkb-bluezoo/gantt)).

```bash
ant
```

Or explicitly:

```bash
ant dist
```

This compiles the project and creates the distribution artifacts. The build downloads external dependencies automatically on first run via `ant resolve-deps` (Ant only — no Maven required). Versions are pinned in `boms/versions.properties`; Dependabot proposes updates via root `pom.xml`.

**Build artifacts:**

| Artifact | Description |
|---|---|
| `dist/gumdrop-container-${version}.zip` | **Primary** servlet container install (Tomcat-style `bin/`, `lib/`, `webapps/`, `conf/`) |
| `dist/gumdrop.jar` | Core server library (all protocols + servlet container; merges optional modules) |
| `dist/gumdrop-telemetry.jar` | Optional OTLP/JSONL export (also merged into `gumdrop.jar`) |
| `dist/gumdrop-http.jar` | OAuthRealm (compiled against http client; merged into `gumdrop.jar`) |
| `dist/gumdrop-ldap.jar` | LDAPRealm (compiled against ldap client; merged into `gumdrop.jar`) |
| `dist/gumdrop-container.jar` | Legacy self-contained fat jar (deprecated; use the zip) |
| `dist/manager.war` | Admin web application |

Unpack the zip and start with `bin/gumdrop.sh conf/gumdroprc.xml.example`, or from a dev tree run `./start` after `ant dist` (uses `dist/container-home/` automatically).

If you don't need the servlet container and want to develop pure async non-blocking services using the Gumdrop framework, you only need `gumdrop.jar` plus [Gonzalez](https://github.com/cpkb-bluezoo/gonzalez) and [jsonparser](https://github.com/cpkb-bluezoo/jsonparser) if you use those.

## Running

Start the server with one of the example configurations in `etc/`:

```bash
./start etc/gumdroprc.servlet
```

You should then be able to point a browser at
[http://localhost:8080/](http://localhost:8080/) or
[https://localhost:8443/](https://localhost:8443/) to see the example web
application included, which includes full documentation of the framework.

Other example configurations are available:

| Configuration | Description |
|---|---|
| `etc/gumdroprc.servlet` | Servlet container (HTTP, HTTPS, HTTP/3) |
| `etc/gumdroprc.webdav` | WebDAV file server |
| `etc/gumdroprc.ftp.file.simple` | Simple FTP file server |
| `etc/gumdroprc.ftp.file.anonymous` | Anonymous FTP file server |
| `etc/gumdroprc.ftp.file.rolebased` | Role-based FTP file server |
| `etc/gumdroprc.imap` | IMAP mailbox access |
| `etc/gumdroprc.pop3` | POP3 mailbox access |
| `etc/gumdroprc.smtp.localdelivery` | SMTP local delivery |
| `etc/gumdroprc.smtp.simplerelay` | SMTP relay (authenticated) |
| `etc/gumdroprc.dns` | DNS caching proxy (UDP, DoT, DoQ) |
| `etc/gumdroprc.mqtt` | MQTT broker (plaintext and optional TLS) |
| `etc/gumdroprc.socks` | SOCKS proxy (SOCKS4/4a/5) |

You can configure any of these to serve your own application and run it
immediately.

