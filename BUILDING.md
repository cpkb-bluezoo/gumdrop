# Building Gumdrop

This document covers how to build and run Gumdrop from a git checkout.

## Building

You need **Java 25+** and [Apache Ant](https://ant.apache.org/) (or [Gantt](https://github.com/cpkb-bluezoo/gantt)).

```bash
ant
```

Or explicitly:

```bash
ant dist
```

This compiles the project and creates distribution artifacts.

### External dependencies

Jars under `lib/` are **not** in git. The first build downloads them via `ant resolve-deps` (run automatically when you compile or run `ant assemble-container`). Versions are pinned in `boms/versions.properties`; Dependabot tracks the Maven coordinates in root `pom.xml`. You need network access on a fresh clone until `lib/` is populated.

See [lib/README](lib/README) for the full list. In short:

- **Bluezoo:** gonzalez-core, jsonparser, jprotobuf, micula (Brotli)
- **Jakarta (Servlet 6.x stack):** jakarta.servlet-api, jakarta.annotation-api, jakarta.persistence-api (compile)
- **Legacy javax / Java EE APIs** (container classpath): javax.mail, javax.annotation-api, javax.ejb-api, javax.persistence-api, jaxws-api

JUnit and Hamcrest (`test/junit/lib/`) are fetched by `ant resolve-test-deps` when you run test targets (`ant test`, `ant integration-test`, etc.), not for a compile-only build.

### Build artifacts


| Artifact                                         | Description                                                                  |
| ------------------------------------------------ | ---------------------------------------------------------------------------- |
| `dist/gumdrop-container-${version}.zip`          | Servlet container install (Tomcat-style `bin/`, `lib/`, `webapps/`, `conf/`) |
| `dist/container-home/`                           | Same tree as the zip, used by repo-root `start` / `start-tls`                |
| `dist/gumdrop.jar`                               | Core server library (all protocols + servlet container)                      |
| `dist/gumdrop-telemetry.jar`                     | Optional OTLP/JSONL export (merged into `gumdrop.jar`)                       |
| `dist/gumdrop-http.jar`, `dist/gumdrop-ldap.jar` | Optional modules merged into `gumdrop.jar`                                   |
| `dist/gumdrop-container.jar`                     | Legacy fat jar (deprecated; use the zip or `container-home`)                 |
| `dist/manager.war`                               | Admin web application                                                        |


If you are **not** using the stock servlet container and only embed Gumdrop as a library, you typically need `dist/gumdrop.jar` or individual submodules plus Gonzalez and jsonparser when your code uses them.

## Running the stock servlet container (quick start)

The **framework** default is composition in Java ([web/configuration.html](web/configuration.html)). The **git checkout** also ships a Tomcat-style container tree for smoke tests and local demos.

### 1. Assemble the install tree

```bash
ant assemble-container
```

This builds jars, copies launchers into `dist/container-home/`, deploys the sample `web/` app to `webapps/ROOT`, and installs configuration under `conf/`.

### 2. Start HTTP on port 8080

From the repository root:

```bash
./start          # POSIX (sh)
start.bat        # Windows
```

These prefer `dist/container-home` as `GUMDROP_HOME` and run `bin/gumdrop.sh` / `bin/gumdrop.bat`, which launch `Bootstrap` → `ContainerMain`.

### 3. Configuration

The default config file is **[etc/server.xml](etc/server.xml)** (copied to `dist/container-home/conf/server.xml` by `assemble-container`). Edit the copy under `conf/` for a running install, or edit `etc/server.xml` in git and re-run `assemble-container` to refresh the tree.

`ContainerMain` resolves `server.xml` in this order:

1. First CLI argument (what `start-tls` uses)
2. `GUMDROP_CONFIG` environment variable
3. `$GUMDROP_HOME/conf/server.xml`
4. `./conf/server.xml`

Realm users for the sample apps live in [etc/realm-servlet.xml](etc/realm-servlet.xml).

Optional: `GUMDROP_DRAIN_TIMEOUT_MS=0` for fast Ctrl+C shutdown during development.

### 4. Beyond servlet smoke tests

| Goal | Where to look |
|------|----------------|
| Custom protocol or HTTP servers in Java (no `server.xml`) | [web/configuration.html](web/configuration.html), `examples/*` |
| Run **your** Gumdrop app or the servlet container image in Docker/Podman/Kubernetes | [docs/CONTAINER-DEPLOYMENT.md](docs/CONTAINER-DEPLOYMENT.md) (cloud **OCI** containers — separate from this local servlet install tree) |

## Local HTTPS and HTTP/3 (TLS smoke test)

After the plain HTTP smoke test, you can run **TLS on 8443** with **HTTP/2 and HTTP/3 (QUIC)** on the same port. Gumdrop adds HTTP/3 automatically for each `secure="true"` listener in `server.xml` (pure Java; no native QUIC library).

### 1. Create the TLS files

```bash
ant tls-certs
```

This writes three [PEM](https://en.wikipedia.org/wiki/Privacy-Enhanced_Mail) files to `etc/tls/` (gitignored), valid for `localhost`, `127.0.0.1` and `::1`:

| File | Holds |
|------|-------|
| `key.pem` | the server's private key |
| `cert.pem` | the server's certificate |
| `ca.pem` | the CA that signed it: the trust anchor for clients |

Plain PEM is the simplest way to configure a listener: no keystore and no password. If you would rather use Java keystores, see [Java keystores](#java-keystores-instead-of-pem) below.

The target needs [mkcert](https://github.com/FiloSottile/mkcert) or OpenSSL. It uses mkcert if it finds it, and falls back to OpenSSL (creating a private CA) if not. If neither is installed the build stops and prints the install command for your system:

| | mkcert (recommended) | OpenSSL |
|---|---|---|
| macOS | `brew install mkcert` | `brew install openssl` |
| Debian / Ubuntu | `sudo apt install mkcert` | `sudo apt install openssl` |
| Fedora | `sudo dnf install mkcert` | `sudo dnf install openssl` |
| Windows | `winget install FiloSottile.mkcert` | `winget install ShiningLight.OpenSSL.Light` (Git for Windows' copy is found automatically) |

Nothing outside the checkout changes: the CA lives in `etc/tls/ca/`, and the target does not touch your operating system's trust store. Running it again does nothing if the files exist; `-Dtls.force=true` makes a new certificate under the same CA. Other properties (`-Dtls.names="localhost example.test"`, `-Dtls.keytype=rsa`, `-Dtls.dir=...`) are listed at the top of [ant/tls.xml](ant/tls.xml).

Then assemble the container, which copies the PEM files into `dist/container-home/conf/tls/`:

```bash
ant assemble-container
```

(If you run `ant tls-certs` after assembling, run `ant assemble-container` again, or copy `etc/tls/*.pem` to `dist/container-home/conf/tls/`.) Both can be given in one command: `ant tls-certs assemble-container`.

### 2. TLS server configuration

**[etc/server-tls.xml](etc/server-tls.xml)** is the example: cleartext `8080` plus secure `8443` with `cert-file="tls/cert.pem"` and `key-file="tls/key.pem"`. It is copied to `conf/server-tls.xml` by `assemble-container`.

### 3. Start with TLS config

```bash
./start-tls          # POSIX
start-tls.bat        # Windows
```

This starts the container with `conf/server-tls.xml` as the config file.

### 4. Verify

`curl` can be given the CA directly, so no trust store needs to change:

```bash
curl --cacert etc/tls/ca.pem https://localhost:8443/
```

Same over the IPv6 loopback literal (brackets required in URLs):

```bash
curl --cacert etc/tls/ca.pem 'https://[::1]:8443/'
```

### 5. Browsers and other tools that use the system trust store

Browsers do not read `ca.pem`; they use the operating system's trust store. With mkcert installed you can add the CA to it:

```bash
ant tls-trust-install      # may ask for your password
```

after which browsers and plain `curl https://localhost:8443/` trust the site. Take it out again when you are done:

```bash
ant tls-trust-uninstall tls-clean
```

Anyone who can read `etc/tls/ca/rootCA-key.pem` can create certificates that your browser trusts once the CA is installed, so keep it private and remove it when finished. Without mkcert, `tls-trust-install` prints how to add `ca.pem` to your system's trust store by hand.

HTTP/3 uses the same port via QUIC; browsers negotiate Alt-Svc after the first HTTPS response. In production you can publish HTTPS/DNS records so clients skip that discovery hop. To deploy this servlet stack (or your own Gumdrop `main`) in Docker/Podman/Kubernetes, see [docs/CONTAINER-DEPLOYMENT.md](docs/CONTAINER-DEPLOYMENT.md).

### Java keystores instead of PEM

If you prefer Java keystores (PKCS#12, or JKS), build them from the same files:

```bash
ant tls-keystore
```

This adds `etc/tls/keystore.p12` (the server key and certificate) and `etc/tls/truststore.p12` (the CA as a trust anchor), both with the password `changeit` (`-Dtls.keystore.pass=...` to change it). Building the keystore needs OpenSSL; the truststore needs only the JDK. Point a listener at the keystore instead of the PEM files:

```xml
<listener port="8443" secure="true" keystore-file="tls/keystore.p12"
          keystore-pass="changeit" bind-wildcard="true"/>
```

(`keystore-format="JKS"` for a JKS store.) A listener takes either the PEM attributes or the keystore attributes, not both. See [web/security.html](web/security.html#tls-certificates) for the equivalent Java API and for converting between the formats.

### Certificates for the integration tests

The integration targets that need TLS files (`integration-setup` and `integration-test-tls`, so `ant integration-test`) run `ant integration-tls` first, which ensures the same three PEM files under `etc/tls/` (with the extra name `test.gumdrop.local` that some tests connect to). It never fails the build: without mkcert or OpenSSL it prints a warning, and the tests that need the files are skipped.

To exercise a locally built micula (for example certificate compression or Brotli fixes not yet on Maven Central), put `micula-<version>.jar` under `lib/` and pass the version on the Ant command line only — do not change `boms/versions.properties`:

```bash
ant -Dmicula.version=20260919 integration-test
```

CI and default local builds keep `micula.version=1.0.0` from `boms/versions.properties`.
