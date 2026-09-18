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

- **Bluezoo:** gonzalez-core, jsonparser, jprotobuf
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

### 1. Create a locally trusted certificate

Install [mkcert](https://github.com/FiloSottile/mkcert) and OpenSSL, then from the repo root:

```bash
chmod +x scripts/dev-tls-setup.sh   # once
./scripts/dev-tls-setup.sh
```

On Windows:

```bat
scripts\dev-tls-setup.bat
```

The script:

- Runs `mkcert -install` (adds mkcert’s CA to the **OS trust store**; may prompt for elevation)
- Writes **gitignored** files under `etc/`: `localhost.pem`, `localhost-key.pem`, `keystore.p12`
- Copies `keystore.p12` into `dist/container-home/conf/` when that directory exists

Default PKCS#12 password is `changeit` (override with `GUMDROP_DEV_TLS_STORE_PASS`).

Re-run `ant assemble-container` if you rebuilt the tree after generating the keystore; then run `dev-tls-setup` again to copy the keystore into `conf/`, or copy `etc/keystore.p12` to `dist/container-home/conf/` yourself.

### 2. TLS server configuration

**[etc/server-tls.xml](etc/server-tls.xml)** is the example: cleartext `8080` plus secure `8443` with `keystore-file="keystore.p12"`. It is copied to `conf/server-tls.xml` by `assemble-container`. Adjust listeners or password there if you change the keystore.

### 3. Start with TLS config

```bash
./start-tls          # POSIX
start-tls.bat        # Windows
```

This starts the container with `conf/server-tls.xml` as the config file.

### 4. Verify

With mkcert’s CA installed, browsers and `curl` should trust the site:

```bash
curl -sS https://localhost:8443/
```

Same over the IPv6 loopback literal (brackets required in URLs):

```bash
curl -sS 'https://[::1]:8443/'
```

HTTP/3 uses the same port via QUIC; browsers negotiate Alt-Svc after the first HTTPS response. In production you can publish HTTPS/DNS records so clients skip that discovery hop. To deploy this servlet stack (or your own Gumdrop `main`) in Docker/Podman/Kubernetes, see [docs/CONTAINER-DEPLOYMENT.md](docs/CONTAINER-DEPLOYMENT.md).