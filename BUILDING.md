# Building Gumdrop

This document covers how to build and run Gumdrop.

## Building

Building Gumdrop is straightforward. You need Java 25+ and [Apache Ant](https://ant.apache.org/) (you can also use [Gantt](https://github.com/cpkb-bluezoo/gantt)).

```bash
ant
```

Or explicitly:

```bash
ant dist
```

This compiles the project and creates the distribution artifacts. External jars (Gonzalez, jsonparser, and the six javax.* API jars) are **not** in the repository; the first build downloads them into `lib/` via `ant resolve-deps` (Ant only — no Maven required). That target runs automatically when compiling or assembling the container. Versions are pinned in `boms/versions.properties`; Dependabot proposes updates via root `pom.xml`. You need network access on a fresh clone until `lib/` is populated.

JUnit and Hamcrest (under `test/junit/lib/`) are downloaded separately by `ant resolve-test-deps`, which runs automatically when you invoke test targets (`ant test`, `ant integration-test`, etc.) but **not** for a compile-only build (`ant`, `ant dist`).

**Build artifacts:**

| Artifact | Description |
|---|---|
| `dist/gumdrop-container-${version}.zip` | **Primary** servlet container install (Tomcat-style `bin/`, `lib/`, `webapps/`, `conf/`) |
| `dist/gumdrop.jar` | Core server library (all protocols + servlet container; merges optional modules) |
| `dist/gumdrop-telemetry.jar` | Optional OTLP/JSONL export (also merged into `gumdrop.jar`) |
| `dist/gumdrop-http.jar` | OAuthRealm (compiled against http client; merged into `gumdrop.jar`) |
| `dist/gumdrop-ldap.jar` | LdapRealm (compiled against ldap client; merged into `gumdrop.jar`) |
| `dist/gumdrop-container.jar` | Legacy self-contained fat jar (deprecated; use the zip) |
| `dist/manager.war` | Admin web application |

If you don't need the servlet container and want to develop pure async non-blocking services using the Gumdrop framework, you only need `gumdrop.jar` plus [Gonzalez](https://github.com/cpkb-bluezoo/gonzalez) and [jsonparser](https://github.com/cpkb-bluezoo/jsonparser) if you use those.

## Running

Gumdrop 3 applications are assembled in Java — no configuration file to
point at. Write a `main` that composes the servers you want (see
[web/configuration.html](web/configuration.html) for the canonical patterns, and
`examples/*` for runnable ones), then run it with `gumdrop.jar` (plus any
optional module jars it needs) on the classpath.

